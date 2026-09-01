#!/usr/bin/env python3
"""把 `X.getString(...)` 安全替换为 `stringResource(...)`（作用域感知，可回退）。

用法：
    python3 apply_string_resource.py <文件相对路径>            # 干跑，只报告
    python3 apply_string_resource.py <文件相对路径> --apply    # 实际写入

流程：
  1. 调 analyze_scope 找出可安全替换的位置；
  2. 从后往前替换，避免位置偏移；
  3. 必要时补 `import androidx.compose.ui.res.stringResource`；
  4. 写入前自动备份到 <文件>.bak，出错可用 --revert 还原。
"""
import os
import re
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyze_scope as A  # noqa: E402

ROOT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "java"))
IMPORT_LINE = "import androidx.compose.ui.res.stringResource"


def resolve(rel):
    return rel if os.path.isabs(rel) else os.path.join(ROOT, rel)


def has_import(src):
    return bool(re.search(r"^import\s+androidx\.compose\.ui\.res\.stringResource\s*$", src, re.M))


def add_import(src, path):
    """插到最后一个 import 之后；没有 import 则插到 package 之后。"""
    imports = list(re.finditer(r"^import\s+[\w.]+\s*$", src, re.M))
    if imports:
        last = imports[-1]
        # 在最后一条 import 之后插入（保持 import 区块连续）
        return src[:last.end()] + "\n" + IMPORT_LINE + src[last.end():]
    m = re.search(r"^package\s+[\w.]+\s*$", src, re.M)
    if m:
        return src[:m.end()] + "\n\n" + IMPORT_LINE + src[m.end():]
    return IMPORT_LINE + "\n" + src


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = {a for a in sys.argv[1:] if a.startswith("--")}
    if not args:
        print(__doc__)
        sys.exit(1)
    path = resolve(args[0])

    if "--revert" in flags:
        bak = path + ".bak"
        if os.path.exists(bak):
            shutil.copy(bak, path)
            print(f"已还原：{path}")
        else:
            print(f"无备份可还原：{bak}")
        return

    src, res = A.analyze(path)
    safe = [r for r in res if r["safe"]]
    unsafe = [r for r in res if not r["safe"]]
    print(f"文件：{os.path.relpath(path, ROOT)}")
    print(f"  getString 总数 {len(res)}    将替换 {len(safe)}    保留 {len(unsafe)}")

    if "--apply" not in flags:
        print("\n（干跑模式，加 --apply 才会写入）")
        print("  将被替换的行号：", ", ".join(str(r["line"]) for r in safe[:60]),
              "..." if len(safe) > 60 else "")
        return

    if not safe:
        print("  无可替换项，未改动文件")
        return

    shutil.copy(path, path + ".bak")
    out = src
    # 从后往前替换，位置不偏移
    for r in sorted(safe, key=lambda r: -r["pos"]):
        out = out[:r["pos"]] + "stringResource(" + out[r["end"]:]

    added_import = False
    if not has_import(out):
        out = add_import(out, path)
        added_import = True

    open(path, "w", encoding="utf-8").write(out)
    print(f"  已写入：替换 {len(safe)} 处" + ("，并新增 import" if added_import else "（import 已存在）"))
    print(f"  备份：{path}.bak")


if __name__ == "__main__":
    main()
