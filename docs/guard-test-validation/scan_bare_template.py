#!/usr/bin/env python3
"""预检：扫描守卫测试里 Regex("...") 内部"偶数反斜杠 + $ 后接标识符"的真·裸模板写法。

Kotlin 里字面 $ 若要出现在正则中，必须转义为 \\\\$ (三反斜杠，得到正则 \$) 以免被当成
$identifier 模板变量（会 Unresolved reference 编译失败）。写成 \\$identifier（两反斜杠）
会炸。花括号形式 ${...} 写成 \\$\\{...\\} 已多次 CI 验证安全，本脚本不报。

用法: python3 scan_bare_template.py [文件或目录...]
不传参则扫描整个 app/src/test 树。
"""
import re
import sys
import glob
import os

def scan_file(path):
    bad = []
    for ln, raw in enumerate(open(path, encoding="utf-8"), 1):
        m = re.search(r'Regex\(\s*"', raw)
        if not m:
            continue
        start = raw.index('"', m.end() - 1) + 1
        j = start
        while j < len(raw):
            if raw[j] == '"' and (j == 0 or raw[j - 1] != '\\'):
                break
            j += 1
        s = raw[start:j]
        i = 0
        while i < len(s):
            if s[i] == '$':
                k = i - 1
                bs = 0
                while k >= 0 and s[k] == '\\':
                    bs += 1
                    k -= 1
                nxt = s[i + 1] if i + 1 < len(s) else ''
                if bs % 2 == 0 and (nxt.isalpha() or nxt == '_'):
                    bad.append((ln, s[max(0, i - 6):i + 18]))
            i += 1
    return bad

def main():
    targets = sys.argv[1:] or [os.path.join("Bastion", "app", "src", "test")]
    files = []
    for t in targets:
        if os.path.isdir(t):
            files += glob.glob(os.path.join(t, "**", "*.kt"), recursive=True)
        else:
            files.append(t)
    found = []
    for p in files:
        for ln, snip in scan_file(p):
            found.append((p, ln, snip))
    if found:
        print("发现 Regex 内真·裸模板 $identifier 写法（会编译失败，应改为 \\\\$）:")
        for p, ln, snip in found:
            print(f"  {p}:{ln} ...{snip!r}")
        sys.exit(1)
    print("✓ 未发现 Regex 内裸模板 $identifier 写法")

if __name__ == "__main__":
    main()
