#!/usr/bin/env python3
"""
Cross-file reference check for the Android sources.

There is no JDK in this repository's CI container for hand edits, so this script
does the part a compiler would otherwise catch first: every `receiver.member`
usage is matched against the declarations of that receiver's class. It is not a
type checker — it is a tripwire for renamed or deleted members.

    python3 tools/check-references.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "java" / "com" / "airwhispers"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def members_of(text: str) -> set[str]:
    """val/fun/var declarations in a Kotlin class body (rough but effective)."""
    names = set(re.findall(r"\b(?:override\s+)?(?:suspend\s+)?(?:val|var|fun)\s+([A-Za-z_][A-Za-z0-9_]*)", text))
    # enum entries and data-class fields also count
    names |= set(re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*[:=]", text))
    return names


RECEIVERS = {
    "viewModel": ROOT / "ui" / "AppViewModel.kt",
    "repository": ROOT / "data" / "repo" / "AirWhispersRepository.kt",
    "api": ROOT / "data" / "remote" / "ApiClient.kt",
    "local": ROOT / "data" / "local" / "LocalStore.kt",
    "settings": ROOT / "data" / "prefs" / "SettingsStore.kt",
    "secrets": ROOT / "data" / "prefs" / "SecretStore.kt",
    "realtime": ROOT / "data" / "remote" / "RealtimeClient.kt",
    "container": ROOT / "AppContainer.kt",
    "queue": ROOT / "domain" / "tts" / "TtsQueue.kt",
}

KOTLIN_KEYWORDS = {
    "let", "run", "apply", "also", "copy", "name", "toString", "equals", "hashCode",
    "ordinal", "entries", "first", "last", "value", "key", "size", "isEmpty",
    "collectAsState", "value",
}


def main() -> int:
    problems: list[str] = []
    declared: dict[str, set[str]] = {}
    for receiver, path in RECEIVERS.items():
        if not path.exists():
            problems.append(f"missing file for receiver '{receiver}': {path}")
            continue
        declared[receiver] = members_of(read(path))

    for kt in ROOT.rglob("*.kt"):
        text = read(kt)
        for receiver, known in declared.items():
            for match in re.finditer(rf"(?<![\w.]){receiver}\.([A-Za-z_][A-Za-z0-9_]*)", text):
                member = match.group(1)
                if text.rfind("\n", 0, match.start()) + 1 == text.rfind("import ", 0, match.start()):
                    continue
                if member in KOTLIN_KEYWORDS or member in known:
                    continue
                line = text[: match.start()].count("\n") + 1
                problems.append(f"{kt.relative_to(ROOT)}:{line}: {receiver}.{member} is not declared")

    # serializer()/DTO references must exist in Dto.kt
    dto_text = read(ROOT / "data" / "remote" / "Dto.kt")
    dto_names = set(re.findall(r"\bdata class ([A-Za-z0-9_]+)", dto_text)) | set(
        re.findall(r"\bobject ([A-Za-z0-9_]+)", dto_text)
    )
    for kt in ROOT.rglob("*.kt"):
        text = read(kt)
        for match in re.finditer(r"\b([A-Za-z][A-Za-z0-9_]*)\.serializer\(\)", text):
            name = match.group(1)
            if name in ("serializer",) or name in dto_names:
                continue
            line = text[: match.start()].count("\n") + 1
            problems.append(f"{kt.relative_to(ROOT)}:{line}: {name}.serializer() has no DTO")

    # deleted types must not be referenced anywhere
    banned = [
        r"\bAccount\b", r"\bContactDto\b", r"\bContactListResponse\b", r"\bContactResponse\b",
        r"\bAddContactRequest\b", r"\bUpdateContactRequest\b", r"\bSettingsDto\b", r"\bSettingsResponse\b",
        r"\bUpdateSettingsRequest\b", r"\bRegisterRequest\b", r"\bLoginRequest\b", r"\bRefreshRequest\b",
        r"\bAuthResponse\b", r"\bSessionStatus\b", r"\bSessionState\b", r"\bContactsScreen\b",
        r"\bpeerEmail\b", r"\bpeerTrusted\b", r"\brefreshToken\b",
    ]
    for kt in ROOT.rglob("*.kt"):
        text = read(kt)
        for pattern in banned:
            for match in re.finditer(pattern, text):
                line = text[: match.start()].count("\n") + 1
                problems.append(f"{kt.relative_to(ROOT)}:{line}: removed symbol {match.group(0)} still used")

    if problems:
        print("\n".join(sorted(set(problems))))
        print(f"\n{len(set(problems))} problem(s)")
        return 1
    print("reference check: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
