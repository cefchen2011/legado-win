#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
汇总编译错误里缺失的符号，并给出"按缺失符号归并"的视角。

用途：找出最高杠杆的补齐目标——某个缺失符号被多少处引用，
就说明补上它能一次消掉多少错误。

用法:
    python -X utf8 tools/symbols.py build_err.txt
"""
import re
import sys
import collections
import pathlib

path = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "build_err.txt")
text = path.read_text(encoding="utf-8", errors="replace")

SYM = re.compile(r"Unresolved reference '([^']+)'")
sym = collections.Counter()
by_file = collections.defaultdict(collections.Counter)

for line in text.splitlines():
    if not line.startswith("e: "):
        continue
    m = SYM.search(line)
    if not m:
        continue
    s = m.group(1)
    sym[s] += 1
    fm = re.match(r"e: file:///(.+?):(\d+):", line)
    if fm:
        short = fm.group(1).split("/engine/src/main/")[-1].split("/")[-1]
        by_file[s][short] += 1

print("缺失符号种类: %d，引用总数: %d" % (len(sym), sum(sym.values())))
print()
print("=== 缺失符号（按引用次数） ===")
for s, c in sym.most_common(50):
    where = ", ".join("%s(%d)" % (f, n) for f, n in by_file[s].most_common(3))
    print("%4d  %-28s %s" % (c, s, where))
