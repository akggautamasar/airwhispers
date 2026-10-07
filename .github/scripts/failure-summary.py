#!/usr/bin/env python3
"""Summarise a failed Android CI run.

Some environments cannot download the raw GitHub Actions log (Azure blob) or the
uploaded artifacts, but check-run *annotations* come back through the REST API.
This script therefore reads the Gradle logs and JUnit XML produced by the build and
emits a single ``::error::`` workflow command, which the runner turns into an
annotation that is readable everywhere.

Usage:  python3 .github/scripts/failure-summary.py     (GITHUB_WORKSPACE respected)
"""
from __future__ import annotations

import glob
import os
import re
import xml.etree.ElementTree as ET

WORKSPACE = os.environ.get("GITHUB_WORKSPACE", ".")

out: list[str] = []

# ----------------------------------------------------------------- Gradle output
ERROR_PATTERNS = (
    re.compile(r"^e: "),                     # Kotlin compiler error
    re.compile(r"^w: .*error"),              # Kotlin warning mentioning an error
    re.compile(r"\berror:"),                 # javac / aapt2 / Gradle
    re.compile(r"^> Task .* FAILED"),
    re.compile(r"^Execution failed for task"),
    re.compile(r"^FAILURE: Build failed"),
    re.compile(r"^\* What went wrong:"),
    re.compile(r"^\* Try:"),
    re.compile(r"^Caused by:"),
    re.compile(r"^\s+> "),                   # Gradle detail lines under "What went wrong"
    re.compile(r"AssertionError|ComparisonFailure"),
    re.compile(r":\s*(Error|Warning):\s"),          # Android lint text output
    re.compile(r"^Lint found \d+ error"),
    re.compile(r"^\s*\d+ errors?, \d+ warnings?"),
)

for path in sorted(glob.glob(os.path.join(WORKSPACE, "build-logs", "*.log"))):
    name = os.path.basename(path)
    text = open(path, errors="replace").read()
    lines = text.splitlines()
    picked: list[str] = []
    for line in lines:
        stripped = line.rstrip()
        if any(pattern.search(stripped) for pattern in ERROR_PATTERNS):
            picked.append(stripped.strip()[:400])
    if picked:
        out.append(f"===== {name}: errors =====")
        # de-duplicate while keeping order
        out.extend(dict.fromkeys(picked[:60]))
    tail = [line.strip() for line in lines[-30:] if line.strip()]
    if tail:
        out.append(f"===== {name}: last lines =====")
        out.extend(tail)

# ---------------------------------------------------------------- JUnit failures
for xml_path in sorted(
    glob.glob(os.path.join(WORKSPACE, "android/app/build/test-results/**/*.xml"), recursive=True)
):
    try:
        root = ET.parse(xml_path).getroot()
    except ET.ParseError:
        continue
    for case in root.iter("testcase"):
        for bad in list(case.iter("failure")) + list(case.iter("error")):
            message = (bad.get("message") or "").strip()
            out.append(f"FAILED {case.get('classname')}.{case.get('name')}: {message}"[:400])
            body = (bad.text or "").strip().splitlines()
            out.extend("    " + line.strip()[:300] for line in body[:15])

# ---------------------------------------------------------------- Android lint
for xml_path in sorted(
    glob.glob(os.path.join(WORKSPACE, "android/**/lint-results-*.xml"), recursive=True)
):
    try:
        root = ET.parse(xml_path).getroot()
    except ET.ParseError:
        continue
    for issue in root.iter("issue"):
        severity = issue.get("severity") or ""
        if severity.lower() not in ("error", "fatal"):
            continue
        location = issue.find("location")
        where = ""
        if location is not None:
            where = f"{location.get('file')}:{location.get('line')} "
        message = (issue.get("message") or "").strip()
        out.append(f"LINT {severity} [{issue.get('id')}] {where}{message}"[:400])

summary = "\n".join(out).strip() or "no build logs or test reports were found"
print(summary[:8000])

# GitHub workflow-command escaping: % -> %25 first, then CR/LF.
escaped = summary[:60000].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
os.makedirs(os.path.join(WORKSPACE, "build-logs"), exist_ok=True)
with open(os.path.join(WORKSPACE, "build-logs", "annotation.txt"), "w") as handle:
    handle.write(f"::error title=Android CI failure::{escaped}\n")
