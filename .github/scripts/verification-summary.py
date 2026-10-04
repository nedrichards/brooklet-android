from pathlib import Path
import os
from xml.etree import ElementTree as ET

reports = sorted(Path('.').glob('**/build/test-results/**/TEST-*.xml'))
lines = ['## Android verification', '']
tests = failures = skipped = 0
for report in reports:
    root = ET.parse(report).getroot()
    suites = [root] if root.tag == 'testsuite' else root.findall('.//testsuite')
    for suite in suites:
        tests += int(suite.get('tests', 0))
        failures += int(suite.get('failures', 0)) + int(suite.get('errors', 0))
        skipped += int(suite.get('skipped', 0))
        for case in suite.findall('testcase'):
            if case.find('failure') is not None or case.find('error') is not None:
                lines.append(f"- Failed: `{case.get('classname')}.{case.get('name')}`")
if reports:
    lines.append(f'{tests} JVM tests, {failures} failures/errors, {skipped} skipped.')
else:
    lines.append('No JVM test reports produced. Inspect the verification step and retained lint/validation reports.')
lines.extend(['', 'Test and lint reports are retained for 14 days, including on failure.',
              'Debug APKs are installable development builds. Release build APKs may be unsigned or debug-signed.'])
with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as stream:
    stream.write('\n'.join(lines) + '\n')
