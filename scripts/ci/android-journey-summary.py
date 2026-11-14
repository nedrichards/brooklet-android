"""Report real instrumentation XML separately from emulator/execution failures."""

import os
from pathlib import Path
from xml.etree import ElementTree as ET

files = sorted(Path(".").glob("**/build/outputs/androidTest-results/**/TEST-*.xml"))
lines = [
    "### Installed Android journeys",
    "",
    "Emulator step: " + os.getenv("EMULATOR_OUTCOME", "unknown"),
]
count = skipped = failed = 0
for path in files:
    root = ET.parse(path).getroot()  # noqa: S314 -- JUnit XML generated locally by our own test runner.
    for case in root.iter("testcase"):
        count += 1
        skipped += case.find("skipped") is not None
        for kind in ("failure", "error"):
            if case.find(kind) is not None:
                failed += 1
                lines.append(f"- {kind}: `{case.get('classname', '')}.{case.get('name', '')}`")
if not files:
    lines.append(
        "No instrumentation XML was produced. Inspect emulator startup and command logs; no assertion outcome can be inferred."
    )
else:
    lines.append(f"{count} executed test records; {failed} failures/errors; {skipped} skipped.")
lines.append(
    "Fixture screenshots, when produced by tests, are retained with the reports. This is emulator evidence, not physical-device acceptance."
)
text = "\n".join(lines) + "\n"
print(text)
with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as output:
    output.write(text)
