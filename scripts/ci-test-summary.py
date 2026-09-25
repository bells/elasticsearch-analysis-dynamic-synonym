#!/usr/bin/env python3
"""Add Maven Surefire totals to the GitHub Actions job summary."""
import os
from pathlib import Path
import xml.etree.ElementTree as ET

reports = sorted(Path("target/surefire-reports").glob("TEST-*.xml"))
totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
for report in reports:
    suite = ET.parse(report).getroot()
    for key in totals:
        totals[key] += int(suite.get(key, "0"))

if reports:
    passed = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
    summary = (
        "## Maven tests\n\n"
        "| Passed | Failed | Errors | Skipped | Total |\n"
        "| ---: | ---: | ---: | ---: | ---: |\n"
        f"| {passed} | {totals['failures']} | {totals['errors']} | "
        f"{totals['skipped']} | {totals['tests']} |\n"
    )
else:
    summary = "## Maven tests\n\nNo Surefire reports were produced.\n"

print(summary)
if path := os.environ.get("GITHUB_STEP_SUMMARY"):
    with open(path, "a", encoding="utf-8") as output:
        output.write(summary)
