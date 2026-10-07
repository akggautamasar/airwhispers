#!/usr/bin/env python3
"""Keep every translation complete and safe to format.

The app ships English and Hindi, and Android falls back *silently*: a string
missing from `values-hi/` just appears in English, which is invisible in review
and invisible in tests. A translated string that drops or renames a format
argument is worse than that — `getString(R.string.x, count)` throws, or prints
the wrong number, only on the device that set the other language.

So this script checks, for every locale directory next to `values/`:

  * the key sets are identical (no string is left untranslated, and no
    translation refers to a key English does not have);
  * each translation carries exactly the same format specifiers as its English
    original (`%1$s`, `%2$d`, …), so a translated string cannot lose an
    argument;
  * no key or translation is empty, and every key is declared once.

Usage:  python3 .github/scripts/check-translations.py
"""

from __future__ import annotations

import os
import re
import sys
from xml.etree import ElementTree

WORKSPACE = os.environ.get("GITHUB_WORKSPACE", ".")
RES = os.path.join(WORKSPACE, "android", "app", "src", "main", "res")
DEFAULT_LOCALE = "values"

STRING_TAG = "string"
SPECIFIER = re.compile(r"%(?:\d+\$)?[a-zA-Z]")


def read_strings(path: str) -> tuple[dict[str, str], list[str]]:
    """Returns (name → text, problems) for one strings.xml."""
    problems: list[str] = []
    try:
        root = ElementTree.parse(path).getroot()
    except (OSError, ElementTree.ParseError) as error:
        return {}, [f"{path} could not be read: {error}"]

    strings: dict[str, str] = {}
    for element in root:
        if element.tag != STRING_TAG:
            continue  # plurals and string-arrays are checked by the same rules later if added
        name = element.get("name")
        if not name:
            problems.append(f"{path}: a <string> has no name")
            continue
        if name in strings:
            problems.append(f"{path}: '{name}' is declared more than once")
            continue
        text = "".join(element.itertext()).strip()
        if not text:
            problems.append(f"{path}: '{name}' is empty")
        strings[name] = text
    return strings, problems


def specifiers(text: str) -> set[str]:
    return set(SPECIFIER.findall(text))


def main() -> int:
    default_dir = os.path.join(RES, DEFAULT_LOCALE)
    if not os.path.isdir(default_dir):
        print(f"::error::no {DEFAULT_LOCALE}/ directory under {RES}")
        return 1

    english, problems = read_strings(os.path.join(default_dir, "strings.xml"))
    if not english:
        print("::error::values/strings.xml declares no strings")
        return 1
    print(f"default locale: {len(english)} strings")

    locales = sorted(
        entry for entry in os.listdir(RES)
        if entry.startswith("values-") and os.path.isfile(os.path.join(RES, entry, "strings.xml"))
    )
    if not locales:
        print("::warning::no translated locale directories found — nothing to compare")

    for locale in locales:
        path = os.path.join(RES, locale, "strings.xml")
        translated, locale_problems = read_strings(path)
        problems.extend(locale_problems)
        print(f"{locale}: {len(translated)} strings")

        missing = sorted(set(english) - set(translated))
        if missing:
            problems.append(
                f"{locale} is missing {len(missing)} string(s) that exist in {DEFAULT_LOCALE}: "
                + ", ".join(missing[:10])
                + (" …" if len(missing) > 10 else "")
            )

        unknown = sorted(set(translated) - set(english))
        if unknown:
            problems.append(
                f"{locale} translates {len(unknown)} key(s) that do not exist in {DEFAULT_LOCALE}: "
                + ", ".join(unknown[:10])
                + (" …" if len(unknown) > 10 else "")
            )

        for name in sorted(set(english) & set(translated)):
            expected, actual = specifiers(english[name]), specifiers(translated[name])
            if expected != actual:
                problems.append(
                    f"{locale} '{name}': format specifiers differ "
                    f"(default {sorted(expected) or ['none']} vs {sorted(actual) or ['none']})"
                )

    if problems:
        for problem in problems:
            print(f"::error::{problem}")
        return 1
    print("::notice::every locale is complete and safe to format")
    return 0


if __name__ == "__main__":
    sys.exit(main())
