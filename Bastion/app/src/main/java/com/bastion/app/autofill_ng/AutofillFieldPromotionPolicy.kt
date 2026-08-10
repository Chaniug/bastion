package com.bastion.app.autofill_ng

/**
 * 字段结构化提升策略。
 *
 * 对齐 bitwarden `AutofillParserImpl.updateForMissingUsernameFields` 的判据：不靠精度分数，
 * 而是靠字段之间的**结构关系**来认定账号框。
 *
 * 背景：WebView 登录页（Via 等轻量浏览器）会把账号框和密码框都报成
 * `TYPE_TEXT_VARIATION_WEB_EDIT_TEXT`，两者都没有任何可用的 hint / 术语信号。密码框还能靠
 * password 术语找回，账号框则完全无迹可循——唯一可靠的线索是「它就在密码框上面」。
 *
 * 早期实现把这类无信号字段一律兜底成 `USERNAME:LOWEST`，等于用「弱肯定」表达「未知」，
 * 导致孤立的搜索栏也被当成低置信账号框放行（京东搜索栏误弹密码条目）。本策略改为：
 * 无信号字段先归入 UNKNOWN（明确的否定），仅在满足结构条件时才提升为账号框。
 */
internal object AutofillFieldPromotionPolicy {

    /**
     * 参与提升判定所需的最小字段信息。刻意不依赖解析器内部模型，以便纯 JVM 单测。
     */
    data class Candidate(
        /** 是否为密码类字段（PASSWORD / NEW_PASSWORD） */
        val isPassword: Boolean,
        /** 是否为账号类字段（USERNAME / EMAIL_ADDRESS / PHONE_NUMBER） */
        val isAccount: Boolean,
        /** 是否为未识别字段（UNKNOWN），即可提升的候选来源 */
        val isUnknown: Boolean,
        /** 字段当前是否可见 */
        val isVisible: Boolean,
        /** 节点遍历序号，用于确定「上下相邻」关系 */
        val traversalIndex: Int,
    )

    /**
     * 表示「无候选可提升」的返回值。
     */
    const val NO_PROMOTION: Int = -1

    /**
     * 在 [candidates] 中挑出应被提升为账号框的那一个，返回其在列表中的下标；
     * 不满足提升条件时返回 [NO_PROMOTION]。
     *
     * 提升需同时满足三个条件，缺一不可：
     * 1. **存在密码框** —— 孤立的搜索栏没有密码框相伴，因此永远不会被提升；
     * 2. **尚无账号框** —— 已经识别出账号框时不再画蛇添足；
     * 3. **位于首个密码框之前且离它最近** —— 只认这一个，不波及页面上其它文本框。
     *
     * 另要求候选可见，避免把已隐藏的历史输入框错认成当前账号框（与账号类字段的
     * 可见性要求保持一致）。
     */
    fun selectUsernameNeighborIndex(candidates: List<Candidate>): Int {
        val firstPasswordIndex = candidates
            .filter { it.isPassword }
            .minOfOrNull { it.traversalIndex }
            ?: return NO_PROMOTION

        if (candidates.any { it.isAccount }) return NO_PROMOTION

        var bestIndex = NO_PROMOTION
        var bestTraversalIndex = Int.MIN_VALUE
        candidates.forEachIndexed { index, candidate ->
            if (!candidate.isUnknown) return@forEachIndexed
            if (!candidate.isVisible) return@forEachIndexed
            if (candidate.traversalIndex >= firstPasswordIndex) return@forEachIndexed
            if (candidate.traversalIndex > bestTraversalIndex) {
                bestTraversalIndex = candidate.traversalIndex
                bestIndex = index
            }
        }
        return bestIndex
    }
}
