#!/usr/bin/env python3
"""扫描整个 java 源码树，用 analyze_scope 的上下文判定，统计每个文件里可被安全替换为
stringResource 的 getString 调用数（即 LocalContextGetResourceValueCall 的可修复子集），按数量降序输出。"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import analyze_scope as A  # noqa: E402

ROOT = A.ROOT


def main():
    rows = []
    total_safe = total_raw = 0
    processed = 0
    for dirpath, _, files in os.walk(ROOT):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            p = os.path.join(dirpath, fn)
            try:
                src, res = A.analyze(p)
            except Exception as e:
                print(f"  [skip] {os.path.relpath(p, ROOT)}: {e}")
                continue
            processed += 1
            if not res:
                continue
            safe = [r for r in res if r["safe"]]
            total_raw += len(res)
            if safe:
                total_safe += len(safe)
                rows.append((len(safe), len(res), os.path.relpath(p, ROOT)))

    rows.sort(key=lambda x: (-x[0], x[2]))
    print(f"已扫描文件：{processed}    可安全替换总计：{total_safe}    全部 getString 调用：{total_raw}")
    print("-" * 78)
    for safe, raw, rel in rows:
        print(f"  {safe:>4}/{raw:<4}  {rel}")


if __name__ == "__main__":
    main()
