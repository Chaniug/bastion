#!/usr/bin/env python3
"""对全部 getString 调用按 analyze_scope 的判定原因分类，统计 LocalContextGetResourceValueCall 的可修复性。"""
import os, sys
from collections import Counter
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import analyze_scope as A

ROOT = A.ROOT

def main():
    reason_counter = Counter()
    safe_total = 0
    raw_total = 0
    per_file_unsafe = Counter()
    # 收集「非 composable 上下文」的具体形态
    kind_counter = Counter()
    for dirpath, _, files in os.walk(ROOT):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            p = os.path.join(dirpath, fn)
            try:
                src, res = A.analyze(p)
            except Exception:
                continue
            for r in res:
                raw_total += 1
                if r["safe"]:
                    safe_total += 1
                else:
                    reason_counter[r["reason"]] += 1
                    # 提取 unsafe 的 kind:token
                    reason = r["reason"]
                    kind_counter[reason.split("（")[0]] += 1
                    rel = os.path.relpath(p, ROOT)
                    per_file_unsafe[rel] += 1

    print(f"全部 getString 调用：{raw_total}    可安全替换：{safe_total}    不安全：{raw_total - safe_total}")
    print("=" * 70)
    print("不安全原因分布：")
    for k, v in reason_counter.most_common(12):
        print(f"  {v:>5}  {k}")
    print("=" * 70)
    print("不安全依据(token 形态) top：")
    for k, v in kind_counter.most_common(12):
        print(f"  {v:>5}  {k}")
    print("=" * 70)
    print("不安全调用最多的文件 top 12：")
    for k, v in per_file_unsafe.most_common(12):
        print(f"  {v:>4}  {k}")

if __name__ == "__main__":
    main()
