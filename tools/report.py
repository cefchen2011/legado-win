#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
移植进度报告 / 引擎边界判定。

输出（UTF-8 文件，避免 Windows 控制台编码问题）：
  1. 编译错误按文件统计
  2. engine 中每个源文件的"Android 耦合度"分类：
       - PURE      无 android/androidx/Compose 依赖，可原样移植
       - ANDROID   直接依赖 Android API，需要 JVM 替代实现
  3. 建议：哪些文件应从 engine 中剔除（Android 应用层，桌面端不用）

用法:
    python tools/report.py build_err.txt report.txt
"""
import re
import sys
import collections
import pathlib

HERE = pathlib.Path(__file__).resolve().parent.parent
ENGINE = HERE / "engine" / "src" / "main"

ANDROID_IMPORT = re.compile(
    r"^\s*import\s+(android\.|androidx\.|com\.google\.android\.|com\.script\.|"
    r"io\.legado\.app\.ui\.|io\.legado\.app\.lib\.)",
    re.M,
)

# 桌面端不需要的 Android 应用层文件（按文件名/路径特征判定）
APP_LAYER_HINTS = (
    "Extensions.kt",          # ViewExtensions / ContextExtensions / ActivityExtensions ...
    "ToastUtils", "QRCodeUtils", "LibArchiveUtils", "FileDocExtensions", "UriExtensions",
    "AppDatabase", "App.kt", "ThemeConfig", "Restore.kt", "ReadBook", "AudioPlay",
    "VideoPlay", "LocalBook", "BookCover", "ImageLoader", "GlideImageGetter",
    "VideoPlayer", "FloatingPlayer", "ReadAloud", "TTS", "WebDav",
)


def parse_errors(path):
    files = collections.Counter()
    details = collections.defaultdict(list)
    if not pathlib.Path(path).exists():
        return files, details
    text = pathlib.Path(path).read_text(encoding="utf-8", errors="replace")
    for line in text.splitlines():
        if not line.startswith("e: "):
            continue
        m = re.match(r"e: file:///(.+?):(\d+):(\d+) (.*)", line)
        if not m:
            continue
        fpath, ln, col, msg = m.groups()
        short = fpath.split("/engine/src/main/")[-1]
        files[short] += 1
        details[short].append((int(ln), msg[:160]))
    return files, details


def classify():
    pure, android, app_layer = [], [], []
    for f in sorted(ENGINE.rglob("*")):
        if f.suffix not in (".kt", ".java"):
            continue
        rel = f.relative_to(ENGINE).as_posix()
        text = f.read_text(encoding="utf-8", errors="replace")
        if any(h in rel for h in APP_LAYER_HINTS):
            app_layer.append(rel)
            continue
        hits = ANDROID_IMPORT.findall(text)
        if hits:
            android.append((rel, len(hits)))
        else:
            pure.append(rel)
    return pure, android, app_layer


def main():
    err_path = sys.argv[1] if len(sys.argv) > 1 else "build_err.txt"
    out_path = sys.argv[2] if len(sys.argv) > 2 else "report.txt"
    err_files, details = parse_errors(err_path)
    pure, android, app_layer = classify()

    lines = []
    add = lines.append

    add("# legado -> Windows 移植进度报告")
    add("")
    add("## 1. 编译错误")
    add("")
    add("错误总数: %d" % sum(err_files.values()))
    add("")
    add("错误最多的文件（前 30）:")
    add("")
    add("| 错误数 | 文件 |")
    add("|---:|---|")
    for f, c in err_files.most_common(30):
        add("| %d | %s |" % (c, f))
    add("")

    add("## 2. engine 源文件分类")
    add("")
    add("- PURE（无 Android 依赖，可原样移植）: %d" % len(pure))
    add("- ANDROID（依赖 Android API，需 JVM 替代）: %d" % len(android))
    add("- APP_LAYER（Android 应用层，建议剔除）: %d" % len(app_layer))
    add("")

    add("### 2.1 依赖 Android 的文件（按 import 数排序）")
    add("")
    add("| Android import 数 | 文件 |")
    add("|---:|---|")
    for rel, n in sorted(android, key=lambda x: -x[1]):
        add("| %d | %s |" % (n, rel))
    add("")

    add("### 2.2 建议剔除的应用层文件")
    add("")
    for rel in app_layer:
        add("- %s" % rel)
    add("")

    add("## 3. 纯逻辑文件（移植的核心资产）")
    add("")
    for rel in pure:
        add("- %s" % rel)

    pathlib.Path(out_path).write_text("\n".join(lines), encoding="utf-8")
    print("报告已写入 %s" % out_path)
    print("PURE=%d ANDROID=%d APP_LAYER=%d 错误=%d"
          % (len(pure), len(android), len(app_layer), sum(err_files.values())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
