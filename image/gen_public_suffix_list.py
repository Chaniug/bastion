#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 Kotlin 版公共后缀表（Public Suffix List）。

背景：自动填充的条目匹配需要把 host 归约到"基域"(registrable domain)。
原先 `BitwardenLikeAutofillMatcherNg.extractBaseDomain` 只硬编码了 12 条双段后缀
（co.uk/com.cn/...），连 `org.uk` 都没覆盖，遇到 com.br / co.kr / com.hk / github.io
等就会把基域算错，导致**跨站误匹配**（a.example.org.uk 与 b.example.org.uk 被当成同一站点）。
Bitwarden 用 ResourceCacheManager 加载 PSL；本项目离线优先，改为编译期嵌入精简表。

取舍：
- 只取 **2~3 段** 规则（ICANN + PRIVATE），4 段以上（s3.us-east-1.amazonaws.com 等）
  极罕见，放弃以控体积；单段 TLD（com/net/org）不需要入库，默认按 1 段处理即可。
- 去掉通配符规则（含 *）与例外规则（以 ! 开头）。
- JVM class 文件对单个字符串常量有 64KB 上限，故按 40KB 切成多个片段。

用法：python gen_public_suffix_list.py
"""
import os
import urllib.request

PSL_URL = "https://publicsuffix.org/list/public_suffix_list.dat"
OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..",
    "Bastion",
    "app",
    "src",
    "main",
    "java",
    "com",
    "bastion",
    "app",
    "autofill_ng",
    "PublicSuffixList.kt",
)
CHUNK_LIMIT = 40_000  # 每个字符串常量上限（字节，留足 64KB 余量）


def fetch_rules():
    data = urllib.request.urlopen(PSL_URL, timeout=60).read().decode("utf-8")
    rules = []
    for line in data.splitlines():
        s = line.strip()
        if not s or s.startswith("//"):
            continue
        # 排除通配符与例外规则
        if "*" in s or s.startswith("!"):
            continue
        labels = s.split(".")
        # 只要 2~3 段（1 段走默认，4 段以上罕见）
        if not (2 <= len(labels) <= 3):
            continue
        if any(not l for l in labels):
            continue
        rules.append(s.lower())
    # 去重并稳定排序，保证生成结果可复现
    return sorted(set(rules))


def chunk_rules(rules):
    chunks = []
    cur = []
    cur_len = 0
    for r in rules:
        add = len(r.encode("utf-8")) + 1
        if cur_len + add > CHUNK_LIMIT and cur:
            chunks.append("\n".join(cur))
            cur = []
            cur_len = 0
        cur.append(r)
        cur_len += add
    if cur:
        chunks.append("\n".join(cur))
    return chunks


def render(rules, chunks):
    chunk_literals = ",\n".join('"""%s"""' % c for c in chunks)
    return '''package com.bastion.app.autofill_ng

import java.util.Locale

/**
 * 公共后缀表（Public Suffix List）精简版 —— 自动填充条目匹配的基域归约用。
 *
 * **本文件由 `image/gen_public_suffix_list.py` 生成，请勿手工修改。**
 *
 * 数据源：https://publicsuffix.org/list/public_suffix_list.dat
 * 收录范围：ICANN + PRIVATE 两个分区中 **2~3 段** 的规则（已去通配符与例外规则），
 * 共 %d 条。单段 TLD（com/net/org...）不入库（默认按 1 段后缀处理即可）；
 * 4 段以上规则（s3.us-east-1.amazonaws.com 等）极罕见，为控体积未收录。
 *
 * 为什么需要它：只看"最后两段"会把 `org.uk`、`com.br`、`co.kr`、`github.io` 这类
 * 算错，导致 `a.example.org.uk` 与 `b.example.org.uk` 被误判为同一基域而交叉匹配。
 * Bitwarden 通过 `ResourceCacheManager` 加载同一份 PSL 做
 * `getDomainOrNull()`，此处为离线可用的编译期等价实现。
 *
 * 为什么按片段切分：JVM class 文件对单个字符串常量有 64KB 上限，
 * 整表约 %d KB，必须切成多段。
 */
internal object PublicSuffixList {

    private val CHUNKS = arrayOf(
%s,
    )

    private val suffixes: Set<String> by lazy {
        val out = HashSet<String>(%d)
        for (chunk in CHUNKS) {
            for (line in chunk.split('\\n')) {
                val s = line.trim()
                if (s.isNotEmpty()) out.add(s)
            }
        }
        out
    }

    /** 收录规则中最长的段数（2 或 3），决定匹配时的回溯深度。 */
    private const val MAX_SUFFIX_LABELS = 3

    /**
     * 归约 [host] 到基域（public suffix + 1 段）。
     *
     * 例：`www.example.co.uk` → `example.co.uk`；`a.example.org.uk` → `example.org.uk`；
     * `user.github.io` → `user.github.io`（github.io 是 PRIVATE 后缀，故各用户互不相等）。
     *
     * host 本身就是基域或为后缀时原样返回。
     */
    fun baseDomain(host: String): String {
        val normalized = host.trim().trim('.').lowercase(Locale.ROOT)
        if (normalized.isBlank()) return normalized
        // IP 字面量不做归约：`192.168.1.1` 若参与归约会变成 "1.1"，
        // 于是 `10.0.1.1` 之类会与它误判为同一基域。
        if (isIpLiteral(normalized)) return normalized
        val labels = normalized.split('.').filter { it.isNotBlank() }
        if (labels.isEmpty()) return normalized

        val suffixLabels = matchSuffixLabels(labels)
        val registrable = suffixLabels + 1
        return if (labels.size > registrable) {
            labels.takeLast(registrable).joinToString(".")
        } else {
            normalized
        }
    }

    /**
     * 返回命中的公共后缀段数；未命中任何规则时按 1 段处理（普通 TLD）。
     * 至少留 1 段给可注册域名，故回溯上界为 `labels.size - 1`。
     */
    private fun matchSuffixLabels(labels: List<String>): Int {
        val maxProbe = minOf(MAX_SUFFIX_LABELS, labels.size - 1)
        for (n in maxProbe downTo 1) {
            if (labels.takeLast(n).joinToString(".") in suffixes) return n
        }
        return 1
    }

    /** 判断 [host] 是否为 IPv4 / IPv6 字面量。 */
    private fun isIpLiteral(host: String): Boolean {
        // IPv6 字面量必然含 ':'
        if (host.contains(':')) return true
        val labels = host.split('.')
        if (labels.size != 4) return false
        return labels.all { label ->
            label.isNotEmpty() &&
                label.length <= 3 &&
                label.all { it.isDigit() } &&
                label.toInt() <= 255
        }
    }
}
''' % (
        len(rules),
        sum(len(c.encode("utf-8")) for c in chunks) // 1024,
        chunk_literals,
        int(len(rules) * 1.4),
    )


def main():
    rules = fetch_rules()
    chunks = chunk_rules(rules)
    out = os.path.abspath(OUT)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(render(rules, chunks))
    print("rules:", len(rules))
    print("chunks:", len(chunks), [len(c.encode('utf-8')) for c in chunks])
    print("written:", out)


if __name__ == "__main__":
    main()
