"""Summarize JUnit GPU evidence, distinguishing passed, failed, partial and not_run."""
import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET


def summarize(results, root, run_status):
    expected = {'.'.join(path.relative_to(root / 'src/test/java').with_suffix('').parts)
                for path in (root / 'src/test/java').rglob('*GpuTest.java')}
    suites = []
    for path in sorted(results.glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        if suite.attrib['name'] not in expected:
            continue
        output = suite.findtext('system-out', '')
        environment = [json.loads(line.removeprefix('GPU_ENV ')) for line in output.splitlines()
                       if line.startswith('GPU_ENV ')]
        counts = {name: int(count) for name, count in re.findall(
            r'^GPU_CHECKS (.+) assertionsCompleted=(\d+)$', output, re.MULTILINE)}
        suites.append({'name': suite.attrib['name'], 'timestamp': suite.attrib.get('timestamp'),
                       **{key: int(suite.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')},
                       'environment': environment, 'dataChecks': counts,
                       'failedTests': [case.attrib['name'] for case in suite.findall('testcase')
                                       if case.find('failure') is not None or case.find('error') is not None]})
    totals = {key: sum(suite[key] for suite in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
    totals['passed'] = totals['tests'] - totals['failures'] - totals['errors'] - totals['skipped']
    missing = sorted(expected - {suite['name'] for suite in suites})
    if totals['failures'] or totals['errors']:
        status = 'failed'
    elif not suites or not expected or missing or any(suite['tests'] == 0 for suite in suites):
        status = 'partial'
    elif totals['tests'] == totals['skipped']:
        status = 'not_run'
    elif totals['skipped'] or any(not suite['environment'] for suite in suites):
        status = 'partial'
    elif run_status.get('state') != 'passed' or not run_status.get('gpuRequested'):
        status = 'partial'
    elif any(not suite['timestamp'] or
             datetime.fromisoformat(suite['timestamp']).replace(tzinfo=timezone.utc).timestamp() * 1000
             < run_status['startedAtMillis'] - 2000 for suite in suites):
        status = 'partial'
    else:
        status = 'passed'
    return {'status': status, 'totals': totals, 'missingSuites': missing, 'suites': suites, 'run': run_status,
            'note': 'JUnit test counts and dataChecks are different units; dataChecks only covers instrumented loops. '
                    'Timestamps identify the recorded run, not a new execution on the current GPU.'}


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--results', type=Path, default=root / 'build/test-results/testJunit')
    parser.add_argument('--output', type=Path, help='Optional JSON report destination')
    parser.add_argument('--run-status', type=Path, help='Completion marker (default: <results-directory>-run.json beside results)')
    parser.add_argument('--expect-run', action='store_true', help='Fail unless every current GPU suite passed with environment evidence')
    args = parser.parse_args()
    marker = args.run_status or args.results.with_name(args.results.name + '-run.json')
    run_status = json.loads(marker.read_text(encoding='utf-8')) if marker.is_file() else {}
    report = summarize(args.results, root, run_status)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: value for key, value in report.items() if key != 'suites'}, indent=2))
    if report['status'] == 'failed' or (args.expect_run and report['status'] != 'passed'):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
