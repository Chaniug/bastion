#!/usr/bin/env python3
"""统计 UnusedAttribute 的文件分布，判断是项目 manifest 还是第三方库合并引入。"""
import os
import xml.etree.ElementTree as ET
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
BASELINE = os.path.normpath(os.path.join(HERE, "..", "..", "app", "lint-baseline.xml"))

root = ET.parse(BASELINE).getroot()
c = Counter()
ex = {}
for iss in root.iter("issue"):
    if iss.get("id") != "UnusedAttribute":
        continue
    loc = iss.find("location")
    f = (loc.get("file") if loc is not None else "") or "?"
    c[f] += 1
    ex.setdefault(f, iss.get("message", ""))

print(f"UnusedAttribute 文件分布（共 {sum(c.values())} 条）")
print("=" * 90)
for f, v in c.most_common():
    print(f"  {v:4d}  {f}")
    print(f"        └ {ex[f][:88]}")
