#!/usr/bin/env python3
"""Generate src/dev/yesplaymusic/probe/OpenCCFoldTable.java from OpenCC dictionaries.

Upstream source (Apache License 2.0, full text in docs/licenses/OpenCC-Apache-2.0.txt):
  https://github.com/BYVoid/OpenCC (commit fae2a0a911c30764e669a7d8b0591cd8e852934a)
  - data/dictionary/TSCharacters.txt
    SHA-256: 9ff46a7d30e5765375eb13d33f2b03a34d298913caf2b120380679f33ae1642d
  - data/dictionary/JPShinjitaiCharacters.txt
    SHA-256: 12cec7250b873ef52b36d8f92218d4f92c0aaf5d8cd7c58fe42d9785bdcdc43a

Usage:
  python scripts/generate_opencc_table.py --opencc-dir /path/to/opencc/data/dictionary
  python scripts/generate_opencc_table.py --opencc-dir /path/to/opencc/data/dictionary --check
"""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "src" / "dev" / "yesplaymusic" / "probe" / "OpenCCFoldTable.java"
CHUNK_SIZE = 80


def parse_dictionary(path: Path) -> dict[str, list[str]]:
    entries: dict[str, list[str]] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t", 1)
        if len(parts) != 2:
            continue
        key = parts[0]
        targets = [t for t in parts[1].split(" ") if t]
        if len(key) == 1 and ord(key) <= 0xFFFF and targets:
            entries[key] = targets
    return entries


def is_gb2312(ch: str) -> bool:
    try:
        ch.encode("gb2312")
        return True
    except UnicodeEncodeError:
        return False


def build_fold_map(ts_path: Path, jp_path: Path) -> tuple[dict[str, str], int, int]:
    ts_raw = parse_dictionary(ts_path)
    jp_raw = parse_dictionary(jp_path)

    # 1. Traditional Chinese -> Simplified Chinese (OpenCC TSCharacters.txt first candidate).
    # Resolve TSCharacters transitive chains (e.g. 薴 -> 苧 -> 苎) before merging JPShinjitai
    # so TS canonical outputs are preserved intact and never rewritten by Japanese entries.
    ts_map: dict[str, str] = {}
    for key, targets in ts_raw.items():
        first = targets[0]
        if len(first) == 1 and ord(first) <= 0xFFFF and first != key:
            ts_map[key] = first
    for _ in range(8):
        changed = False
        for key in list(ts_map.keys()):
            nxt = ts_map.get(ts_map[key])
            if nxt is not None and nxt != ts_map[key]:
                ts_map[key] = nxt
                changed = True
        if not changed:
            break
    ts_map = {k: v for k, v in ts_map.items() if k != v}
    ts_targets = set(ts_map.values())

    fold = dict(ts_map)
    ts_count = len(ts_map)

    # 2. Japanese Shinjitai <-> Kyujitai -> Simplified Chinese (OpenCC JPShinjitaiCharacters.txt).
    # General safety invariants (zero character-specific exceptions):
    # - Never overwrite a key that is already in TSCharacters or is a canonical TS target
    #   (protects 滬->沪 and 蕓->芸 from being rewritten to 滤/艺).
    # - Never add a JP edge that merges two distinct GB2312 standard Chinese characters
    #   (protects pairs like 予/豫, 欠/缺, 缶/罐, 沪/滤, 芸/艺, 弁/辩, 糸/丝, 浜/滨).
    # - When neither form is in TSCharacters (e.g. 黒/黑 or 翻/飜), orient toward the GB2312
    #   standard Chinese form so simplified Chinese characters stay intact.
    jp_added = 0
    for key, targets in jp_raw.items():
        if key in fold or key in ts_targets:
            continue
        bmp_targets = [t for t in targets if len(t) == 1 and ord(t) <= 0xFFFF]
        if not bmp_targets:
            continue
        chosen = next((ts_map[t] for t in bmp_targets if t in ts_map), None)
        if chosen is not None and chosen != key:
            if is_gb2312(key) and is_gb2312(chosen):
                continue
            fold[key] = chosen
            jp_added += 1
            continue
        other = next((t for t in bmp_targets if t != key), None)
        if not other:
            continue
        if is_gb2312(key) and is_gb2312(other):
            continue
        if is_gb2312(key) and not is_gb2312(other):
            if other not in fold and other not in ts_targets:
                fold[other] = key
                jp_added += 1
        else:
            fold[key] = other
            jp_added += 1

    # 3. Verify strict idempotency and TS target preservation.
    fold = {k: v for k, v in fold.items() if k != v}
    for t in ts_targets:
        if t in fold:
            raise RuntimeError(f"TSCharacters target overwritten: {t!r} -> {fold[t]!r}")
    for k, v in fold.items():
        if v in fold:
            raise RuntimeError(f"Non-idempotent mapping detected: {k!r} -> {v!r} -> {fold[v]!r}")
    return fold, ts_count, jp_added


def java_escape_char(ch: str) -> str:
    code = ord(ch)
    if ch == "\\" or ch == '"' or code < 0x20 or code >= 0x7F and (0xD800 <= code <= 0xDFFF):
        return f"\\u{code:04x}"
    return ch


def format_java_string_chunks(chars: list[str], indent: str) -> str:
    lines: list[str] = []
    for start in range(0, len(chars), CHUNK_SIZE):
        chunk = "".join(java_escape_char(c) for c in chars[start : start + CHUNK_SIZE])
        suffix = " +" if start + CHUNK_SIZE < len(chars) else ""
        lines.append(f'{indent}"{chunk}"{suffix}')
    return "\n".join(lines)


def render_java(fold: dict[str, str], ts_count: int, jp_added: int) -> str:
    keys = sorted(fold.keys(), key=ord)
    values = [fold[k] for k in keys]
    min_cp = ord(keys[0])
    max_cp = ord(keys[-1])
    from_chunks = format_java_string_chunks(keys, "            ")
    to_chunks = format_java_string_chunks(values, "            ")

    return f"""package dev.yesplaymusic.probe;

import java.util.Arrays;

/**
 * Generated Traditional Chinese and Japanese Shinjitai single-character fold table.
 *
 * <p>Derived from OpenCC (https://github.com/BYVoid/OpenCC, Apache License 2.0):
 * TSCharacters.txt ({ts_count} entries) + JPShinjitaiCharacters.txt ({jp_added} added entries)
 * -> {len(keys)} BMP 1-to-1 idempotent mappings.
 *
 * <p>Regenerate with:
 * {{@code python scripts/generate_opencc_table.py --opencc-dir <opencc-dictionary-dir>}}
 */
final class OpenCCFoldTable {{
    private static final char MIN_CHAR = '\\u{min_cp:04x}';
    private static final char MAX_CHAR = '\\u{max_cp:04x}';

    private static final char[] FROM = (
{from_chunks}
    ).toCharArray();

    private static final char[] TO = (
{to_chunks}
    ).toCharArray();

    private OpenCCFoldTable() {{}}

    static char foldChar(char c) {{
        if (c < MIN_CHAR || c > MAX_CHAR) return c;
        int idx = Arrays.binarySearch(FROM, c);
        return idx < 0 ? c : TO[idx];
    }}

    static String fold(String text) {{
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {{
            char c = text.charAt(i);
            char folded = foldChar(c);
            if (folded != c) {{
                if (out == null) {{
                    out = new StringBuilder(text.length());
                    out.append(text, 0, i);
                }}
                out.append(folded);
            }} else if (out != null) {{
                out.append(c);
            }}
        }}
        return out == null ? text : out.toString();
    }}

    static int size() {{
        return FROM.length;
    }}

    static char keyAt(int index) {{
        return FROM[index];
    }}

    static char valueAt(int index) {{
        return TO[index];
    }}
}}
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--opencc-dir", type=Path, required=True,
                        help="Directory containing TSCharacters.txt and JPShinjitaiCharacters.txt")
    parser.add_argument("--output", type=Path, default=OUTPUT, help="Output Java file path")
    parser.add_argument("--check", action="store_true", help="Verify output is up-to-date without writing")
    args = parser.parse_args()

    ts_path = args.opencc_dir / "TSCharacters.txt"
    jp_path = args.opencc_dir / "JPShinjitaiCharacters.txt"
    if not ts_path.is_file() or not jp_path.is_file():
        print(f"error: expected TSCharacters.txt and JPShinjitaiCharacters.txt in {args.opencc_dir}", file=sys.stderr)
        return 2

    fold, ts_count, jp_added = build_fold_map(ts_path, jp_path)
    rendered = render_java(fold, ts_count, jp_added)

    if args.check:
        if not args.output.is_file():
            print(f"error: missing generated file {args.output}", file=sys.stderr)
            return 1
        current = args.output.read_text(encoding="utf-8").replace("\r\n", "\n")
        if current != rendered:
            print(f"error: {args.output} is out of date; re-run generate_opencc_table.py", file=sys.stderr)
            return 1
        print(f"OpenCCFoldTable.java is up to date ({len(fold)} mappings)")
        return 0

    args.output.write_text(rendered, encoding="utf-8", newline="\n")
    print(f"Generated {args.output} ({len(fold)} mappings: {ts_count} TS + {jp_added} JP)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
