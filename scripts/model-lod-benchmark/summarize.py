"""Validate and summarize the opt-in model LOD benchmark (Python 3.10+, stdlib only)."""
import argparse
import json
import math
import statistics
from pathlib import Path


ORDER = ["full", "lod", "lod", "full"]
TIMINGS = ("submitNs", "gpuNs", "frameNs")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def distribution(values, scale=1):
    values = sorted(value / scale for value in values)
    require(bool(values), "empty sample set")
    return {"mean": statistics.mean(values), "median": statistics.median(values),
            "p95": values[math.ceil(len(values) * .95) - 1], "min": values[0], "max": values[-1]}


def validate_profile(data, metadata, log, profile):
    pipeline = "IrisVoxyRenderPipeline" if profile == "iris" else "NormalRenderPipeline"
    require(data["pipeline"] == pipeline, f"{profile}: unexpected pipeline")
    require(f"MODEL_LOD_BENCH PASS frames=480 pipeline={pipeline}" in log, f"{profile}: missing PASS")
    require("BUILD SUCCESSFUL" in log, f"{profile}: Gradle did not succeed")
    errors = [line for line in log.splitlines() if any(token in line for token in
              ("/ERROR]", "/FATAL]", "GL_INVALID_", "falling back to plain"))]
    require(not errors, f"{profile}: client errors: {errors[:5]}")
    require(data["shaderVerified"] is True, f"{profile}: shader not verified")
    require(metadata["shaders"] == (profile == "iris"), f"{profile}: shader metadata mismatch")
    require(data["resolution"] == metadata["resolution"] == [1920, 1080], "resolution mismatch")
    require(data["blockOrder"] == ORDER and data["instances"] == 512, "workload mismatch")
    require(data["pathFrames"] == data["warmupFrames"] == 120 and data["settleFramesPerBlock"] == 30,
            "sample schedule mismatch")
    require(data["cameraPath"] == "far-grid-v1" and data["fovDegrees"] == 70
            and data["subDivisionSize"] == 256 and data["fullDetailDistance"] == 96, "view mismatch")
    require(len(data["bakes"]) == 4 and len({row["name"] for row in data["bakes"]}) == 4, "fixture mismatch")
    for row in data["bakes"]:
        for key in ("fullPrepareNs", "lodPrepareNs"):
            require(len(row[key]) == 20 and all(value > 0 for value in row[key]), "missing bake samples")
        require(row["sourceCaptureNs"] > 0 and row["fullTriangles"] > 0, "missing source capture")
        require(row["lodTriangles"] and all(0 < n < row["fullTriangles"] for n in row["lodTriangles"]),
                "fixture has no geometry reduction")
        for kind in ("CpuPayloadBytes", "GpuBytes"):
            require(row[f"lod{kind}"] > row[f"full{kind}"] > 0, "missing memory accounting")
    frames = data["frames"]
    require(len(frames) == 480, f"{profile}: expected 480 samples, got {len(frames)}")
    denominator = sum(row["fullTriangles"] for row in data["bakes"]) * 128
    for index, row in enumerate(frames):
        block, position = divmod(index, 120)
        require((row["block"], row["pathIndex"], row["mode"]) == (block, position, ORDER[block]),
                f"{profile}: missing, reordered or duplicated frame {index}")
        require(all(math.isfinite(row[key]) and row[key] > 0 for key in TIMINGS), "invalid frame timing")
        require(row["fullDetailTriangles"] == denominator, "full geometry denominator changed")
        require(row["coarseDraws"] + row["detailedDraws"] == 512, "missing scene draws")
        if row["mode"] == "full":
            require(row["coarseDraws"] == 0 and row["submittedTriangles"] == denominator, "invalid baseline")
        else:
            require(row["coarseDraws"] > 0 and 0 < row["submittedTriangles"] < denominator, "LOD did not reduce geometry")
    for position in range(120):
        a, b = frames[120 + position], frames[240 + position]
        require(all(a[key] == b[key] for key in
                    ("submittedTriangles", "fullDetailTriangles", "coarseDraws", "detailedDraws")),
                f"{profile}: LOD changed on repeated path position {position}")


def summarize(data):
    result = {"pipeline": data["pipeline"], "renderer": data["renderer"], "glVersion": data["glVersion"],
              "javaVersion": data["javaVersion"], "modes": {}, "blocks": [], "bakes": []}
    for mode in ("full", "lod"):
        rows = [row for row in data["frames"] if row["mode"] == mode]
        result["modes"][mode] = {"samples": len(rows),
            "timingMs": {key[:-2]: distribution([row[key] for row in rows], 1e6) for key in TIMINGS},
            "triangles": distribution([row["submittedTriangles"] for row in rows]),
            "coarseDraws": distribution([row["coarseDraws"] for row in rows])}
    for block, mode in enumerate(ORDER):
        rows = [row for row in data["frames"] if row["block"] == block]
        result["blocks"].append({"block": block, "mode": mode,
            "timingMs": {key[:-2]: distribution([row[key] for row in rows], 1e6) for key in TIMINGS}})
    full, lod = (result["modes"][mode] for mode in ("full", "lod"))
    result["triangleReductionPercent"] = 100 * (1 - lod["triangles"]["mean"] / full["triangles"]["mean"])
    result["medianTimingChangePercent"] = {key: 100 * (lod["timingMs"][key]["median"] /
        full["timingMs"][key]["median"] - 1) for key in ("submit", "gpu", "frame")}
    for row in data["bakes"]:
        result["bakes"].append({key: value for key, value in row.items() if key not in ("fullPrepareNs", "lodPrepareNs")}
            | {"fullPrepareMs": distribution(row["fullPrepareNs"], 1e6),
               "lodPrepareMs": distribution(row["lodPrepareNs"], 1e6)})
    result["memoryBytes"] = {key: sum(row[key] for row in data["bakes"]) for key in
        ("fullCpuPayloadBytes", "lodCpuPayloadBytes", "fullGpuBytes", "lodGpuBytes")}
    return result


def markdown(report):
    meta = report["profiles"]["sodium"]["metadata"]
    lines = ["# Dedicated model LOD benchmark", "",
        "Controlled comparison of full-mesh and adaptive submission policies in the same binary.", "",
        f"Minecraft {meta['minecraft']}, NeoForge {meta['neoforge']}, Sodium {meta['sodium']}, Create {meta['create']}.",
        f"World level.dat SHA-256: `{meta['levelDatSha256']}`.", "",
        "512 instances from four captured Create assets; 1920×1080; FOV 70; subdivision 256; full detail range 96 blocks. "
        "The same 120-position camera path runs in full/LOD/LOD/full order after 120 warmup frames, "
        "with 30 settling frames per block and 240 measured frames per mode. p95 uses the nearest-rank definition.", ""]
    for profile, result in report["profiles"].items():
        lines += [f"## {profile}", "", f"{result['renderer']} / {result['glVersion']}; Java {result['javaVersion']}.", ""]
        if profile == "iris":
            metadata = result["metadata"]
            lines += [f"Iris {metadata['iris']}; {metadata['shaderPack']}; {metadata['shaderOptions']}. "
                      f"Pack SHA-256: `{metadata['shaderPackSha256']}`.", ""]
        lines += ["| Measurement | Full mean / median / p95 | LOD mean / median / p95 |",
                  "|---|---:|---:|"]
        for key in ("submit", "gpu", "frame"):
            cells = [" / ".join(f"{result['modes'][mode]['timingMs'][key][stat]:.4f}" for stat in ("mean", "median", "p95"))
                     for mode in ("full", "lod")]
            lines.append(f"| {key} (ms) | {cells[0]} | {cells[1]} |")
        full, lod = (result["modes"][mode]["triangles"] for mode in ("full", "lod"))
        lines += ["", f"Mean submitted triangles: **{full['mean']:,.0f} → {lod['mean']:,.0f} "
            f"(−{result['triangleReductionPercent']:.2f}%)**. LOD range: {lod['min']:,.0f}–{lod['max']:,.0f}.", "",
            "Block medians expose warmup/order drift; these are descriptive samples from one run, not confidence intervals.", "",
            "| Block | Mode | CPU submit ms | GPU ms | Render-frame ms |", "|---:|---|---:|---:|---:|"]
        for block in result["blocks"]:
            cells = " | ".join(f"{block['timingMs'][key]['median']:.4f}" for key in ("submit", "gpu", "frame"))
            lines.append(f"| {block['block']} | {block['mode']} | {cells} |")
        lines += ["", "| Asset | Initial capture ms | Full preparation median / p95 ms | LOD preparation median / p95 ms | CPU payload bytes full → LOD | GPU bytes full → LOD |",
                  "|---|---:|---:|---:|---:|---:|"]
        for bake in result["bakes"]:
            cells = [f"{bake[key]['median']:.4f} / {bake[key]['p95']:.4f}" for key in ("fullPrepareMs", "lodPrepareMs")]
            lines.append(f"| {bake['name']} | {bake['sourceCaptureNs'] / 1e6:.3f} | {cells[0]} | {cells[1]} | "
                f"{bake['fullCpuPayloadBytes']:,} → {bake['lodCpuPayloadBytes']:,} | {bake['fullGpuBytes']:,} → {bake['lodGpuBytes']:,} |")
        memory = result["memoryBytes"]
        lines += ["", f"Four unique meshes: private GPU buffers {memory['fullGpuBytes']:,} → {memory['lodGpuBytes']:,} bytes "
            f"(+{100 * (memory['lodGpuBytes'] / memory['fullGpuBytes'] - 1):.2f}%). "
            "The 512 instances share those meshes; memory is not multiplied by instance count.", "",
            f"Evidence: [{profile}/results.json]({profile}/results.json), [{profile}/logs/latest.log]({profile}/logs/latest.log), "
            f"[full preview]({profile}/preview-full.png), [LOD preview]({profile}/preview-lod.png).", ""]
    lines += ["## Interpretation and limits", "",
        "- The geometry reduction is reproducible on both pipelines. LOD selection adds CPU submission work, "
        "and building coarse meshes adds preparation time and private buffer storage. Read these costs alongside GPU time.",
        "- The scene uses distant models with small screen coverage, rasterization and depth testing enabled. "
        "It measures a geometry-heavy workload; it does not represent a close-up, fill-rate-heavy scene or validate visual fidelity.",
        "- CPU submit timers cover transformations, uniforms, selection and draw calls. GPU timers cover the added scene draws "
        "(not framebuffer clears); queries are read asynchronously.",
        "- Render-frame timers span NeoForge Pre/Post events and include normal game rendering plus the added offscreen workload. "
        "They exclude presentation and FPS-limiter/VSync waits outside those events. Background world work and scheduling remain variable. "
        "A triangle or GPU-time reduction is not evidence of a general FPS increase.",
        "- Preparation pairs allocate/copy the identical captured vertex stream, with or without simplification (10 warmups, "
        "20 samples each, alternating order). Initial Create capture is a separate one-off measurement using the current baker, "
        "not a historical full-baker baseline. Upload time is not included in paired preparation.",
        "- CPU payload is original vertices plus coarse arrays, released after upload; temporary simplifier allocations, "
        "Java object overhead, whole-client heap and process RSS are not measured. GPU totals count private buffers only; "
        "shared indices, textures and the common eight-target framebuffer are excluded.",
        "- World identity is checked by level.dat hash, with the source save copied before each run. "
        "The benchmark does not hash every region file or compare historical mod binaries.", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reports", type=Path, default=Path(__file__).resolve().parents[2] / "build/reports/model-lod-benchmark")
    args = parser.parse_args()
    report = {"profiles": {}}
    raw = {}
    for profile in ("sodium", "iris"):
        directory = args.reports / profile
        data = json.loads((directory / "results.json").read_text(encoding="utf-8"))
        metadata = json.loads((directory / "source-world.json").read_text(encoding="utf-8"))
        # The Gradle log is kept outside each disposable client profile.
        log = (args.reports.parent.parent / f"model-lod-benchmark-{profile}.log").read_text(encoding="utf-8-sig")
        validate_profile(data, metadata, log, profile)
        for mode in ("full", "lod"):
            require((directory / f"preview-{mode}.png").is_file(), f"{profile}: missing preview")
            require(f"MODEL_LOD_BENCH preview {mode} visiblePixels=" in log, f"{profile}: preview not verified")
        raw[profile] = data
        report["profiles"][profile] = summarize(data) | {"metadata": metadata}
    for key in ("levelDatSha256", "benchmarkSourceSha256", "minecraft", "neoforge", "sodium", "create", "resolution", "renderDistance"):
        require(report["profiles"]["sodium"]["metadata"][key] == report["profiles"]["iris"]["metadata"][key], f"profile mismatch: {key}")
    for key in ("renderer", "glVersion", "javaVersion"):
        require(raw["sodium"][key] == raw["iris"][key], f"environment mismatch: {key}")
    for sodium, iris in zip(raw["sodium"]["bakes"], raw["iris"]["bakes"]):
        for key in ("name", "fullTriangles", "lodTriangles", "fullCpuPayloadBytes", "lodCpuPayloadBytes", "fullGpuBytes", "lodGpuBytes"):
            require(sodium[key] == iris[key], f"fixture mismatch: {key}")
    for sodium, iris in zip(raw["sodium"]["frames"], raw["iris"]["frames"]):
        require(sodium["submittedTriangles"] == iris["submittedTriangles"], "pipeline geometry mismatch")
    (args.reports / "summary.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (args.reports / "summary.md").write_text(markdown(report), encoding="utf-8")
    print(f"Validated 960 frame samples and 320 preparation samples: {args.reports / 'summary.md'}")


if __name__ == "__main__":
    main()
