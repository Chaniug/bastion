#!/usr/bin/env python3
"""统计 LocalContext.current 派生 receiver 的资源读取调用分布，按方法类型与 receiver 名归类。"""
import os, re, sys
from collections import Counter

JAVA = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "java"))

METHODS = ["getString", "getQuantityString", "getPluralString", "getText",
           "getColor", "getDrawable", "getDimension", "getBoolean",
           "getInteger", "getFont"]

# 形如 X.method(  其中 X 是某个局部变量
CALL_RE = re.compile(r"\b(\w+)\.(\w+)\s*\(")
# 形如 val X = LocalContext.current  或  val X = LocalContext.current.applicationContext 等
ASSIGN_RE = re.compile(r"\bval\s+(\w+)\s*=\s*LocalContext\.current\b")

by_method = Counter()
by_receiver = Counter()
file_count = Counter()
total = 0

for dp, _, files in os.walk(JAVA):
    for fn in files:
        if not fn.endswith(".kt"):
            continue
        p = os.path.join(dp, fn)
        try:
            src = open(p, encoding="utf-8").read()
        except Exception:
            continue
        # 收集本文件中由 LocalContext.current 派生的 receiver 名
        receivers = set(m.group(1) for m in ASSIGN_RE.finditer(src))
        for m in CALL_RE.finditer(src):
            recv, meth = m.group(1), m.group(2)
            if meth in METHODS and recv in receivers:
                by_method[meth] += 1
                by_receiver[recv] += 1
                file_count[p] += 1
                total += 1

print(f"LocalContext.current 派生 receiver 的资源读取调用总计：{total}")
print("=" * 50)
print("按方法类型：")
for k, v in by_method.most_common():
    print(f"  {k:<20} {v:>5}")
print("=" * 50)
print("按 receiver 名：")
for k, v in by_receiver.most_common(15):
    print(f"  {k:<20} {v:>5}")
print("=" * 50)
print(f"涉及文件数：{len(file_count)}")
