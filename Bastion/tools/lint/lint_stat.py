#!/usr/bin/env python3
"""统计 lint-baseline.xml 的条目数与各 issue id 分布，支持两版对比。

用法：
    python3 lint_stat.py                     # 只统计当前 baseline
    python3 lint_stat.py --save  名字        # 存一份快照到 /root/.codebuddy/artifact/
    python3 lint_stat.py --diff  快照.json   # 与快照对比
"""
import json
import os
import sys
import xml.etree.ElementTree as ET

BASELINE = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "lint-baseline.xml"))
SNAP_DIR = os.environ.get("LINT_SNAP_DIR") or os.path.dirname(os.path.abspath(__file__))


def load(path):
    root = ET.parse(path).getroot()
    items = []
    for iss in root.iter("issue"):
        # AGP baseline 的文件路径在 <location> 子元素上（也可能直接挂在 <issue> 上）
        loc = iss.find("location")
        src = loc if loc is not None else iss
        parts = []
        if loc is None:  # 多位置时保留主位置
            parts = [l.get("file") for l in iss.findall("location")]
        items.append(
            {
                "id": iss.get("id"),
                "file": (src.get("file") or (parts[0] if parts else "") or "").replace("\\", "/"),
                "line": src.get("line"),
                "message": (iss.get("message") or "")[:80],
            }
        )
    return items


def tally(items):
    d = {}
    for it in items:
        d[it["id"]] = d.get(it["id"], 0) + 1
    return dict(sorted(d.items(), key=lambda kv: (-kv[1], kv[0])))


def top_files(items, n=8):
    d = {}
    for it in items:
        d[it["file"]] = d.get(it["file"], 0) + 1
    return sorted(d.items(), key=lambda kv: -kv[1])[:n]


def main():
    items = load(BASELINE)
    stat = tally(items)
    total = len(items)

    if "--save" in sys.argv:
        name = sys.argv[sys.argv.index("--save") + 1]
        p = os.path.join(SNAP_DIR, f"lint_snap_{name}.json")
        json.dump({"total": total, "stat": stat}, open(p, "w"), indent=2, ensure_ascii=False)
        print(f"快照已存：{p}（{total} 条）")
        return

    print(f"baseline 总条目：{total}   种类：{len(stat)}")
    print("-" * 52)
    for k, v in stat.items():
        print(f"  {k:<42} {v:>6}")
    print("-" * 52)
    print("问题最多的文件：")
    for f, c in top_files(items):
        print(f"  {c:>5}  {f or '(无文件路径)'}")

    if "--diff" in sys.argv:
        p = sys.argv[sys.argv.index("--diff") + 1]
        old = json.load(open(p))
        print()
        print(f"== 与快照对比（旧 {old['total']} → 新 {total}）==")
        delta = total - old["total"]
        print(f"  总计：{delta:+d}")
        print("-" * 52)
        for k in sorted(set(old["stat"]) | set(stat), key=lambda k: -abs(stat.get(k, 0) - old["stat"].get(k, 0))):
            o, n = old["stat"].get(k, 0), stat.get(k, 0)
            if o != n:
                print(f"  {k:<40} {o:>6} → {n:<6} {n - o:+d}")


if __name__ == "__main__":
    main()
