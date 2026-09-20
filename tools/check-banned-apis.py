#!/usr/bin/env python3
"""Fail if the library's sources use an API that does not exist at minSdk 24.

Lint's NewApi check covers call sites, but it can be suppressed and it only runs on
configurations lint knows about. This is the blunt second gate: the types below simply must
not appear in `qualflare-espresso/src/main` at all.

    java.nio.file / java.time / java.util.Base64      API 26
    List.of / Map.of / Set.of / requireNonNullElse    API 30
    String.join                                       API 26
    ProcessHandle                                     absent from Android at every level

Comments are stripped before matching. The first version of this check grepped raw text and
flagged `Attachments.java`'s javadoc — a comment that exists precisely to warn the next reader
off `java.util.Base64`. A guard that fires on its own documentation trains people to ignore it.
"""

import pathlib
import re
import sys

BANNED = {
    "java.nio.file": "API 26+ — use java.io.File and streams",
    "java.time": "API 26+ — use SimpleDateFormat with a UTC TimeZone",
    "java.util.Base64": "API 26+ — use android.util.Base64 with NO_WRAP",
    "String.join": "API 26+ — join by hand with a StringBuilder",
    "List.of": "API 30+ — use Arrays.asList or a new ArrayList",
    "Map.of": "API 30+ — use a LinkedHashMap",
    "Set.of": "API 30+ — use a LinkedHashSet",
    "Objects.requireNonNullElse": "API 30+ — write the null check",
    "ProcessHandle": "absent from Android at every API level — use android.os.Process.myPid()",
}

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")
STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\])*"')


def strip_noise(source: str) -> str:
    """Remove comments and string literals, keeping line numbers intact."""
    def blank(match: re.Match) -> str:
        # Preserve newlines so reported line numbers still point at the real line.
        return re.sub(r"[^\n]", " ", match.group(0))

    without_blocks = BLOCK_COMMENT.sub(blank, source)
    without_lines = LINE_COMMENT.sub(blank, without_blocks)
    return STRING_LITERAL.sub(blank, without_lines)


def main() -> int:
    root = pathlib.Path(__file__).resolve().parent.parent
    src = root / "qualflare-espresso" / "src" / "main"
    files = sorted(src.rglob("*.java"))
    if not files:
        print(f"no java sources under {src}", file=sys.stderr)
        return 1

    failures = []
    for path in files:
        code = strip_noise(path.read_text(encoding="utf-8"))
        for lineno, line in enumerate(code.splitlines(), start=1):
            for needle, why in BANNED.items():
                if needle in line:
                    rel = path.relative_to(root)
                    failures.append(f"{rel}:{lineno}: {needle} — {why}")

    if failures:
        print("These are not available at minSdk 24:", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        return 1

    print(f"checked {len(files)} file(s): no API 26+ types in the library's sources")
    return 0


if __name__ == "__main__":
    sys.exit(main())
