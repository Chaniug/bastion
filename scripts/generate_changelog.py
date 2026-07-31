#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成结构化的 Release / Preview 更新日志。

设计目标（对应此前诊断出的问题）：
  1. 按 Conventional Commits 前缀分组：
       feat            -> ✨ 新功能
       fix             -> 🐛 修复
       refactor/perf/  -> 🔧 优化
       optimize
  2. 过滤噪声，绝不进入最终日志：
       - merge 提交（--no-merges 已剔除非快进合并；显式 merge 文案再兜底）
       - revert 提交（"Revert ..."）
       - 纯编译修复（"修复编译错误" / "compile error" / "build fix" 等）
       - 纯补 import（"补充 import" / "add import"）
       - 纯维护类前缀：chore / docs / test / ci / build / style
  3. 剥掉类型前缀与括号 scope，让中文文案可读。
  4. 未带 Conventional 前缀的提交走轻量启发式归类（修复/新功能/优化）。
  5. 每个分组有上限，超出部分折叠为「以及其他 N 项{分组}」，避免日志爆量。
  6. 安全回退：当无法定位上一次基线（prev 为空或 range 非法）时，
     只取最近 FALLBACK_CAP 条提交，绝不倾倒全量历史（旧逻辑会 dump 全部 932 条）。

用法：
  python3 scripts/generate_changelog.py --prev <ref> [--head HEAD] [--repo .] [--self-test]
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys

# ---------------------------------------------------------------------------
# 常量
# ---------------------------------------------------------------------------
SEP = "\x1f"  # 单元分隔符，用于拆分 git 输出中的 subject 与 hash
FALLBACK_CAP = 200          # prev 不可用时最多回看多少条提交（安全上限）
MAX_PER_GROUP = 15          # 每个分组最多展示条数，超出折叠

GROUP_ORDER = [
    ("新功能", "✨"),
    ("修复", "🐛"),
    ("优化", "🔧"),
]
GROUP_LABELS = {label for label, _ in GROUP_ORDER}

# 整条丢弃的噪声（大小写不敏感）
NOISE_RE = re.compile(
    r"^(revert\s)"                                  # 纯 revert
    r"|修复编译错误|编译错误|build error|compile error|修复构建|build fix|fix build"
    r"|补.{0,20}import|补充.{0,20}import|补齐.{0,20}import|缺失.{0,15}import"  # 纯补 import
    r"|编译失败|资源编译失败|kspDebugKotlin|语法错误"                          # 纯编译失败/语法
    r"|merge branch|merge remote|merged?\s"
    , re.IGNORECASE,
)

# 纯维护类前缀：整条丢弃
DROP_PREFIX_RE = re.compile(
    r"^(chore|docs|test|ci|build|style)(\([^)]*\))?(!)?:\s*", re.IGNORECASE,
)

# Conventional 前缀 -> 分组
PREFIX_MAP = [
    (re.compile(r"^feat(\([^)]*\))?(!)?:\s*", re.IGNORECASE), "新功能"),
    (re.compile(r"^fix(\([^)]*\))?(!)?:\s*", re.IGNORECASE), "修复"),
    (re.compile(r"^refactor(\([^)]*\))?(!)?:\s*", re.IGNORECASE), "优化"),
    (re.compile(r"^perf(\([^)]*\))?(!)?:\s*", re.IGNORECASE), "优化"),
    (re.compile(r"^optimize(\([^)]*\))?(!)?:\s*", re.IGNORECASE), "优化"),
]

# 无前缀提交的启发式归类
HEURISTIC_FIX = re.compile(r"修复|fix|bug|闪退|crash|异常|报错", re.IGNORECASE)
HEURISTIC_FEAT = re.compile(r"新增|添加|支持|增加|实现|feature|new|加入|扩充", re.IGNORECASE)

# 用于剥前缀（含 scope 与感叹号）
STRIP_PREFIX_RE = re.compile(
    r"^(feat|fix|refactor|perf|optimize|chore|docs|test|ci|build|style)"
    r"(\([^)]*\))?(!)?:\s*",
    re.IGNORECASE,
)


# ---------------------------------------------------------------------------
# 核心分类 / 清洗逻辑（可独立单测）
# ---------------------------------------------------------------------------
def classify(subject: str):
    """返回分组标签，或 None 表示该条应丢弃。"""
    s = (subject or "").strip()
    if not s:
        return None
    if NOISE_RE.search(s):
        return None
    if DROP_PREFIX_RE.match(s):
        return None
    for rx, label in PREFIX_MAP:
        if rx.match(s):
            return label
    if HEURISTIC_FIX.search(s):
        return "修复"
    if HEURISTIC_FEAT.search(s):
        return "新功能"
    return "优化"


def strip_prefix(subject: str) -> str:
    """剥掉 Conventional 类型前缀，保留可读文案。"""
    s = (subject or "").strip()
    stripped = STRIP_PREFIX_RE.sub("", s)
    return (stripped or s).strip()


def parse_commits(raw: str):
    """将 `git log --pretty=format:'%s<SEP>%h'` 输出转换为 (label, text) 列表。"""
    entries = []
    for line in raw.splitlines():
        if not line.strip():
            continue
        parts = line.split(SEP)
        subject = parts[0] if parts else ""
        label = classify(subject)
        if label is None:
            continue
        entries.append((label, strip_prefix(subject)))
    return entries


def build_notes(entries) -> str:
    """把 (label, text) 列表组装成分组 Markdown。"""
    groups = {label: [] for label in GROUP_LABELS}
    seen = set()
    for label, text in entries:
        if label not in groups:
            label = "优化"
        key = (label, text)
        if key in seen or not text:
            continue
        seen.add(key)
        groups[label].append(text)

    total = sum(len(v) for v in groups.values())
    if total == 0:
        return "- 无变更记录"

    lines: list[str] = []
    for label, icon in GROUP_ORDER:
        items = groups[label]
        if not items:
            continue
        lines.append(f"### {icon} {label}")
        if len(items) > MAX_PER_GROUP:
            for t in items[:MAX_PER_GROUP]:
                lines.append(f"- {t}")
            rest = len(items) - MAX_PER_GROUP
            lines.append(f"- 以及其他 {rest} 项{label}")
        else:
            for t in items:
                lines.append(f"- {t}")
        lines.append("")
    return "\n".join(lines).strip()


# ---------------------------------------------------------------------------
# git 读取
# ---------------------------------------------------------------------------
def read_commits(prev: str, head: str, repo: str):
    """
    返回 (entries, used_spec)。
    优先用 prev..head；不可用/非法时安全回退到最近 FALLBACK_CAP 条。
    """
    specs: list[str] = []
    if prev:
        specs.append(f"{prev}..{head}")
    specs.append(f"-{FALLBACK_CAP}")  # 安全有界回退，绝不倾倒全量历史

    for spec in specs:
        try:
            out = subprocess.run(
                ["git", "-C", repo, "log", "--no-merges",
                 f"--pretty=format:%s{SEP}%h", spec],
                capture_output=True, text=True, check=True,
            ).stdout
            return parse_commits(out), spec
        except subprocess.CalledProcessError:
            continue
    # 极端兜底：连 -N 都失败（理论上不会发生），返回空
    return [], None


# ---------------------------------------------------------------------------
# 内置自检
# ---------------------------------------------------------------------------
def self_test() -> int:
    samples = [
        ("feat(autofill): 新增自动填充权限卡片", "新功能", "新增自动填充权限卡片"),
        ("fix: 修复双 DataStore 闪退", "修复", "修复双 DataStore 闪退"),
        ("refactor(settings): 聚合 83 个委托方法", "优化", "聚合 83 个委托方法"),
        ("perf(ui): 列表滚动更流畅", "优化", "列表滚动更流畅"),
        ("chore: 更新依赖版本", None, None),
        ("docs: 补充 README", None, None),
        ("test: 新增回归测试", None, None),
        ("ci: 调整构建缓存", None, None),
        ("Revert \"feat: 实验性功能\"", None, None),
        ("fix: 修复编译错误", None, None),
        ("补充 import", None, None),
        ("补 SettingsExtraComponents 缺失 import（Toast）", None, None),
        ("修复 AppearanceSelectionSheet 缺失 @OptIn 注解导致编译失败", None, None),
        ("修复 Log 删除后残留的悬空参数行导致语法错误", None, None),
        ("修复自动填充设置页闪退", "修复", "修复自动填充设置页闪退"),
        ("新增暗色主题支持", "新功能", "新增暗色主题支持"),
        ("调整文案措辞", "优化", "调整文案措辞"),
        ("Merge branch 'dev' into main", None, None),
    ]
    failed = 0
    for subject, exp_label, exp_text in samples:
        got_label = classify(subject)
        got_text = strip_prefix(subject) if exp_text is not None else None
        ok = (got_label == exp_label)
        if exp_text is not None:
            ok = ok and (got_text == exp_text)
        status = "OK " if ok else "FAIL"
        if not ok:
            failed += 1
            print(f"[{status}] {subject!r} -> label={got_label} (exp {exp_label})"
                  + (f" text={got_text!r} (exp {exp_text!r})" if exp_text is not None else ""))
        else:
            print(f"[{status}] {subject!r} -> {got_label} / {got_text}")
    # 折叠逻辑自检
    big = [("修复", f"问题{i}") for i in range(MAX_PER_GROUP + 5)]
    notes = build_notes(big)
    assert "以及其他 5 项修复" in notes, f"折叠逻辑异常:\n{notes}"
    assert notes.count("\n- ") >= MAX_PER_GROUP, "展示条数异常"
    # 空输入自检
    assert build_notes([]) == "- 无变更记录"
    # 去重自检
    dedup = build_notes([("修复", "同一条"), ("修复", "同一条")])
    assert dedup.count("同一条") == 1, "去重失败"
    print(f"\nSelf-test: {'ALL PASSED' if failed == 0 else str(failed) + ' FAILED'}")
    return 1 if failed else 0


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------
def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="生成结构化更新日志")
    p.add_argument("--prev", default="", help="上一次基线 ref（tag 或 commit-ish）；为空则安全回退")
    p.add_argument("--head", default="HEAD", help="当前基线 ref，默认 HEAD")
    p.add_argument("--repo", default=".", help="git 仓库根目录")
    p.add_argument("--self-test", action="store_true", help="运行内置自检并退出")
    args = p.parse_args(argv)

    if args.self_test:
        return self_test()

    entries, used_spec = read_commits(args.prev, args.head, args.repo)
    print(build_notes(entries))
    if not args.prev or used_spec != f"{args.prev}..{args.head}":
        sys.stderr.write(
            f"[changelog] prev={args.prev or 'none'} "
            f"used_spec={used_spec or 'none'} (entries={len(entries)})\n"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
