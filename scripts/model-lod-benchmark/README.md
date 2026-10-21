This benchmark compares the full-mesh submission policy used before dedicated model LOD with adaptive LOD in the current binary. Both modes use identical captured Create geometry, world snapshot, shaders, resolution and camera path. It is a controlled comparison of the mesh policy, not a benchmark of two historical mod binaries.

Run from the repository root with Java 21 and an existing Minecraft 1.21.1 world:

```powershell
New-Item -ItemType Directory -Force build | Out-Null
.\gradlew.bat runClient --offline --console=plain -I scripts/benchmark-model-lod.gradle -PbenchmarkWorld="D:/path/to/world" *> build/model-lod-benchmark-sodium.log
.\gradlew.bat runClient --offline --console=plain -I scripts/benchmark-model-lod.gradle -PbenchmarkWorld="D:/path/to/world" -PbenchmarkShaders=true *> build/model-lod-benchmark-iris.log
python scripts/model-lod-benchmark/summarize.py
```

Omit `--offline` when dependencies are not cached. The shader run requires `run/client/shaderpacks/ComplementaryReimagined_r5.8.1.zip`. The world is copied into `build/reports/model-lod-benchmark/{sodium,iris}/saves/benchmark-world`; each run replaces only that disposable copy. The source world is never modified. The repository's existing local verification world is the default when `benchmarkWorld` is omitted.

The client captures four real Create assets (carriage, small/large standard bogeys and a kinetic section), then renders 512 instances to a 1920×1080 offscreen framebuffer using the active Voxy shader pipeline during normal Minecraft frames. Rasterization and depth testing remain enabled. The offscreen view follows a deterministic 120-frame camera path. It is independent of the player's camera and does not add blocks to the save.

After 120 warmup frames, the benchmark runs full/LOD/LOD/full blocks. Each block discards 30 settling frames and records the same 120 camera positions. GPU queries are read asynchronously and previews are captured only during warmup. Completion requires all 480 samples, nonempty rendered previews and the expected shader (including the actual patched shader in Iris); the client then saves and exits. `results.json`, `source-world.json` and preview PNGs are written in each profile directory. The shader pack uses its default options; its hash and the benchmark source hash are recorded.

The commands retain Gradle console logs at `build/model-lod-benchmark-sodium.log` and `build/model-lod-benchmark-iris.log`. The Python summarizer requires those logs, both completed profiles, no client errors, matching geometry/world/source metadata and complete frame ordering. It writes `summary.json` and `summary.md` in `build/reports/model-lod-benchmark`, including mean/median/p95, separate block medians and per-asset preparation/memory. A failed run cannot reuse stale results or count duplicate frames while waiting for GPU queries.

The measurements have distinct meanings:

- `submitNs`: CPU time for the 512 model transformations, uniforms, LOD selection and draw calls.
- `gpuNs`: GPU time for those model draws, including rasterization and depth testing. Framebuffer clears are outside this query.
- `frameNs`: wall time between NeoForge's render-frame Pre/Post events, including normal Minecraft rendering and the added scene. Presentation, the FPS limiter and VSync waiting outside these events are excluded; this is not an FPS prediction.
- `fullPrepareNs` / `lodPrepareNs`: native-buffer allocation/copy versus the same work plus mesh simplification, with 10 warmup and 20 measured iterations in alternating order. `sourceCaptureNs` records initial model capture separately; it is not included in the paired preparation comparison.
- CPU payload bytes: original vertices plus any coarse arrays during preparation, excluding Java object overhead and temporary simplifier data. These arrays are released after upload. They are not a measurement of the whole client heap or peak process RSS.
- GPU bytes: each unique mesh's private vertex/index buffers. Shared quad indices, textures, common framebuffer attachments and repeated instances are not charged per mesh.

This is a workload of many distant models with small screen coverage. The previews establish that rasterization occurred, not that model fidelity or complete in-world integration passed. CPU selection can cost more even when geometry and GPU work decrease. Treat frame timings as descriptive results from one run per pipeline, and inspect block drift before generalizing. Whole-client heap/RSS, temporary simplifier peaks, historical-binary comparisons and general FPS gains are outside this benchmark.

F3's dedicated-mesh counters cover all `DistantMesh` calls in one `GameRenderer.renderLevel`, across views and passes. The full-detail denominator includes would-be full geometry for subpixel-culled meshes; depth replay is counted in totals and also shown separately. Terrain, native Sable geometry, and models rejected before a draw call are excluded. The benchmark's per-scene statistics use differences around its own draws.

The init script adds benchmark sources only for that invocation. After benchmarking, run a normal build to remove benchmark classes from production output:

```powershell
.\gradlew.bat build --offline --console=plain -I scripts/enable-mesh-gpu-tests.gradle
```

Do not run the benchmark and a normal Gradle build concurrently in the same checkout.
