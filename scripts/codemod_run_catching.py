#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
codemod_run_catching.py
=======================

将全仓库里静默吞掉异常的 ``runCatching { ... }`` 调用，改造为语义保持、可观测的
``runCatchingObserved { ... }`` 包装器调用。

背景
----
Kotlin 的 ``runCatching`` 是 lambda 调用（``runCatching { ... }``，没有括号），
且 lambda 内部常用 ``return@runCatching`` 做非局部返回。直接把函数名改成
``runCatchingObserved`` 后，如果不一起把 ``return@runCatching`` 这个标签也改名，
代码将无法编译（标签 ``runCatching`` 已不存在）。因此本脚本会同时处理：

  1. 普通调用 ``runCatching { ... }``      -> ``runCatchingObserved { ... }``
  2. 全限定调用 ``kotlin.runCatching { }``  -> ``runCatchingObserved { ... }``
     （丢掉 ``kotlin.`` 前缀，计入 kotlinFqnConverted）
  3. lambda 标签 ``return@runCatching``     -> ``return@runCatchingObserved``

下游的 ``.getOrNull()`` / ``.onFailure { }`` / ``.fold { }`` / ``.getOrDefault()`` 一律不改。

扫描范围
--------
``<repo>/Bastion/app/src/main/java/com/bastion/app/**/*.kt``

黑名单（跳过，计入 skippedBlacklist）
------------------------------------
  * 路径含 ``/test/`` ``/androidTest/`` ``/build/`` ``/generated/``
  * ``.gradle`` / ``.kts`` 文件
  * 工具文件自身 ``SwallowedExceptionLogger.kt``
  * 已含 ``import com.bastion.app.logging.runCatchingObserved`` 的文件（幂等）

import 注入
----------
仅当本文件有 >=1 处真实替换，且未含目标 import、未用 ``import com.bastion.app.logging.*``
通配时，在 ``package ...`` 行之后（跳过已有空行）插入
``import com.bastion.app.logging.runCatchingObserved``。
无 ``package`` 行的文件计入 ``injectFailed``（仍会做文本替换，只是不注入 import）。

用法
----
  python3 codemod_run_catching.py            # 默认 dry-run：只打印统计，不写文件
  python3 codemod_run_catching.py --apply    # 真正落地改写
"""

from __future__ import annotations

import argparse
import json
import os
import sys

# --------------------------------------------------------------------------- #
# 配置
# --------------------------------------------------------------------------- #
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)                       # .../bastion
SCAN_ROOT = os.path.join(
    REPO_ROOT, "Bastion", "app", "src", "main", "java",
    "com", "bastion", "app",
)

TARGET_IMPORT = "import com.bastion.app.logging.runCatchingObserved"
WILDCARD_IMPORT = "import com.bastion.app.logging.*"
WRAPPER_BASENAME = "SwallowedExceptionLogger.kt"

BLACKLIST_PATH_SEGMENTS = ("/test/", "/androidtest/", "/build/", "/generated/")


# --------------------------------------------------------------------------- #
# 词法扫描：块注释 / 行注释 / 普通字符串 / 原始字符串 状态机 + token 替换
# --------------------------------------------------------------------------- #
def _is_word_start(text: str, i: int) -> bool:
    """判断位置 i 是否处于一个标识符的起始（左侧不是字母/数字/下划线）。"""
    if i <= 0:
        return True
    ch = text[i - 1]
    return not (ch.isalnum() or ch == "_")


def _is_call_follow(text: str, k: int) -> bool:
    """
    判断 ``runCatching`` 之后是否构成一次“调用”：
    允许空白（空格 / 制表 / 换行，兼容 lambda 换行的写法）之后紧跟 ``(`` 或 ``{``。
    """
    j = k
    n = len(text)
    while j < n and text[j] in (" ", "\t", "\n", "\r"):
        j += 1
    if j >= n:
        return False
    return text[j] in ("(", "{")


def transform(text: str):
    """
    对单文件文本做 token 替换。

    返回 (new_text, call_count, label_count, kotlin_count)
      * call_count     : 普通 + FQN 调用替换总数（语义上 = 被观测的吞异常点）
      * label_count    : ``@runCatching`` 标签替换数（为保持可编译必须同步改名）
      * kotlin_count   : 其中 ``kotlin.runCatching`` 全限定形式的数量
    """
    n = len(text)
    out: list[str] = []
    i = 0
    block = False   # /* ... */
    raw = False     # """ ... """

    call_count = 0
    label_count = 0
    kotlin_count = 0

    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""

        # ---- 块注释 /* ... */ ----
        if block:
            out.append(c)
            if c == "*" and nxt == "/":
                # 修复：必须把被跳过的 "/" 也写进输出，否则 "*/" 会丢 "/"，
                # 变成 "*"，导致块注释无法闭合、后续代码被吞进注释。
                out.append(nxt)
                block = False
                i += 2
                continue
            i += 1
            continue

        # ---- 原始字符串 """ ... """ ----
        if raw:
            out.append(c)
            if c == '"' and nxt == '"' and i + 2 < n and text[i + 2] == '"':
                raw = False
                out.append('""')
                i += 3
                continue
            i += 1
            continue

        # ---- 普通字符串 " ... " ----
        if c == '"':
            # 优先识别三引号原始字符串
            if nxt == '"' and i + 2 < n and text[i + 2] == '"':
                raw = True
                out.append('"""')
                i += 3
                continue
            out.append(c)
            i += 1
            while i < n:
                c2 = text[i]
                out.append(c2)
                if c2 == "\\":
                    if i + 1 < n:
                        out.append(text[i + 1])
                        i += 2
                        continue
                    i += 1
                    break
                if c2 == '"':
                    i += 1
                    break
                if c2 == "\n":
                    # 未闭合（合法 Kotlin 普通字符串不会跨行）—— 当作字符串意外结束，停止
                    break
                i += 1
            continue

        # ---- 注释起始 ----
        if c == "/" and nxt == "*":
            block = True
            out.append(c)
            out.append(nxt)
            i += 2
            continue
        if c == "/" and nxt == "/":
            # 行注释：原文照搬（含可能的 runCatching 文本），不做替换
            j = i
            while j < n and text[j] != "\n":
                j += 1
            out.append(text[i:j])
            i = j
            continue

        # ---- 代码区 ----
        # 1) 全限定调用 kotlin.runCatching { / (
        if text.startswith("kotlin.runCatching", i) and _is_word_start(text, i):
            k = i + len("kotlin.runCatching")
            if _is_call_follow(text, k):
                out.append("runCatchingObserved")
                i = k
                kotlin_count += 1
                call_count += 1
                continue

        # 2) lambda 标签 @runCatching（return@runCatching 等）
        if text.startswith("@runCatching", i):
            after = i + len("@runCatching")
            if after >= n or (not text[after].isalnum() and text[after] != "_"):
                out.append("@runCatchingObserved")
                i = after
                label_count += 1
                continue

        # 3) 普通调用 runCatching { / (
        if text.startswith("runCatching", i) and _is_word_start(text, i):
            prev = text[i - 1] if i > 0 else ""
            # 排除 .runCatching（已由 kotlin. 分支处理）/ @runCatching（标签分支）/
            # ::runCatching（方法引用，不应替换）
            if prev not in (".", "@", ":"):
                k = i + len("runCatching")
                if _is_call_follow(text, k):
                    out.append("runCatchingObserved")
                    i = k
                    call_count += 1
                    continue

        # 默认：照抄字符
        out.append(c)
        i += 1

    return "".join(out), call_count, label_count, kotlin_count


# --------------------------------------------------------------------------- #
# import 注入
# --------------------------------------------------------------------------- #
def inject_import(text: str, uses_crlf: bool):
    """
    在 ``package`` 行之后注入目标 import。

    返回 (content, injected, failed)
      * injected : 是否成功注入
      * failed   : 是否因缺少 package 行而失败（仍返回原文本）
    已含目标 import 或通配 import 时直接返回原文本（injected=False, failed=False）。
    """
    lines = text.split("\n")

    if any(l.strip() == TARGET_IMPORT for l in lines):
        return text, False, False
    if any(l.strip() == WILDCARD_IMPORT for l in lines):
        # 通配已覆盖，无需注入
        return text, False, False

    pkg_idx = None
    for idx, line in enumerate(lines):
        if line.startswith("package "):
            pkg_idx = idx
            break
    if pkg_idx is None:
        return text, False, True

    import_line = TARGET_IMPORT
    if uses_crlf:
        import_line += "\r"

    insert_at = pkg_idx + 1
    if insert_at < len(lines) and lines[insert_at].strip() == "":
        # 已有空行，插入到空行之后，避免双空行
        new_lines = lines[:insert_at + 1] + [import_line] + lines[insert_at + 1:]
    else:
        new_lines = lines[:insert_at] + ["", import_line] + lines[insert_at:]

    return "\n".join(new_lines), True, False


# --------------------------------------------------------------------------- #
# 黑名单判定
# --------------------------------------------------------------------------- #
def is_blacklisted(abs_path: str) -> bool:
    norm = abs_path.replace("\\", "/")
    base = os.path.basename(abs_path)

    if base == WRAPPER_BASENAME:
        return True
    if base.endswith(".gradle") or base.endswith(".kts"):
        return True
    # 路径中出现这些片段即跳过（test / androidTest / build / generated）
    for seg in BLACKLIST_PATH_SEGMENTS:
        if seg in norm:
            return True
    return False


def already_imported(text: str) -> bool:
    return TARGET_IMPORT in text


# --------------------------------------------------------------------------- #
# 主流程
# --------------------------------------------------------------------------- #
def main() -> int:
    parser = argparse.ArgumentParser(description="codemod: runCatching -> runCatchingObserved")
    parser.add_argument(
        "--apply", action="store_true",
        help="真正落地改写文件；不传则只做 dry-run（打印统计，不写文件）",
    )
    args = parser.parse_args()

    dry_run = not args.apply

    if not os.path.isdir(SCAN_ROOT):
        print(f"[FATAL] 扫描根目录不存在: {SCAN_ROOT}", file=sys.stderr)
        return 2

    files_changed = 0
    occurrences_replaced = 0
    kotlin_fqn_converted = 0
    label_refs_renamed = 0
    skipped_blacklist = 0
    inject_failed: list[str] = []
    changed_files: list[str] = []

    for root, _dirs, files in os.walk(SCAN_ROOT):
        for name in files:
            if not name.endswith(".kt"):
                continue
            abs_path = os.path.join(root, name)

            if is_blacklisted(abs_path):
                skipped_blacklist += 1
                continue
            # 幂等：已含目标 import 的文件跳过
            with open(abs_path, "r", encoding="utf-8", newline="") as fh:
                original = fh.read()
            if already_imported(original):
                skipped_blacklist += 1
                continue

            uses_crlf = "\r\n" in original
            new_text, call_count, label_count, kotlin_count = transform(original)
            replaced_total = call_count + label_count

            if replaced_total == 0:
                # 本文件中的 runCatching 仅在注释/字符串里，无需改动
                continue

            files_changed += 1
            occurrences_replaced += replaced_total
            kotlin_fqn_converted += kotlin_count
            label_refs_renamed += label_count
            changed_files.append(abs_path)

            # import 注入（dry-run 也计算，用于预测 injectFailed）
            final_text, injected, failed = inject_import(new_text, uses_crlf)
            if failed:
                inject_failed.append(abs_path)

            if dry_run:
                continue

            with open(abs_path, "w", encoding="utf-8", newline="") as fh:
                fh.write(final_text)

    manifest = {
        "filesChanged": files_changed,
        "occurrencesReplaced": occurrences_replaced,
        "kotlinFqnConverted": kotlin_fqn_converted,
        "labelReferencesRenamed": label_refs_renamed,
        "skippedBlacklist": skipped_blacklist,
        "injectFailed": [os.path.relpath(p, REPO_ROOT) for p in inject_failed],
        "dryRun": dry_run,
        "scanRoot": os.path.relpath(SCAN_ROOT, REPO_ROOT),
    }

    manifest_path = os.path.join(SCRIPT_DIR, "codemod_manifest.json")
    with open(manifest_path, "w", encoding="utf-8", newline="") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    # ---- 控制台报告 ----
    print("=" * 64)
    print(f"codemod_run_catching  {'[DRY-RUN]' if dry_run else '[APPLY]'}")
    print("=" * 64)
    print(f"  扫描根目录        : {manifest['scanRoot']}")
    print(f"  改动文件数         : {files_changed}")
    print(f"  替换 token 总数    : {occurrences_replaced}")
    print(f"    - 调用点替换     : {occurrences_replaced - label_refs_renamed}")
    print(f"        (其中 FQN)   : {kotlin_fqn_converted}")
    print(f"    - 标签改名       : {label_refs_renamed}")
    print(f"  跳过(黑名单/幂等) : {skipped_blacklist}")
    print(f"  import 注入失败    : {len(inject_failed)}")
    for p in inject_failed:
        print(f"      ! {p}")
    print(f"  manifest          : {os.path.relpath(manifest_path, REPO_ROOT)}")
    print("=" * 64)

    if dry_run:
        print("DRY-RUN：未写入任何文件。确认无误后加 --apply 落地。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
