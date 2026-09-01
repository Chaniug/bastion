#!/usr/bin/env python3
"""删除 baseline 中 UnusedResources 里「确无任何引用」的资源。

安全设计（针对 887 条里 4 条致命误报）：
1. 先用精确引用模式（R.x.y / @x/y / getIdentifier("y","x")）复核一遍，
   任何有引用的条目一律跳过。例如 R.raw.eff_short_wordlist 被
   PasswordGenerator 用 getIdentifier 动态加载，删了密码短语生成就废了。
2. values-*.xml 中按「元素块」删除：保留注释与分区结构；仅当该块独占
   整行时才连行首缩进与行尾换行一起删，避免误伤同行其他元素。
3. 文件型资源（drawable/raw 等）只删除 baseline 指定的那一个文件。
4. 不生成 .bak（避免误提交），回滚直接 git checkout。
"""
import os
import re
import sys

from verify_unused_resources import APP_DIR, load_targets, scan_sources

VALUES_PREFIX = "src/main/res/values"


def locate_elements(text, tag):
    """返回 [(name, start, end)]；end 为元素结束后一位。"""
    out = []
    pat = re.compile(r"<" + re.escape(tag) + r"(?=[\s/>])")
    for m in pat.finditer(text):
        start = m.start()
        gt = text.find(">", start)
        if gt == -1:
            continue
        head = text[start:gt]
        if head.rstrip().endswith("/"):          # 自闭合
            end = gt + 1
        else:
            close = text.find("</" + tag, gt)
            if close == -1:
                continue
            end = text.find(">", close) + 1
        nm = re.search(r'\bname\s*=\s*"([^"]*)"', head)
        out.append((nm.group(1) if nm else None, start, end))
    return out


def plan_block(text, start, end):
    """若该块独占整行，则扩展到整行（含行首缩进与行尾换行）。"""
    line_start = text.rfind("\n", 0, start) + 1
    nl = text.find("\n", end)
    line_end = len(text) if nl == -1 else nl + 1
    prefix, suffix = text[line_start:start], text[end:line_end]
    if prefix.strip() == "" and suffix.strip() == "":
        return line_start, line_end
    return start, end


def squeeze_blank_lines(text):
    """连续 3+ 换行压缩为 2（最多保留一个空行），保持文件整洁。"""
    return re.sub(r"\n{3,}", "\n\n", text)


def main():
    apply = "--apply" in sys.argv
    targets = load_targets()
    found, nfiles = scan_sources()
    print(f"baseline UnusedResources：{len(targets)} 条；已扫描 {nfiles} 个源文件")

    # 复核：跳过任何仍有引用的条目
    safe, skipped = {}, 0
    for key, deff in targets.items():
        if found.get(key, set()) - {deff}:
            skipped += 1
            continue
        safe[key] = deff
    print(f"复核后：可删 {len(safe)} 条，保留（有引用/疑似误报）{skipped} 条\n")

    # 按定义文件分组
    by_file = {}
    for (rtype, name), deff in safe.items():
        by_file.setdefault(deff, []).append((rtype, name))

    files_to_delete, xml_to_edit = [], []
    for deff, items in by_file.items():
        if deff.startswith(VALUES_PREFIX):
            xml_to_edit.append((deff, items))
        else:
            files_to_delete.append((deff, items))

    print("=" * 84)
    print(f"待编辑的 values XML：{len(xml_to_edit)} 个文件")
    print(f"待删除的资源文件  ：{len(files_to_delete)} 个")
    print("=" * 84)

    total_removed = 0
    # 1) 删除独立资源文件
    for deff, items in files_to_delete:
        abs_p = os.path.normpath(os.path.join(APP_DIR, deff))
        names = ", ".join(f"R.{t}.{n}" for t, n in items)
        if not os.path.exists(abs_p):
            print(f"  [缺失] {deff}  ({names})")
            continue
        if apply:
            os.remove(abs_p)
        print(f"  [删文件] {deff}  ({names})")
        total_removed += len(items)

    # 2) 编辑 values XML
    for deff, items in xml_to_edit:
        abs_p = os.path.normpath(os.path.join(APP_DIR, deff))
        if not os.path.exists(abs_p):
            print(f"  [缺失] {deff}")
            continue
        text = open(abs_p, encoding="utf-8").read()
        want = {(t, n) for t, n in items}
        types = {t for t, _ in items}

        spans = []
        for tag in types:
            for nm, s, e in locate_elements(text, tag):
                # Android 把资源名里的 '.' 在 R 中替换成 '_'
                # （如 ThemeOverlay.Bastion.AutofillAuth → ThemeOverlay_Bastion_AutofillAuth），
                # 比较时必须归一化，否则含点号的 style 会全部漏掉。
                canon = nm.replace(".", "_") if nm else None
                if (tag, canon) in want:
                    spans.append(plan_block(text, s, e))

        if len(spans) != len(items):
            print(f"  [警告] {deff}：{len(items) - len(spans)} 项未能定位，已跳过")
        if not spans:
            continue
        # 从后往前删，避免位置偏移
        for s, e in sorted(spans, key=lambda x: -x[0]):
            text = text[:s] + text[e:]

        new_text = squeeze_blank_lines(text)
        if apply:
            open(abs_p, "w", encoding="utf-8").write(new_text)
        print(f"  [改 XML] {deff}  删除 {len(spans)} 项")
        total_removed += len(spans)

    print("=" * 84)
    print(f"{'已实际删除' if apply else '干跑：将删除'} 资源项合计 {total_removed} 条")
    if not apply:
        print("确认无误后加 --apply 执行（回滚：git checkout -- <文件>）")


if __name__ == "__main__":
    main()
