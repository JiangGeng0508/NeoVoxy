#!/usr/bin/env python3
"""Exercise the Gradle language gate in isolated projects; never edit main resources."""

import json
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / "build/reports/lang-keys"
SOURCE = r'''
class Probe {
    void probe(String value) {
        Component /* a comment between tokens */ . translatable("voxy.required");
        Component.translatable(("voxy." + "joined"));
        Component.translatableWithFallback("voxy.fallback", "fallback text");
        Component.translatable("voxy.\u0075nicode");
        Component.translatable("voxy.dynamic." + value);
        Component.translatable("voxy.dynamic" + value);
        String category = "key.categories.voxy";
        String message = "message.voxy." + "notice";
        String dynamicMessage = "message.voxy.dynamic" + value;
        // Component.translatable("voxy.comment");
        /* Component.translatable("voxy.blockComment"); */
        String example = "Component.translatable(\"voxy.example\")";
    }
}
'''
KEYS = ["voxy.required", "voxy.joined", "voxy.fallback", "voxy.unicode",
        "key.categories.voxy", "message.voxy.notice"]
BASE = dict.fromkeys(KEYS, "翻译 / translation")


def main():
    REPORTS.mkdir(parents=True, exist_ok=True)
    fixtures = Path(tempfile.mkdtemp(prefix="fixtures-", dir=REPORTS))
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    checker = (ROOT / "scripts/check-lang-keys.gradle").as_posix().replace("'", "\\'")
    cases = []

    def case(name, *, english=BASE, chinese=BASE, source=SOURCE, raw_chinese=None,
             error=None, extra_locale=None, empty_directories=True):
        project = fixtures / name
        lang = project / "src/main/resources/assets/voxy/lang"
        java = project / "src/main/java"
        project.mkdir(parents=True)
        if empty_directories:
            lang.mkdir(parents=True)
            java.mkdir(parents=True)
        (project / "settings.gradle").write_text("rootProject.name = 'lang-fixture'\n", encoding="utf-8")
        (project / "build.gradle").write_text(f"apply from: '{checker}'\n", encoding="utf-8")
        for locale, data in [("en_us", english), ("zh_cn", chinese), ("extra", extra_locale)]:
            if data is not None:
                lang.mkdir(parents=True, exist_ok=True)
                (lang / f"{locale}.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        if raw_chinese is not None:
            (lang / "zh_cn.json").write_text(raw_chinese, encoding="utf-8")
        if source is not None:
            java.mkdir(parents=True, exist_ok=True)
            (java / "Probe.java").write_text(source, encoding="utf-8")
        command = [str(wrapper), "-p", str(project), "checkLangKeys", "--offline", "--console=plain"]
        result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (project / "gradle.log").write_bytes(result.stdout)
        report_path = project / "build/reports/lang-keys/check.json"
        report = json.loads(report_path.read_text(encoding="utf-8")) if report_path.exists() else {}
        actual_errors = "\n".join(report.get("problems", []))
        if error is None:
            passed = (result.returncode == 0 and report.get("state") == "passed"
                      and set(report.get("requested", {})) == set(KEYS)
                      and len(report.get("dynamicReferences", [])) == 2)
        else:
            passed = result.returncode != 0 and report.get("state") == "failed" and error in actual_errors
        cases.append({"name": name, "passed": passed, "exitCode": result.returncode,
                      "expectedError": error, "report": str(report_path.relative_to(ROOT))})
        print(f"{'PASS' if passed else 'FAIL'} {name}", flush=True)

    case("valid-static-and-dynamic")
    case("parity", chinese={**BASE, "voxy.onlyChinese": "中文"}, error="missing key present in another locale")
    missing = {key: value for key, value in BASE.items() if key != "voxy.required"}
    case("missing-static-in-both", english=missing, chinese=missing, error="missing static key voxy.required")
    missing = {key: value for key, value in BASE.items() if key != "message.voxy.notice"}
    case("missing-indirect-in-both", english=missing, chinese=missing, error="missing static key message.voxy.notice")
    case("literal-ending-dot", source=SOURCE.replace('"voxy.required"', '"voxy.required."'),
         error="missing static key voxy.required.")
    case("missing-english", english=None, error="Required locale is missing: en_us.json")
    case("missing-chinese", chinese=None, error="Required locale is missing: zh_cn.json")
    case("missing-all-locales", english=None, chinese=None, error="Required locale is missing")
    case("missing-lang-directory", english=None, chinese=None, empty_directories=False,
         error="Required locale is missing")
    case("extra-locale-parity", extra_locale={"voxy.required": "value"}, error="extra.json: missing key")
    case("malformed-json", raw_chinese='{"voxy.required":', error="invalid JSON")
    case("non-object-json", chinese=["value"], error="expected a non-empty JSON object")
    case("empty-locale", chinese={}, error="expected a non-empty JSON object")
    case("non-string-translation", chinese={**BASE, "voxy.required": 42}, error="must have a non-empty string")
    case("blank-translation", chinese={**BASE, "voxy.required": " \t"}, error="must have a non-empty string")
    case("missing-sources", source=None, error="No Java sources found")
    case("missing-java-directory", source=None, empty_directories=False, error="No Java sources found")
    case("invalid-java", source="class Broken {", error="Cannot parse Java sources")
    summary = {"state": "passed" if all(case["passed"] for case in cases) else "failed", "cases": cases}
    (REPORTS / "fixture-verification.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"{sum(case['passed'] for case in cases)}/{len(cases)} checks passed; fixtures: {fixtures}")
    return 0 if summary["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
