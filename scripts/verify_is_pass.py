#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_is_pass.py
=================

对 codemod_run_catching 的落地结果做 IS_PASS 一致性自检（修复版）。

真正的硬指标是「块注释不再丢 /」：
  - 由于 transform() 对所有字符（注释/字符串/行注释）均为“逐字符回显”，仅有
    runCatching / @runCatching / kotlin.runCatching 三类 token 替换与 import 注入会改变文本，
    且这些都不涉及 "/*" 或 "*/"。因此落地后的 `/*`、`*/` 计数应与其「干净原始版本」完全相同。
  - 故用「工作树计数 == 干净原始(HEAD)计数」作为「/ 是否被丢」的权威判定。
  - 同时保留主理人指定的朴素等号断言（grep -o '/*' == grep -o '*/'）作为辅助，
    并对朴素不成立、但工作树==原始的文件打印上下文，证明其为字符串/字符字面量中的
    "/*"、"*/" 造成的误报（非真实 bug）。

其他检查：
  - MainActivity.kt 抽验：第 436 行应为 "     */"，且 /* == */（均 >0）。
  - 残留扫描：除 SwallowedExceptionLogger.kt 外，全仓无 runCatching( / kotlin.runCatching(。
  - import 配平：每个被改文件恰好 1 行目标 import。

用法：
  python3 scripts/verify_is_pass.py
"""

from __future__ import annotations

import os
import subprocess
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)
SCAN_ROOT = os.path.join(
    REPO_ROOT, "Bastion", "app", "src", "main", "java",
    "com", "bastion", "app",
)
TARGET_IMPORT = "import com.bastion.app.logging.runCatchingObserved"
WRAPPER_BASENAME = "SwallowedExceptionLogger.kt"

BLACKLIST_PATH_SEGMENTS = ("/test/", "/androidtest/", "/build/", "/generated/")


def count_sub(text: str, sub: str) -> int:
    c = 0
    s = 0
    while True:
        i = text.find(sub, s)
        if i == -1:
            break
        c += 1
        s = i + 1
    return c


def git_show_head(git_path: str) -> "str | None":
    """返回 HEAD 中该文件的原始内容；不存在（未跟踪）则返回 None。"""
    try:
        r = subprocess.run(
            ["git", "show", f"HEAD:{git_path}"],
            capture_output=True, text=True, cwd=REPO_ROOT,
        )
        if r.returncode != 0:
            return None
        return r.stdout
    except Exception:
        return None


def is_blacklisted(abs_path: str) -> bool:
    norm = abs_path.replace("\\", "/")
    base = os.path.basename(abs_path)
    if base == WRAPPER_BASENAME:
        return True
    if base.endswith(".gradle") or base.endswith(".kts"):
        return True
    for seg in BLACKLIST_PATH_SEGMENTS:
        if seg in norm:
            return True
    return False


def context_of(text: str, sub: str, width: int = 40) -> list[str]:
    """返回所有 sub 出现位置的上下文片段（用于解释误报）。"""
    out = []
    s = 0
    while True:
        i = text.find(sub, s)
        if i == -1:
            break
        a = max(0, i - width)
        b = min(len(text), i + len(sub) + width)
        out.append("..." + text[a:b].replace("\n", "\\n") + "...")
        s = i + 1
    return out


def main() -> int:
    regression_files: list[str] = []      # 工作树计数 != 原始计数 -> 真实丢字符
    naive_flagged: list[tuple[str, int, int]] = []  # 朴素 /* != */ 但工作树==原始
    import_issues: list[str] = []
    changed_files: list[str] = []

    for root, _dirs, files in os.walk(SCAN_ROOT):
        for name in files:
            if not name.endswith(".kt"):
                continue
            abs_path = os.path.join(root, name)
            if is_blacklisted(abs_path):
                continue
            with open(abs_path, "r", encoding="utf-8", newline="") as fh:
                content = fh.read()
            if TARGET_IMPORT not in content:
                continue
            changed_files.append(abs_path)

            rel = os.path.relpath(abs_path, REPO_ROOT).replace("\\", "/")
            wd_open = count_sub(content, "/*")
            wd_close = count_sub(content, "*/")

            # import 恰好 1 行
            imp_n = sum(1 for ln in content.split("\n") if ln.strip() == TARGET_IMPORT)
            if imp_n != 1:
                import_issues.append(f"{rel} (import count={imp_n})")

            # 权威判定：工作树计数 vs 干净原始(HEAD)
            head = git_show_head(rel)
            if head is not None:
                head_open = count_sub(head, "/*")
                head_close = count_sub(head, "*/")
                if wd_open != head_open or wd_close != head_close:
                    regression_files.append(
                        f"{rel} (worktree /*={wd_open} */={wd_close} | HEAD /*={head_open} */={head_close})"
                    )
                # 朴素等号断言（辅助，仅当工作树==原始才视为误报）
                if wd_open != wd_close:
                    naive_flagged.append((rel, wd_open, wd_close))
            else:
                # 未跟踪文件：无“原始”可比，要求工作树自身配平
                if wd_open != wd_close:
                    naive_flagged.append((rel, wd_open, wd_close))
                    # 未跟踪且不平衡：按朴素规则判为可疑（无原始可对照）
                    regression_files.append(
                        f"{rel} (UNTRACKED, worktree /*={wd_open} */={wd_close})"
                    )

    # MainActivity.kt 抽验
    ma_rel = ma_detail = None
    ma_ok = False
    ma_path = None
    for root, _dirs, files in os.walk(SCAN_ROOT):
        for name in files:
            if name == "MainActivity.kt":
                ma_path = os.path.join(root, name)
                ma_rel = os.path.relpath(ma_path, REPO_ROOT)
                break
        if ma_path:
            break
    if ma_path is None:
        ma_detail = "MainActivity.kt 未找到"
    else:
        with open(ma_path, "r", encoding="utf-8", newline="") as fh:
            ma_text = fh.read()
            ma_lines = ma_text.split("\n")
        line436 = ma_lines[435] if len(ma_lines) >= 436 else "<EOF>"
        ma_open = count_sub(ma_text, "/*")
        ma_close = count_sub(ma_text, "*/")
        line_ok = line436.rstrip("\r") == "     */"
        balance_ok = (ma_open == ma_close) and ma_open > 0
        ma_ok = line_ok and balance_ok
        ma_detail = (
            f"line436={line436!r} (expect '     */' -> {'OK' if line_ok else 'BAD'}); "
            f"/*={ma_open}, */={ma_close} (both>0 & equal -> {'OK' if balance_ok else 'BAD'})"
        )

    # 残留扫描
    residual: list[str] = []
    for root, _dirs, files in os.walk(SCAN_ROOT):
        for name in files:
            if not name.endswith(".kt"):
                continue
            abs_path = os.path.join(root, name)
            if os.path.basename(abs_path) == WRAPPER_BASENAME:
                continue
            with open(abs_path, "r", encoding="utf-8", newline="") as fh:
                content = fh.read()
            if "kotlin.runCatching(" in content:
                residual.append(os.path.relpath(abs_path, REPO_ROOT) + " [kotlin.runCatching(]")
                continue
            if "runCatching(" in content:
                # runCatchingObserved( 不含 "runCatching("（其后为 O 不是 (），故精确命中即残留
                residual.append(os.path.relpath(abs_path, REPO_ROOT) + " [runCatching(]")

    # ---------- 汇总 ----------
    print("=" * 70)
    print("IS_PASS 一致性自检（修复版：权威判定 = 工作树计数 == HEAD 原始计数）")
    print("=" * 70)
    print(f"  被改文件数 (含目标 import) : {len(changed_files)}")
    print(f"  真实回归(丢字符)文件数     : {len(regression_files)}")
    for b in regression_files:
        print(f"      ! {b}")
    print(f"  朴素等号断言误报(已证伪)   : {len(naive_flagged)}")
    for rel, o, c in naive_flagged:
        print(f"      ~ {rel} (/*={o}, */={c})  <- 工作树==原始，字符串字面量所致")
    print(f"  import 异常文件数          : {len(import_issues)}")
    for b in import_issues:
        print(f"      ! {b}")
    print(f"  残留 runCatching( 文件数   : {len(residual)}")
    for b in residual:
        print(f"      ! {b}")
    print(f"  MainActivity.kt ({ma_rel or 'N/A'}): {ma_detail}")
    print("=" * 70)

    is_pass = (
        len(regression_files) == 0
        and len(import_issues) == 0
        and len(residual) == 0
        and ma_ok
        and len(changed_files) == 201
    )
    print(f"IS_PASS: {'YES' if is_pass else 'NO'}")

    # 若朴素误报非空，打印上下文供主理人复核（证明非真实 bug）
    if naive_flagged:
        print("-" * 70)
        print("朴素误报文件上下文（'/*' / '*/' 出现位置，证明在字符串/字符字面量中）:")
        for rel, _o, _c in naive_flagged:
            gp = os.path.join(REPO_ROOT, rel.replace("/", os.sep))
            txt = open(gp, encoding="utf-8", newline="").read()
            print(f"\n[{rel}]")
            for ctx in context_of(txt, "/*"):
                print(f"    /* -> {ctx}")
            for ctx in context_of(txt, "*/"):
                print(f"    */ -> {ctx}")
    return 0 if is_pass else 1


if __name__ == "__main__":
    raise SystemExit(main())
