#!/usr/bin/env python3
"""删资源后的「后悔检查」（只读）。

背景：删除 883 条 UnusedResources 时的引用复核只扫了 src/main，
而 compileDebugKotlin 并不包含 test 源集。若 src/test、src/debug 或
其他模块（desktop）引用了被删资源，主变体照样编译通过，
但单元测试会编译失败或运行期抛 Resources.NotFoundException。

两处易误报，已专门处理：
1. 字符串字面量里的 R.string.x 不是真实引用。例如架构守护测试写
   assertFalse(body.contains("stringResource(R.string.passkey_detail_account)"))，
   那是文本断言，资源删了照样编译。故匹配前先剥离字面量。
2. 「实际已删」= baseline 全集 - main 中仍有真实引用者（即特意保留的误报项，
   如 R.raw.eff_short_wordlist）。否则会把保留项当成危险点。

终审仍以编译器为准：./gradlew :app:compileDebugUnitTestKotlin
"""
import os
import re
import xml.etree.ElementTree as ET
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
BASTION = os.path.normpath(os.path.join(HERE, "..", ".."))          # Bastion/
ROOT = os.path.normpath(os.path.join(BASTION, ".."))                # 仓库根
BASELINE = os.path.join(BASTION, "app", "lint-baseline.xml")
MAIN = os.path.join(BASTION, "app", "src", "main")

RE_R = re.compile(r"\bR\.(\w+)\.(\w+)")
RE_XML = re.compile(r"@(\w+)/(\w+)")
RE_DYN = re.compile(r'getIdentifier\(\s*"([^"]+)"\s*,\s*"([^"]+)"')
RE_DQ = re.compile(r'"[^"\n]*"')
RE_SQ = re.compile(r"'[^'\n]*'")


def strip_literals(line):
    """去掉字符串字面量，避免把文本里出现的 R.string.x 误判为真实引用。"""
    return RE_SQ.sub("''", RE_DQ.sub('""', line))


def baseline_unused():
    root = ET.parse(BASELINE).getroot()
    out = set()
    for iss in root.iter("issue"):
        if iss.get("id") != "UnusedResources":
            continue
        m = re.search(r"R\.(\w+)\.(\w+)", iss.get("message", ""))
        if m:
            out.add((m.group(1), m.group(2)))
    return out


def scan(root_dir):
    """返回 {(type,name): [(file,line)]}，只认真实代码引用。"""
    hits = defaultdict(list)
    nfiles = 0
    if not os.path.isdir(root_dir):
        return hits, 0
    for dirpath, dirnames, filenames in os.walk(root_dir):
        dirnames[:] = [d for d in dirnames
                       if d not in ("build", ".git", ".gradle")]
        for fn in filenames:
            if not fn.endswith((".kt", ".xml", ".kts")):
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, ROOT).replace("\\", "/")
            try:
                lines = open(full, encoding="utf-8",
                             errors="ignore").read().splitlines()
            except OSError:
                continue
            nfiles += 1
            for ln, raw in enumerate(lines, 1):
                refs = set()
                # 动态引用靠字符串字面量传参（getIdentifier("name","type")），
                # 必须在剥离字面量【之前】匹配，否则会被 strip 清空而漏检。
                for m in RE_DYN.finditer(raw):
                    refs.add((m.group(2), m.group(1)))
                # 静态引用则在剥离字面量【之后】匹配，排除文本断言等误报。
                line = strip_literals(raw)
                for m in RE_R.finditer(line):
                    refs.add((m.group(1), m.group(2)))
                for m in RE_XML.finditer(line):
                    refs.add((m.group(1), m.group(2)))
                for key in refs:
                    hits[key].append((rel, ln))
    return hits, nfiles


def main():
    total = baseline_unused()
    main_hits, _ = scan(MAIN)
    kept = {k for k in main_hits if k in total}      # 特意保留的误报项
    deleted = total - kept
    print(f"baseline 全集 {len(total)} 条")
    print(f"  实际保留（main 中仍有引用）{len(kept)} 条："
          + ", ".join(f"R.{t}.{n}" for t, n in sorted(kept)))
    print(f"  实际已删除 {len(deleted)} 条\n")

    areas = [
        ("app/src/test   (单元测试)", os.path.join(BASTION, "app", "src", "test")),
        ("app/src/debug  (debug 源集)", os.path.join(BASTION, "app", "src", "debug")),
        ("desktop        (其他模块)", os.path.join(ROOT, "desktop")),
        ("app/src/main   (主源集·应为 0)", MAIN),
    ]

    danger_total = 0
    for label, path in areas:
        hits, nfiles = scan(path)
        danger = {k: v for k, v in hits.items() if k in deleted}
        print("=" * 84)
        print(f"{label}  扫描 {nfiles} 个文件")
        if not nfiles:
            print("  （目录不存在或无 .kt/.xml，跳过）")
            continue
        if not danger:
            print("  未发现对已删资源的真实引用")
            continue
        danger_total += len(danger)
        print(f"  {len(danger)} 个已删资源仍被引用：")
        for (t, n), locs in sorted(danger.items())[:40]:
            print(f"     R.{t}.{n}")
            for f, ln in locs[:3]:
                print(f"         → {f}:{ln}")

    print("=" * 84)
    if danger_total:
        print(f"合计 {danger_total} 个已删资源仍被真实引用 → 需恢复或改引用方")
    else:
        print("结论：main 之外无任何真实引用指向已删资源，删除安全。")


if __name__ == "__main__":
    main()
