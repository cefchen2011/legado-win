#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从上游 legado-plus 源码树向桌面移植工程同步源文件。

用法:
    python tools/port_sync.py <上游相对路径> [<上游相对路径> ...]

路径可以是目录或单个文件，例如:
    app/src/main/java/io/legado/app/model/analyzeRule
    app/src/main/java/io/legado/app/utils/GSON.kt
    modules/rhino/src/main/java/com/script

源文件被复制到 engine/src/main/kotlin 下并**保持原有包路径**，
因此上游代码可以不加修改地编译。已被本地修改过的文件不会被覆盖，
而是打印出来提醒（用 --force 强制覆盖）。
"""
import sys
import shutil
import pathlib

HERE = pathlib.Path(__file__).resolve().parent.parent
UPSTREAM = pathlib.Path(r"C:\workspace\legado-plus")
ENGINE_KT = HERE / "engine" / "src" / "main" / "kotlin"
ENGINE_JAVA = HERE / "engine" / "src" / "main" / "java"

UPSTREAM_ROOTS = [
    "app/src/main/java",
    "modules/rhino/src/main/java",
    "modules/book/src/main/java",
]

# 上游已有本地改动（补丁）的文件，避免被同步覆盖
PATCHED = set()


def resolve(rel: str):
    """把上游相对路径解析为 (源路径, 目标根目录)。"""
    for root in UPSTREAM_ROOTS:
        if rel.startswith(root + "/"):
            src = UPSTREAM / rel
            pkg = rel[len(root) + 1:]
            return src, None
    return None, None


def target_for(rel: str, path: pathlib.Path) -> pathlib.Path:
    for root in UPSTREAM_ROOTS:
        marker = root + "/"
        if rel.startswith(marker):
            pkg = rel[len(marker):]
            base = ENGINE_JAVA if path.suffix == ".java" else ENGINE_KT
            return base / pkg
    raise ValueError(f"无法定位上游根目录: {rel}")


def do_copy(rel: str, force: bool, verbose: bool):
    src = UPSTREAM / rel
    if not src.exists():
        print(f"[缺失] {rel}")
        return 0
    files = [src] if src.is_file() else [p for p in src.rglob("*") if p.is_file()]
    n = 0
    for f in files:
        if f.suffix not in (".kt", ".java"):
            continue
        sub = f.relative_to(UPSTREAM).as_posix()
        dst = target_for(sub, f)
        if dst.exists() and not force:
            local = dst.read_text(encoding="utf-8", errors="replace")
            upstream = f.read_text(encoding="utf-8", errors="replace")
            if local != upstream:
                print(f"[已改动-跳过] {sub}")
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(f, dst)
        n += 1
        if verbose:
            print(f"[复制] {sub}")
    return n


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    force = "--force" in sys.argv
    verbose = "--verbose" in sys.argv or "-v" in sys.argv
    if not args:
        print(__doc__)
        return 1
    total = 0
    for rel in args:
        total += do_copy(rel.replace("\\", "/"), force, verbose)
    print(f"同步完成：新增/覆盖 {total} 个文件 -> {ENGINE_KT.parent}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
