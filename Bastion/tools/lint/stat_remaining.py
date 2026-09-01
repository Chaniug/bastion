#!/usr/bin/env python3
"""统计 baseline 中剩余 lint 债的分布，按 message 归类，辅助决定清理优先级。"""
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
APP_DIR = os.path.normpath(os.path.join(HERE, "..", "..", "app"))
BASELINE = os.path.join(APP_DIR, "lint-baseline.xml")

root = ET.parse(BASELINE).getroot()
by_id = {}
for iss in root.iter("issue"):
    iid = iss.get("id")
    by_id.setdefault(iid, []).append(iss)

for iid in sorted(by_id, key=lambda k: -len(by_id[k])):
    items = by_id[iid]
    print(f"\n{iid}  ({len(items)} 条)")
    print("-" * 78)
    if iid in ("UnusedResources", "UnusedAttribute", "GradleDependency",
               "UseTomlInstead", "GradleDependencies"):
        c = Counter()
        for it in items:
            msg = it.get("message", "")
            # UnusedResources: "The resource `R.string.foo` appears to be unused"
            m = re.search(r"R\.(\w+)\.(\w+)", msg)
            if m:
                c[f"{m.group(1)} 资源"] += 1
                continue
            m = re.search(r"`([^`]+)`", msg)
            c[m.group(1)[:60] if m else msg[:60]] += 1
        for k, v in c.most_common(25):
            print(f"  {v:5d}  {k}")
    else:
        for it in items[:10]:
            loc = it.find("location")
            f = loc.get("file") if loc is not None else "?"
            print(f"  {os.path.basename(f)}: {it.get('message','')[:70]}")
        if len(items) > 10:
            print(f"  ... 其余 {len(items)-10} 条")

# UnusedResources 按文件（资源所在 xml 目录）归类
print("\n\nUnusedResources 命中资源所在目录")
print("=" * 78)
c = Counter()
for it in by_id.get("UnusedResources", []):
    loc = it.find("location")
    f = (loc.get("file") if loc is not None else "") or ""
    c[os.path.basename(os.path.dirname(f))] += 1
for k, v in c.most_common(30):
    print(f"  {v:5d}  {k}")
