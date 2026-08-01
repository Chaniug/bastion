#!/usr/bin/env python3
"""忠实复刻 Kotlin 字符串转义，校验 B.2.3 守卫正则。
处理内嵌转义引号 \" 与 ${...}，定位具体哪个断言在 Java 语义下不命中。"""
import re
import sys

BASE = "/workspace/bastion"

def resolve(rel):
    return BASE + "/Bastion/" + rel if not rel.startswith("Bastion/") else BASE + "/" + rel

def kotlin_unescape(s):
    """复刻 Kotlin 字符串字面量转义: \\ -> \, \" -> ", \$ -> $, \t \n \r 等。"""
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == '\\' and i + 1 < len(s):
            n = s[i + 1]
            mapping = {'\\': '\\', '"': '"', '$': '$', 't': '\t', 'n': '\n', 'r': '\r', "'": "'"}
            out.append(mapping.get(n, n))  # 其它如 { } . s | 等原样保留
            i += 2
        else:
            out.append(c)
            i += 1
    return ''.join(out)

def extract_regex_body(line):
    """从 assertTrue/assertFalse(X.contains(Regex("..."))) 提取正则体(已忠实转义)。"""
    m = re.search(r'\b(assertTrue|assertFalse)\(\s*(\w+)\.contains\(\s*Regex\(\s*"', line)
    if not m:
        # 纯字符串 contains
        m2 = re.search(r'\b(assertTrue|assertFalse)\(\s*(\w+)\.contains\(\s*"', line)
        if not m2:
            return None
        kind, var = m2.group(1), m2.group(2)
        # 找第一个 " 后的内容直到行尾倒数第二个 )
        start = line.index('"', m2.end() - 1) + 1
        # 纯字符串不含转义引号(本批里都是简单串)，取直到倒数第二个 " 前
        # 以最后一个不被 \ 转义的 " 结尾
        end = len(line) - 1
        while end > start and line[end] != '"':
            end -= 1
        body = line[start:end]
        return kind, var, kotlin_unescape(body), False
    kind, var = m.group(1), m.group(2)
    start = line.index('"', m.end() - 1) + 1
    # 找第一个不被 \ 转义的 " 作为结束
    j = start
    while j < len(line):
        if line[j] == '"' and (j == 0 or line[j - 1] != '\\'):
            break
        j += 1
    body = line[start:j]
    return kind, var, kotlin_unescape(body), True

def main(path):
    print(f"\n===== 校验文件: {path} =====")
    cur_vars = {}
    failures = []
    with open(path, encoding="utf-8") as f:
        for idx, raw in enumerate(f, 1):
            line = raw.rstrip("\n")
            mp = re.search(r'val\s+(\w+)\s*=\s*projectFile\(\s*"([^"]+)"\s*\)', line)
            if mp:
                cur_vars[mp.group(1)] = open(resolve(mp.group(2)), encoding="utf-8").read()
                continue
            res = extract_regex_body(line)
            if not res:
                continue
            kind, var, body, is_regex = res
            src = cur_vars.get(var)
            if src is None:
                print(f"  [L{idx}] 警告: 变量 {var} 无源文本")
                continue
            if is_regex:
                try:
                    pat = re.compile(body)
                except re.error as e:
                    print(f"  [L{idx}] ✗ 正则编译失败: {e}\n          正则(转义后): {body!r}")
                    failures.append(idx)
                    continue
                matched = pat.search(src) is not None
            else:
                matched = body in src
            ok = matched if kind == "assertTrue" else (not matched)
            status = "✓" if ok else "✗"
            print(f"  [L{idx}] {status} {kind}({var}) regex={is_regex} {'命中' if matched else '未命中'} | {body!r}")
            if not ok:
                failures.append(idx)
    print(f"  --- 失败条数: {len(failures)} (行: {failures}) ---")
    return failures

if __name__ == "__main__":
    total = []
    for p in sys.argv[1:]:
        total += main(p)
    print(f"\n总失败行: {total}" if total else "\n全部断言校验通过 ✓")
    sys.exit(1 if total else 0)
