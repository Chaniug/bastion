#!/usr/bin/env python3
"""作用域感知地分析 Kotlin 文件中哪些 `X.getString(...)` 可以安全替换为 `stringResource(...)`。

安全判定（依据 docs/lint债务清理计划.md §Phase 3 执行记录的抽取方法论）：
  1. 调用点必须在 @Composable 函数体内；
  2. 从函数体到调用点的**每一层花括号块**都必须是"可组合上下文"：
     - 控制流块（if/else/when/for/while/do/try/catch/finally）→ 安全
     - inline 作用域函数（run/apply/let/also/with/repeat）→ 安全
     - 独立块 → 视为不安全（保守）
  3. 以下为非可组合上下文，命中即判定不安全：
     - 任何 `->` lambda（onClick / 回调 / `val x = { }` 等）
     - remember / LaunchedEffect / DisposableEffect / SideEffect / produceState
       / derivedStateOf / snapshotFlow 的 lambda
     - 作为普通参数传入的 lambda（`foo({ })` 或 `foo(a, { })`）
  4. 只替换"直接参数值"形态（Text(...) / label= / title= 等），
     排除状态赋值 `x = context.getString(...)`（保守，留人工）。

用法：
    python3 analyze_scope.py <文件相对 Bastion/app/src/main/java 的路径>
    python3 analyze_scope.py <文件> --json    # 输出机器可读结果
"""
import json
import os
import re
import sys

# 相对定位：本脚本位于 <项目>/Bastion/tools/lint/，向上三级到 Bastion/app/src/main/java
ROOT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "java"))

CONTROL = {
    "if", "else", "when", "for", "while", "do", "try", "catch", "finally",
    "run", "runCatching", "apply", "let", "also", "with", "repeat",
}
# 已知的非可组合 lambda（这些 lambda 里不能调用 @Composable 函数）
NON_COMPOSABLE = {
    "remember", "rememberSaveable", "LaunchedEffect", "DisposableEffect",
    "SideEffect", "produceState", "derivedStateOf", "snapshotFlow",
    "rememberCoroutineScope", "rememberUpdatedState", "rememberLauncherForActivityResult",
    "animateFloatAsState", "animateDpAsState", "animateColorAsState",
    "animateIntAsState", "animateIntOffsetAsState", "infiniteTransition",
    "buildList", "buildString", "buildMap", "launch", "withContext", "runBlocking",
    # ⚠️ BackHandler 本身是 @Composable 函数，但它的 trailing lambda 是
    #    onBack: () -> Unit —— 不是可组合上下文。Dialog / AlertDialog /
    #    ModalBottomSheet / Popup 的 content lambda 反而是 @Composable，不能加。
    "BackHandler",
    "apply", "also", "let",   # apply/also/let 是 inline，但挂这里更保守（常见于非 UI 上下文）
}

# Compose / Material3 内置：trailing lambda 是 @Composable 上下文
BUILTIN_COMPOSABLE = {
    # 布局
    "Row", "Column", "Box", "BoxWithConstraints", "Spacer", "Layout", "SubcomposeLayout",
    "FlowRow", "FlowColumn", "ConstraintLayout", "MotionLayout",
    # Material3 组件
    "Text", "Button", "Icon", "IconButton", "IconToggleButton", "OutlinedButton",
    "TextButton", "ElevatedButton", "FilledTonalButton", "Card", "ElevatedCard",
    "OutlinedCard", "FilledCard", "Surface", "Divider", "HorizontalDivider",
    "VerticalDivider", "Scaffold", "TopAppBar", "CenterAlignedTopAppBar",
    "MediumTopAppBar", "LargeTopAppBar", "BottomAppBar", "NavigationBar",
    "NavigationBarItem", "NavigationRail", "NavigationRailItem", "FloatingActionButton",
    "SmallFloatingActionButton", "LargeFloatingActionButton",
    "ExtendedFloatingActionButton", "ListItem", "DropdownMenu", "DropdownMenuItem",
    "ExposedDropdownMenuBox", "ExposedDropdownMenu", "AlertDialog", "BasicAlertDialog",
    "Dialog", "Snackbar", "SnackbarHost", "Tab", "TabRow", "LeadingIconTab",
    "ScrollableTabRow", "PrimaryTabRow", "SecondaryTabRow", "PrimaryScrollableTabRow",
    "Checkbox", "TriStateCheckbox", "Switch", "Slider", "RangeSlider", "RadioButton",
    "TextField", "OutlinedTextField", "BasicTextField", "SecureTextField",
    "OutlinedSecureTextField", "LinearProgressIndicator", "CircularProgressIndicator",
    "Chip", "FilterChip", "InputChip", "AssistChip", "SuggestionChip",
    "ElevatedFilterChip", "ElevatedAssistChip", "ElevatedSuggestionChip",
    "Badge", "BadgedBox", "TooltipBox", "RichTooltip", "PlainTooltip",
    "SearchBar", "DockedSearchBar", "SegmentedButton",
    "SingleChoiceSegmentedButtonRow", "MultiChoiceSegmentedButtonRow",
    "TimePicker", "TimeInput", "DatePicker", "DatePickerDialog", "DateRangePicker",
    # 容器 / 主题 / 副作用容器
    "MaterialTheme", "CompositionLocalProvider", "ProvideTextStyle", "ProvideContentColorTextStyle",
    "ModalBottomSheet", "ModalDrawerSheet", "ModalNavigationDrawer",
    "PermanentNavigationDrawer", "PermanentDrawerSheet", "DismissibleNavigationDrawer",
    "DismissibleDrawerSheet", "PullToRefreshBox", "HorizontalPager", "VerticalPager",
    "AnimatedVisibility", "AnimatedContent", "Crossfade", "Canvas", "Image",
    "AsyncImage", "SubcomposeAsyncImage", "BasicText", "SelectionContainer",
    "DisableSelection", "Popup", "BackHandler", "DropdownMenuContent",
    # Lazy 作用域（items/item 的 lambda 是 @Composable）
    "LazyColumn", "LazyRow", "LazyVerticalGrid", "LazyHorizontalGrid",
    "LazyVerticalStaggeredGrid", "LazyHorizontalStaggeredGrid",
    "items", "itemsIndexed", "item", "stickyHeader",
    # 常见自定义容器名（本项目的 Section 系列由其自身 @Composable 收集覆盖）
}


def collect_project_composables():
    """扫描项目源码，收集所有 @Composable 函数名（供 trailing lambda 判定使用）。"""
    names = set()
    for dirpath, _, files in os.walk(ROOT):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            p = os.path.join(dirpath, fn)
            try:
                txt = open(p, encoding="utf-8").read()
            except Exception:
                continue
            # 只在代码区匹配（粗略跳过注释行）
            for m in re.finditer(r"@Composable(?![A-Za-z0-9_]).{0,4000}?\bfun\s+(\w+)", txt, re.S):
                seg = m.group(0)
                # 排除掉明显跨函数误匹配（中间不应出现 'fun ' 之外的函数体开始）
                if seg.count("fun ") > 1:
                    continue
                names.add(m.group(1))
    return names


_PROJECT_COMPOSABLES = None


def is_composable_call(name):
    global _PROJECT_COMPOSABLES
    if _PROJECT_COMPOSABLES is None:
        _PROJECT_COMPOSABLES = collect_project_composables()
    return name in BUILTIN_COMPOSABLE or name in _PROJECT_COMPOSABLES


def scan(src):
    """词法扫描，返回 (braces, code_mask)。
    braces: [{pos, close, depth, head}]  head 为该 '{' 前的关键 token 描述
    code_mask: 长度 len(src) 的 bytearray，1=代码区，0=注释/字符串字面量内容
    """
    n = len(src)
    code = bytearray([1]) * n
    i = 0
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            j = n if j == -1 else j
            for k in range(i, j):
                code[k] = 0
            i = j
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            j = n if j == -1 else j + 2
            for k in range(i, j):
                code[k] = 0
            i = j
            continue
        if src[i:i + 3] == '"""':
            j = src.find('"""', i + 3)
            j = n if j == -1 else j + 3
            for k in range(i, j):
                code[k] = 0
            i = j
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    j += 1
                    break
                if src[j] == "\n":
                    break
                j += 1
            for k in range(i, min(j, n)):
                code[k] = 0
            # 恢复 ${...} 模板插值里的代码区
            k = i
            while k < j:
                if src[k] == "$" and k + 1 < j and src[k + 1] == "{":
                    d, m = 0, k + 1
                    while m < j:
                        if src[m] == "{":
                            d += 1
                        elif src[m] == "}":
                            d -= 1
                            if d == 0:
                                break
                        m += 1
                    for x in range(k, min(m + 1, j)):
                        code[x] = 1
                    k = m + 1
                else:
                    k += 1
            i = j
            continue
        if c == "'":
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "'":
                    j += 1
                    break
                j += 1
            for k in range(i, min(j, n)):
                code[k] = 0
            i = j
            continue
        i += 1

    # 二遍：只在代码区统计花括号与配对
    braces, stack = [], []
    for i, c in enumerate(src):
        if not code[i]:
            continue
        if c == "{":
            braces.append({"pos": i, "depth": len(stack), "head": head_of(src, code, i)})
            stack.append(len(braces) - 1)
        elif c == "}":
            if stack:
                idx = stack.pop()
                braces[idx]["close"] = i
    return braces, code


def prev_nonspace(src, code, i):
    """返回 i 之前最近的（代码区）非空白字符位置，找不到返回 -1。"""
    k = i - 1
    while k >= 0:
        if code[k] and not src[k].isspace():
            return k
        k -= 1
    return -1


def match_paren(src, code, close_pos):
    """给定 ')' 的位置，返回匹配的 '(' 位置。"""
    d = 0
    k = close_pos
    while k >= 0:
        if code[k]:
            if src[k] == ")":
                d += 1
            elif src[k] == "(":
                d -= 1
                if d == 0:
                    return k
        k -= 1
    return -1


def ident_at(src, code, pos):
    """取 pos 处结尾的标识符（含点号），如 `foo`、`foo.bar`。"""
    if pos < 0 or not code[pos]:
        return ""
    k = pos
    while k >= 0 and (src[k].isalnum() or src[k] in "_$."):
        k -= 1
    return src[k + 1:pos + 1]


def classify_call(src, code, p):
    """判断 '{' 前导位置的调用性质。p 为最近的有效字符位置。"""
    if p < 0:
        return ("unknown", "")
    ch = src[p]
    if ch == ")":
        lp = match_paren(src, code, p)
        if lp > 0:
            return classify_call(src, code, prev_nonspace(src, code, lp))
        return ("lambda", "(call)")
    if ch.isalnum() or ch in "_$":
        tok = ident_at(src, code, p).split(".")[-1]
        if tok in CONTROL:
            return ("control", tok)
        if tok in NON_COMPOSABLE:
            return ("noncomposable", tok)
        if is_composable_call(tok):
            return ("composable", tok)
        return ("lambda", tok)
    if ch in ",(":
        return ("arg", ch)
    if ch == "{":
        return ("block", "{")
    if ch in ";}":
        return ("block", ch)
    if ch == "=":
        return ("callback", "=")      # `val x = { }` / `onClick = { }`
    return ("unknown", ch)


def head_of(src, code, brace_pos):
    """判断 '{' 前导 token 的性质，返回 (kind, token)。
    kind: 'composable' | 'control'  → 可组合上下文，安全
          'lambda' | 'noncomposable' | 'arg' | 'callback' | 'block' | 'unknown' → 不安全（保守）
    """
    p = prev_nonspace(src, code, brace_pos)
    if p < 0:
        return ("unknown", "")
    # lambda 参数列表形态 `{ item -> }`：继续向前解析出被调用的函数名
    if src[p] == ">" and p - 1 >= 0 and src[p - 1] == "-":
        return classify_call(src, code, prev_nonspace(src, code, p - 1))
    return classify_call(src, code, p)


def composable_ranges(src, code, braces):
    """返回 @Composable 函数的 body 花括号索引列表。"""
    out = []
    for m in re.finditer(r"@Composable\b", src):
        if not code[m.start()]:
            continue
        # 括号配平地跳过函数签名：参数默认值里可能自带 lambda
        # （如 `onClearAllData: (...) -> Unit = { _, _, _ -> }`），
        # 直接取第一个 '{' 会错误地定位到参数默认值而非函数体。
        j, depth = m.end(), 0
        while j < len(src):
            if not code[j]:
                j += 1
                continue
            c = src[j]
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
            elif c == "{" and depth == 0:
                break
            j += 1
        if j >= len(src):
            continue
        for bi, b in enumerate(braces):
            if b["pos"] == j:
                out.append(bi)
                break
    return out


def analyze(path):
    src = open(path, encoding="utf-8").read()
    braces, code = scan(src)
    line_start = [0]
    for i, c in enumerate(src):
        if c == "\n":
            line_start.append(i + 1)

    def lineno(pos):
        import bisect
        return bisect.bisect_right(line_start, pos)

    comp_bodies = composable_ranges(src, code, braces)
    # 每个花括号块的父块索引
    parent = {}
    for bi, b in enumerate(braces):
        parent[bi] = None
    for bi, b in enumerate(braces):
        for cbi in range(bi + 1, len(braces)):
            if braces[cbi]["pos"] > b["pos"]:
                if braces[cbi]["pos"] < b.get("close", -1):
                    if parent.get(cbi) is None or braces[parent[cbi]]["pos"] < b["pos"]:
                        parent[cbi] = bi
                else:
                    break

    results = []
    for m in re.finditer(r"\b(\w+(?:\.current)?)\.getString\(", src):
        pos = m.start()
        if not code[pos]:
            continue
        base = {"line": lineno(pos), "pos": m.start(), "end": m.end(),
                "match": m.group(0), "code": line_of(src, pos)}
        # 找包含它的最内层块
        container = None
        for bi, b in enumerate(braces):
            if b["pos"] < pos and b.get("close", -1) > pos:
                if container is None or b["pos"] > braces[container]["pos"]:
                    container = bi
        if container is None:
            results.append({**base, "safe": False, "reason": "不在任何块内（文件顶层）"})
            continue

        # 沿着父链向上到最近的 composable body，检查每一层
        chain, cur = [], container
        while cur is not None:
            chain.append(cur)
            if cur in comp_bodies:
                break
            cur = parent.get(cur)
        in_composable = cur is not None

        unsafe = None
        for bi in chain:
            if bi in comp_bodies:
                continue  # body 自身不算
            kind, tok = braces[bi]["head"]
            # ⚠️ 'block'（裸块 `{ ... }`）默认判为不安全。
            # 曾误判为"中性继承外层"，结果放过了 `val x = if (c) { { ... } }` 这类
            # `() -> Unit` 回调 lambda（SettingsScreen L1093-1096），编译报
            # "@Composable invocations can only happen from the context of a
            # @Composable function"。少数确实安全的场景（`topBar = if (c) { { ... } }`，
            # 目标是 @Composable slot）宁可跳过，交由人工确认。
            if kind not in ("control", "composable"):
                unsafe = f"{kind}:{tok}"
                break

        if not in_composable:
            results.append({**base, "safe": False, "reason": "不在 @Composable 函数体内"})
        elif unsafe:
            results.append({**base, "safe": False,
                            "reason": f"嵌套在 {unsafe} 内（非可组合上下文）"})
        else:
            results.append({**base, "safe": True, "reason": "可安全替换",
                            "var": m.group(1)})
    return src, results


def line_of(src, pos):
    a = src.rfind("\n", 0, pos) + 1
    b = src.find("\n", pos)
    b = len(src) if b == -1 else b
    return src[a:b].strip()


if __name__ == "__main__":
    rel = sys.argv[1]
    path = rel if os.path.isabs(rel) else os.path.join(ROOT, rel)
    src, res = analyze(path)
    safe = [r for r in res if r["safe"]]
    unsafe = [r for r in res if not r["safe"]]
    print(f"文件：{path}")
    print(f"getString 调用总数：{len(res)}    可安全替换：{len(safe)}    判定不安全：{len(unsafe)}")
    if "--json" in sys.argv:
        print(json.dumps(res, ensure_ascii=False, indent=2))
        sys.exit(0)
    print("\n=== 判定不安全的原因分布 ===")
    from collections import Counter
    for k, v in Counter(r["reason"].split("（")[0] for r in unsafe).most_common():
        print(f"  {v:>4}  {k}")
    print("\n=== 可安全替换的调用（前 40 条）===")
    for r in safe[:40]:
        print(f"  L{r['line']:<5} {r['code'][:100]}")
    if len(safe) > 40:
        print(f"  ... 还有 {len(safe) - 40} 条")
