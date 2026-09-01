#!/usr/bin/env python3
"""对 lint-baseline.xml 中所有 LocalContextGetResourceValueCall 命中的文件，
在文件顶部（package 之前）追加 `@file:Suppress("LocalContextGetResourceValueCall")`。

零行为变更：仅用 Kotlin 内置 kotlin.Suppress 抑制该 lint，无需 import。
支持 --apply 实际写入；默认干跑。写入前自动备份为 <file>.bak。
"""
import os
import re
import shutil
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
APP_DIR = os.path.normpath(os.path.join(HERE, "..", "..", "app"))  # Bastion/app
BASELINE = os.path.join(APP_DIR, "lint-baseline.xml")
SUPPRESS_LINE = '@file:Suppress("LocalContextGetResourceValueCall")'
ANNOT_RE = re.compile(r'^\s*@file:(SuppressLint|Suppress)\s*\(')


def collect_files():
    root = ET.parse(BASELINE).getroot()
    files = set()
    for iss in root.iter("issue"):
        if iss.get("id") != "LocalContextGetResourceValueCall":
            continue
        loc = iss.find("location")
        f = (loc.get("file") if loc is not None else iss.get("file")) or ""
        if f:
            files.add(f.replace("\\", "/"))
    return files


def to_abs(rel):
    # baseline 路径形如 src/main/java/com/.../X.kt，相对 Bastion/app
    return os.path.normpath(os.path.join(APP_DIR, rel))


def already_suppressed(text):
    for line in text.splitlines():
        if ANNOT_RE.match(line) and "LocalContextGetResourceValueCall" in line:
            return True
    return False


def add_suppress(text):
    """在 package 之前插入抑制注解行；若已存在则原样返回。"""
    if already_suppressed(text):
        return text, False
    lines = text.splitlines(keepends=True)
    # 找 package 行
    idx = next((i for i, ln in enumerate(lines)
                if ln.lstrip().startswith("package ")), None)
    if idx is None:
        # 没有 package，插到最前
        return SUPPRESS_LINE + "\n" + text, True
    # 若前面紧邻已是 @file: 注解块，直接在其前插入
    new_lines = lines[:idx] + [SUPPRESS_LINE + "\n"] + lines[idx:]
    return "".join(new_lines), True


def main():
    apply = "--apply" in sys.argv
    files = sorted(collect_files())
    print(f"baseline 中 LocalContextGetResourceValueCall 命中文件数：{len(files)}")
    print("=" * 78)
    changed = 0
    skipped = 0
    missing = 0
    for rel in files:
        abs = to_abs(rel)
        if not os.path.exists(abs):
            print(f"  [缺失] {rel}")
            missing += 1
            continue
        text = open(abs, encoding="utf-8").read()
        new_text, did = add_suppress(text)
        if not did:
            skipped += 1
            continue
        if not apply:
            print(f"  [将改] {rel}")
            changed += 1
            continue
        shutil.copy(abs, abs + ".bak")
        open(abs, "w", encoding="utf-8").write(new_text)
        print(f"  [已改] {rel}")
        changed += 1
    print("=" * 78)
    print(f"{'干跑' if not apply else '实际写入'}：将改/已改 {changed}  跳过(已抑制) {skipped}  缺失 {missing}")


if __name__ == "__main__":
    main()
