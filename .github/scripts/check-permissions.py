#!/usr/bin/env python3
"""Keep declared, documented and shipped permissions in agreement.

`docs/security.md` makes a privacy promise that a user can check: nine
permissions, each with a stated reason, plus a list that is *deliberately
absent* (`RECORD_AUDIO`, `READ_CALL_LOG`, `MODIFY_AUDIO_SETTINGS`, …). A promise
like that only holds if something fails when it drifts, so this script compares
three sources:

  1. ``android/app/src/main/AndroidManifest.xml`` — what the app declares;
  2. ``docs/security.md``                          — what the docs promise;
  3. the built release APK via ``aapt2 dump badging`` — what a user actually
     installs, i.e. our declarations *plus* anything a library merges in.

Two modes:

  * source mode (default) — manifest ⇄ docs must be set-equal. A permission
    declared but not documented, or documented but not declared, fails.
  * APK mode (``--apk path``) — additionally forbids every "deliberately absent"
    permission from appearing in the shipped APK and reports the permissions
    that only came from libraries. Those extras are printed as an annotation,
    because CI logs are not always reachable from every environment.

Usage:
    python3 .github/scripts/check-permissions.py
    python3 .github/scripts/check-permissions.py --apk artifacts/app-standalone-release.apk
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys

WORKSPACE = os.environ.get("GITHUB_WORKSPACE", ".")
MANIFEST = os.path.join(WORKSPACE, "android", "app", "src", "main", "AndroidManifest.xml")
SECURITY_DOC = os.path.join(WORKSPACE, "docs", "security.md")

PERMISSION_PREFIX = "android.permission."


def declared_permissions(manifest_path: str) -> set[str]:
    """Permissions the app's own manifest declares."""
    with open(manifest_path, encoding="utf-8") as handle:
        text = handle.read()
    names = re.findall(r'<uses-permission[^>]*android:name="([^"]+)"', text)
    return {name[len(PERMISSION_PREFIX):] if name.startswith(PERMISSION_PREFIX) else name for name in names}


def documented_permissions(doc_path: str) -> tuple[set[str], set[str]]:
    """(documented-as-present, documented-as-absent) permission names."""
    with open(doc_path, encoding="utf-8") as handle:
        text = handle.read()

    section_start = text.find("## The app's permissions")
    if section_start == -1:
        raise SystemExit("::error::docs/security.md has no '## The app's permissions' section")
    rest = text[section_start + 1:]
    section_end = rest.find("\n## ")
    section = rest if section_end == -1 else rest[:section_end]

    present: set[str] = set()
    for line in section.splitlines():
        if not line.startswith("|"):
            continue
        cells = line.split("|")
        if len(cells) < 2:  # noqa: PLR2004 - a table row needs a first cell
            continue
        first_cell = cells[1]
        present.update(
            token for token in re.findall(r"`([A-Z][A-Z0-9_]{2,})`", first_cell)
        )

    absent: set[str] = set()
    absent_match = re.search(r"Deliberately \*\*absent\*\*:(.*?)(?:\n\n|\Z)", section, re.S)
    if not absent_match:
        raise SystemExit("::error::docs/security.md no longer lists deliberately absent permissions")
    absent.update(re.findall(r"`([A-Z][A-Z0-9_]{2,})`", absent_match.group(1)))

    return present, absent


def find_aapt2() -> str | None:
    """Newest installed aapt2, the same one the release-verification step uses."""
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    root = os.path.join(home, "build-tools")
    if not os.path.isdir(root):
        return None
    candidates: list[tuple[tuple[int, ...], str]] = []
    for version in os.listdir(root):
        numeric = tuple(int(part) for part in re.findall(r"\d+", version))
        candidates.append((numeric, os.path.join(root, version, "aapt2")))
    for _, path in sorted(candidates, reverse=True):
        if os.access(path, os.X_OK):
            return path
    return None


def apk_permissions(apk_path: str) -> set[str]:
    """Permissions in the built APK, including library-merged ones."""
    aapt2 = find_aapt2()
    if aapt2 is None:
        print("::warning::aapt2 not found — the shipped permissions were not verified")
        return set()
    badging = subprocess.run(  # noqa: S603 - fixed argv, no shell
        [aapt2, "dump", "badging", apk_path],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    names = re.findall(r"uses-permission: name='([^']+)'", badging)
    return {name[len(PERMISSION_PREFIX):] if name.startswith(PERMISSION_PREFIX) else name for name in names}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", help="built APK to inspect as well")
    parser.add_argument("--manifest", default=MANIFEST)
    parser.add_argument("--doc", default=SECURITY_DOC)
    args = parser.parse_args()

    declared = declared_permissions(args.manifest)
    documented, absent = documented_permissions(args.doc)

    problems: list[str] = []
    undeclared_in_docs = declared - documented
    undocumented_in_manifest = documented - declared
    if undeclared_in_docs:
        problems.append(
            "declared in the manifest but missing from docs/security.md: "
            + ", ".join(sorted(undeclared_in_docs))
        )
    if undocumented_in_manifest:
        problems.append(
            "documented in docs/security.md but not declared in the manifest: "
            + ", ".join(sorted(undocumented_in_manifest))
        )
    leaking = declared & absent
    if leaking:
        problems.append("permission listed as deliberately absent yet declared: " + ", ".join(sorted(leaking)))

    print(f"declared and documented ({len(declared)}): {', '.join(sorted(declared))}")
    print(f"deliberately absent and must stay so: {', '.join(sorted(absent))}")

    with open(args.manifest, encoding="utf-8") as handle:
        manifest_text = handle.read()
    if "BIND_ACCESSIBILITY_SERVICE" in manifest_text:
        problems.append("the manifest declares an accessibility service, which the docs promise it never will")

    inspected_apk = False
    if args.apk:
        shipped = apk_permissions(args.apk)
        inspected_apk = bool(shipped)
        if shipped:
            merged_only = shipped - declared
            print(f"shipped in {os.path.basename(args.apk)} ({len(shipped)}): {', '.join(sorted(shipped))}")
            leaked_shipped = shipped & absent
            if leaked_shipped:
                problems.append(
                    "a deliberately absent permission reached the shipped APK: "
                    + ", ".join(sorted(leaked_shipped))
                )
            if merged_only:
                # An annotation, not just stdout: CI logs are not reachable from
                # every environment, but annotations are.
                print(
                    "::notice::permissions merged in from libraries (not declared by the app): "
                    + ", ".join(sorted(merged_only))
                )
            else:
                print("::notice::the shipped APK declares exactly the app's own permissions")

    if problems:
        for problem in problems:
            print(f"::error::{problem}")
        return 1
    where = "manifest, docs/security.md and the shipped APK" if inspected_apk else "manifest and docs/security.md"
    print(f"::notice::permission set is consistent: {where} agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
