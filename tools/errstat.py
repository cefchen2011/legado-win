#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""统计 Kotlin 编译错误，按文件与错误类型分组，便于分批修复。"""
import re
import sys
import collections
import pathlib

path = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "build_err.txt")
text = path.read_text(encoding="utf-8", errors="replace")
errs = [l for l in text.splitlines() if l.startswith("e: ")]

print("错误总数:", len(errs))

files = collections.Counter()
msgs = collections.Counter()
details = collections.defaultdict(list)

for line in errs:
    # 注意：Windows 路径形如 file:///C:/workspace/... 含冒号，
    # 因此不能用 [^:]+ 匹配路径，必须非贪婪匹配到 ":行号:列号"
    m = re.match(r"e: file:///(.+?):(\d+):(\d+) (.*)", line)
    if not m:
        continue
    fpath, ln, col, msg = m.groups()
    short = fpath.split("/engine/src/main/")[-1]
    files[short] += 1
    norm = re.sub(r"'[^']*'", "X", msg)[:90]
    msgs[norm] += 1
    details[short].append((int(ln), msg[:150]))

print()
print("=== 错误最多的文件 ===")
for f, c in files.most_common(25):
    print("%4d  %s" % (c, f))

print()
print("=== 错误类型 ===")
for m, c in msgs.most_common(20):
    print("%4d  %s" % (c, m))

if len(sys.argv) > 2 and sys.argv[2] in files:
    print()
    print("=== %s 的错误明细 ===" % sys.argv[2])
    for ln, msg in sorted(details[sys.argv[2]])[:60]:
        print("%5d  %s" % (ln, msg))
