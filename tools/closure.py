#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
自动补齐移植依赖闭包（受控版）。

要点：
  1. 排除 Android 专属包（UI / Service / Receiver / ContentProvider 等），
     这些由 Windows 端自己实现，不参与移植闭包；
  2. 符号解析不再"找不到就整包拷贝"，而是精确定位声明该符号的文件；
  3. 无法定位的符号会被记录为"待桌面端实现"，而不是盲目扩大闭包；
  4. 判断"文件是否已存在"用 (package, 文件名)，而不是绝对路径——
     绝对路径在 engine 与 upstream 之间永远不相等，会导致重复拷贝。

用法:
    python tools/closure.py --dry     # 只报告需要哪些文件
    python tools/closure.py           # 实际同步
"""
import re
import sys
import shutil
import pathlib
from collections import defaultdict

HERE = pathlib.Path(__file__).resolve().parent.parent
UPSTREAM = pathlib.Path(r"C:\workspace\legado-plus")
ENGINE_SRC = HERE / "engine" / "src"
# 上游原样源码的落点（同步目标）
ENGINE = ENGINE_SRC / "main"
# 判定"符号是否已存在"时必须**同时扫描桌面实现目录**，
# 否则会把已被 src/desktop 取代的上游文件重新拉回来。
SCAN_ROOTS = [ENGINE_SRC / "main", ENGINE_SRC / "desktop"]


def engine_files():
    for root in SCAN_ROOTS:
        if not root.exists():
            continue
        for f in root.rglob("*"):
            if f.suffix in (".kt", ".java"):
                yield f

UPSTREAM_ROOTS = [
    "app/src/main/java",
    "modules/rhino/src/main/java",
    "modules/book/src/main/java",
]

SELF_PREFIXES = ("io.legado.", "com.script.", "me.ag2s.")

# Android 专属包：由桌面端重新实现，不进入移植闭包
#
# 说明：data/entities 里大量出现 androidx.room / Parcelable 注解，
# 那些由 compat 兼容层覆盖，不算真正的 Android 耦合，因此不排除。
# 这里排除的是**必然需要桌面端另写实现**的 Android 应用层与原生依赖包。
EXCLUDE_PREFIXES = (
    # 界面 / 框架
    "io.legado.app.ui",
    "io.legado.app.lib",
    "io.legado.app.receiver",
    "io.legado.app.service",
    "io.legado.app.api",
    "io.legado.app.web",
    "io.legado.app.base",
    # 数据访问层（Room DAO -> 桌面端用 SQLite 自行实现）
    "io.legado.app.data.dao",
    # 依赖 Android 原生/多媒体/图片加载的子系统
    "io.legado.app.help.exoplayer",
    "io.legado.app.help.gsyVideo",
    "io.legado.app.help.glide",
    "io.legado.app.help.webView",
    "io.legado.app.help.storage",
    "io.legado.app.help.update",
    "io.legado.app.help.crypto",
    "io.legado.app.utils.canvasrecorder",
    "io.legado.app.utils.compress",
    "io.legado.app.model.localBook",
    "io.legado.app.model.webBook",
    # 单个 Android 专属文件
    "io.legado.app.App",
    "io.legado.app.help.AppWebDav",
    "io.legado.app.help.AppFreezeMonitor",
    "io.legado.app.help.CrashHandler",
    "io.legado.app.help.LifecycleHelp",
    "io.legado.app.help.IntentHelp",
    "io.legado.app.help.IntentData",
    "io.legado.app.help.TTS",
    "io.legado.app.help.MediaHelp",
    "io.legado.app.help.GlideImageGetter",
    "io.legado.app.help.LauncherIconHelp",
    "io.legado.app.help.DirectLinkUpload",
    "io.legado.app.help.DefaultData",
    "io.legado.app.help.EventMessage",
    "io.legado.app.help.DispatchersMonitor",
    "io.legado.app.help.TextViewTagHandler",
    "io.legado.app.utils.RealPathUtil",
    "io.legado.app.utils.BitmapUtils",
    "io.legado.app.utils.SvgUtils",
    "io.legado.app.utils.ColorUtils",
    "io.legado.app.utils.ACache",
    "io.legado.app.utils.SystemUtils",
    "io.legado.app.utils.ParcelFileDescriptorChannel",
    "io.legado.app.utils.DocumentUtils",
    "io.legado.app.utils.HandlerUtils",
    "io.legado.app.utils.ConflateLiveData",
    "io.legado.app.utils.ViewExtensions",
    "io.legado.app.utils.ContextExtensions",
    "io.legado.app.utils.ActivityExtensions",
    "io.legado.app.utils.FragmentExtensions",
    "io.legado.app.utils.MenuExtensions",
    "io.legado.app.utils.ToastUtils",
    "io.legado.app.utils.QRCodeUtils",
    "io.legado.app.utils.FileDocExtensions",
    "io.legado.app.utils.UriExtensions",
    "io.legado.app.model.AudioPlay",
    "io.legado.app.model.VideoPlay",
    "io.legado.app.model.ReadManga",
    "io.legado.app.model.ReadBook",
    "io.legado.app.model.BookCover",
    "io.legado.app.model.Debug",
    "io.legado.app.model.rss",
    "io.legado.app.utils.HandlerUtils",
    # 以下三个上游文件已由 engine/src/desktop/kotlin 下的桌面实现取代，
    # 不要再从上游同步，否则会与桌面实现重名冲突。
    "io.legado.app.utils.NetworkUtils",
    "io.legado.app.utils.LogUtils",
    "io.legado.app.utils.ToastUtils",
)

IMPORT_RE = re.compile(r"^\s*import\s+([\w.]+)(\.\*)?\s*$", re.M)
PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)\s*$", re.M)


def decl_re(symbol: str) -> re.Pattern:
    return re.compile(
        r"(?:^|\s)(?:@\w+\s+)*(?:public\s+|internal\s+|private\s+|open\s+|abstract\s+|sealed\s+|"
        r"data\s+|value\s+|annotation\s+|inline\s+|suspend\s+|external\s+|override\s+)*"
        r"(?:fun|val|var|class|object|interface|typealias|enum\s+class)\s+"
        + re.escape(symbol) + r"\b",
        re.M,
    )


PKG_INDEX = defaultdict(list)   # package -> [path]
FILE_TEXT = {}                  # path -> 全文


def build_index():
    for root in UPSTREAM_ROOTS:
        base = UPSTREAM / root
        if not base.exists():
            continue
        for f in base.rglob("*"):
            if f.suffix not in (".kt", ".java"):
                continue
            text = f.read_text(encoding="utf-8", errors="replace")
            FILE_TEXT[f] = text
            m = PACKAGE_RE.search(text[:4000])
            if m:
                PKG_INDEX[m.group(1)].append(f)


def excluded(pkg: str) -> bool:
    return pkg.startswith(EXCLUDE_PREFIXES)


def dest_for(f: pathlib.Path) -> pathlib.Path:
    rel = f.relative_to(UPSTREAM).as_posix()
    for root in UPSTREAM_ROOTS:
        marker = root + "/"
        if rel.startswith(marker):
            pkg = rel[len(marker):]
            base = ENGINE / ("java" if f.suffix == ".java" else "kotlin")
            return base / pkg
    raise ValueError(rel)


def scan_engine():
    """返回 (package -> 词干集合, (package, 词干) 集合)。"""
    pkgs = defaultdict(set)
    have = set()
    for f in engine_files():
        text = f.read_text(encoding="utf-8", errors="replace")
        m = PACKAGE_RE.search(text[:4000])
        if m:
            pkgs[m.group(1)].add(f.stem)
            have.add((m.group(1), f.stem))
    return pkgs, have


def present(f: pathlib.Path, have) -> bool:
    """上游文件 f 是否已存在于 engine（按 package + 文件名判断）。"""
    m = PACKAGE_RE.search(FILE_TEXT.get(f, "")[:4000])
    if not m:
        return False
    return (m.group(1), f.stem) in have


def engine_imports():
    imports = set()
    for f in engine_files():
        for m in IMPORT_RE.finditer(f.read_text(encoding="utf-8", errors="replace")):
            fq = m.group(1)
            if fq.startswith(SELF_PREFIXES):
                imports.add((fq, m.group(2) is not None))
    return imports


def resolve(import_fq, wildcard, pkgs, have, unresolved):
    need = set()
    if wildcard:
        pkg = import_fq
        if excluded(pkg):
            unresolved.add(import_fq + ".*  (整包被排除，需桌面端实现)")
            return need
        for f in PKG_INDEX.get(pkg, []):
            if not present(f, have):
                need.add(f)
        return need

    pkg, _, symbol = import_fq.rpartition(".")
    if not pkg:
        return need
    # 既排除整包，也排除单个文件（如 io.legado.app.constant.AppConst）
    if excluded(pkg) or excluded(import_fq):
        unresolved.add(import_fq + "  (包被排除，需桌面端实现)")
        return need
    if symbol in pkgs.get(pkg, set()):
        return need
    direct = [f for f in PKG_INDEX.get(pkg, []) if f.stem == symbol]
    if direct:
        for f in direct:
            if not present(f, have):
                need.add(f)
        return need
    pat = decl_re(symbol)
    hits = [f for f in PKG_INDEX.get(pkg, []) if pat.search(FILE_TEXT.get(f, ""))]
    if not hits:
        hits = [f for f in PKG_INDEX.get(pkg, [])
                if re.search(r"\b" + re.escape(symbol) + r"\b", FILE_TEXT.get(f, ""))]
    if hits:
        for f in hits:
            if not present(f, have):
                need.add(f)
        return need
    if pkg not in PKG_INDEX:
        unresolved.add(import_fq + "  (上游不存在该包，需桌面端实现)")
    else:
        unresolved.add(import_fq + "  (无法定位声明)")
    return need


def main():
    dry = "--dry" in sys.argv
    build_index()
    total = 0
    rounds = 0
    last_unresolved = set()
    while True:
        rounds += 1
        pkgs, have = scan_engine()
        unresolved = set()
        to_copy = {}
        for fq, wc in sorted(engine_imports()):
            for f in resolve(fq, wc, pkgs, have, unresolved):
                to_copy.setdefault(f, fq)
        if not to_copy:
            last_unresolved = unresolved
            break
        if dry:
            print("--- 第 %d 轮：需要 %d 个文件 ---" % (rounds, len(to_copy)))
            for f in sorted(to_copy, key=lambda p: p.as_posix()):
                print("  " + f.relative_to(UPSTREAM).as_posix())
            last_unresolved = unresolved
            break
        for f in to_copy:
            dst = dest_for(f)
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, dst)
        total += len(to_copy)
        if rounds > 15:
            print("达到轮次上限")
            break

    n = len(list(engine_files()))
    print("\n闭包补齐：同步 %d 个文件，%d 轮" % (total, rounds))
    print("engine 源文件总数：%d" % n)
    if last_unresolved:
        print("\n待桌面端自行实现的符号 %d 项：" % len(last_unresolved))
        for u in sorted(last_unresolved):
            print("  - " + u)
    return 0


if __name__ == "__main__":
    sys.exit(main())
