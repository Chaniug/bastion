#!/usr/bin/env python3
"""精确量化 UnusedResources 的误报风险（只读，不改文件）。

与 token 粗筛不同，这里只认「真正的资源引用」：
  - Kotlin:  R.string.foo / R.color.bar / R.raw.baz
  - XML:     @string/foo / @color/bar
  - 动态:    getIdentifier("foo", "raw", ...)

只有这些才算「被使用」。资源名恰好作为普通单词出现在代码/注释里
（如 length、menu、notification）不算引用——那正是粗筛的假阳性来源。

结论用于判断 UnusedResources 能否安全批量删除：误删会引发
Resources.NotFoundException，或让依赖动态引用的功能静默失效。
"""
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
APP_DIR = os.path.normpath(os.path.join(HERE, "..", "..", "app"))
BASELINE = os.path.join(APP_DIR, "lint-baseline.xml")
SRC = os.path.join(APP_DIR, "src", "main")

RE_R = re.compile(r"\bR\.(\w+)\.(\w+)")
RE_XML = re.compile(r"@(\w+)/(\w+)")
RE_DYN = re.compile(r'getIdentifier\(\s*"([^"]+)"\s*,\s*"([^"]+)"')


def load_targets():
    """(type,name) -> 定义文件路径"""
    root = ET.parse(BASELINE).getroot()
    targets = {}
    for iss in root.iter("issue"):
        if iss.get("id") != "UnusedResources":
            continue
        m = re.search(r"R\.(\w+)\.(\w+)", iss.get("message", ""))
        if not m:
            continue
        loc = iss.find("location")
        targets[(m.group(1), m.group(2))] = (
            (loc.get("file") if loc is not None else "") or ""
        ).replace("\\", "/")
    return targets


def scan_sources():
    """返回 {(type,name): set(引用它的文件)}"""
    found = defaultdict(set)
    nfiles = 0
    for dirpath, dirnames, filenames in os.walk(SRC):
        dirnames[:] = [d for d in dirnames
                       if d not in ("build", ".git", "androidTest", "test")]
        for fn in filenames:
            if not fn.endswith((".kt", ".xml")):
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, APP_DIR).replace("\\", "/")
            try:
                content = open(full, encoding="utf-8", errors="ignore").read()
            except OSError:
                continue
            nfiles += 1
            refs = set()
            for m in RE_R.finditer(content):
                refs.add((m.group(1), m.group(2)))
            for m in RE_XML.finditer(content):
                refs.add((m.group(1), m.group(2)))
            for m in RE_DYN.finditer(content):
                refs.add((m.group(2), m.group(1)))  # (type, name)
            for key in refs:
                found[key].add(rel)
    return found, nfiles


def main():
    targets = load_targets()
    found, nfiles = scan_sources()
    print(f"baseline 中 UnusedResources：{len(targets)} 条")
    print(f"已扫描 src/main 下 {nfiles} 个 .kt/.xml（精确引用模式）\n")

    false_pos, safe = [], []
    for key, deff in targets.items():
        files = found.get(key, set()) - {deff}
        if files:
            false_pos.append((key, deff, sorted(files)))
        else:
            safe.append((key, deff))

    total = len(targets)
    print("=" * 92)
    print(f"确凿误报（确有代码/XML 引用，删除会出事）：{len(false_pos)}")
    print(f"可安全删除候选（无任何引用）            ：{len(safe)}  "
          f"({len(safe)*100//total}% of {total})")
    print("=" * 92)

    show = int(sys.argv[1]) if len(sys.argv) > 1 else 20
    print(f"\n【确凿误报 · 全部 {len(false_pos)} 条】")
    for (t, n), deff, files in false_pos:
        print(f"  R.{t}.{n}   (定义于 {deff})")
        for f in files[:3]:
            print(f"      被引用 → {f}")

    print(f"\n【可安全删除候选 · 前 {min(show, len(safe))} 条】")
    for (t, n), deff in safe[:show]:
        print(f"  R.{t}.{n}   ({deff})")

    # 按类型统计可删量
    by_type = defaultdict(int)
    for (t, n), _ in safe:
        by_type[t] += 1
    print("\n【可安全删除候选 · 按类型】")
    for t, v in sorted(by_type.items(), key=lambda x: -x[1]):
        print(f"  {v:5d}  R.{t}.*")


if __name__ == "__main__":
    main()
