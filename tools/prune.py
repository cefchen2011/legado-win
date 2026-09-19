#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
按 Android 耦合度裁剪 engine，只保留 PURE（纯逻辑）文件。

用途：验证"自底向上移植"是否可行——如果剥掉 Android 应用层与
Android 依赖文件后，纯逻辑核心能独立编译，就以此为基座逐层加回。

用法:
    python tools/prune.py            # 报告将要删除的文件
    python tools/prune.py --apply    # 实际删除
"""
import re
import sys
import pathlib

HERE = pathlib.Path(__file__).resolve().parent.parent
ENGINE = HERE / "engine" / "src" / "main"

ANDROID_IMPORT = re.compile(
    r"^\s*import\s+(android\.|androidx\.|com\.google\.android\.|"
    r"io\.legado\.app\.ui\.|io\.legado\.app\.lib\.)",
    re.M,
)

# 这些 androidx 注解已由 compat 兼容层覆盖，不算真正的 Android 耦合
BENIGN = re.compile(
    r"^\s*import\s+(androidx\.(room|annotation|collection)\.|android\.(text|util|os|annotation|webkit)\.|kotlinx\.parcelize)",
    re.M,
)


def classify():
    pure, android, app_layer = [], [], []
    for f in sorted(ENGINE.rglob("*")):
        if f.suffix not in (".kt", ".java"):
            continue
        rel = f.relative_to(ENGINE).as_posix()
        text = f.read_text(encoding="utf-8", errors="replace")
        imports = re.findall(r"^\s*import\s+([\w.]+)", text, re.M)
        # 去掉被 compat 覆盖的良性 import 后，看还剩哪些 Android 依赖
        real = [i for i in imports
                if (i.startswith("android.") or i.startswith("androidx.")
                    or i.startswith("com.google.android."))
                and not BENIGN.match("import " + i + "\n")]
        if real:
            android.append((rel, real))
        else:
            pure.append(rel)
    return pure, android


def main():
    apply = "--apply" in sys.argv
    pure, android = classify()
    print("PURE  : %d" % len(pure))
    print("ANDROID: %d" % len(android))
    print()
    print("=== 需要剔除的文件及其真实 Android 依赖 ===")
    for rel, deps in sorted(android, key=lambda x: -len(x[1])):
        print("%-70s %s" % (rel, ", ".join(sorted(set(deps))[:4])))
    if not apply:
        print("\n（未删除任何文件；加 --apply 执行）")
        return 0
    n = 0
    for rel, _ in android:
        (ENGINE / rel).unlink()
        n += 1
    print("\n已删除 %d 个文件，保留 %d 个纯逻辑文件" % (n, len(pure)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
