#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给 tools/*.ps1 补回 UTF-8 BOM。

PowerShell 5.1 在没有 BOM 时会把 UTF-8 的 .ps1 按本地代码页解析，
中文字符串会破坏语法（典型的 "MissingEndCurlyBrace"）。
"""
import pathlib

TOOLS = pathlib.Path(__file__).resolve().parent
BOM = b"\xef\xbb\xbf"


def main():
    for p in sorted(TOOLS.glob("*.ps1")):
        raw = p.read_bytes()
        if raw.startswith(BOM):
            print(f"  {p.name}: 已有 BOM")
            continue
        p.write_bytes(BOM + raw)
        print(f"  {p.name}: 已补 BOM（{len(raw)} -> {len(raw) + 3} 字节）")


if __name__ == "__main__":
    main()
