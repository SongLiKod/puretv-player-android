package com.puretv.player.data.prefs

/**
 * 影视搜索历史的纯逻辑（与 DataStore 解耦，便于单元测试）：
 * - [decode]/[encode]：'\n' 分隔的持久化字符串 ↔ 有序列表（最近在前）；
 * - [add]：置顶去重（重复搜的词回到最前）、空白词忽略、超上限截断；
 * - [remove]：单条精确删除。
 *
 * 持久化读写见 [SettingsRepository.vodSearchHistory]。
 */
internal object SearchHistoryCodec {

    /** 搜索历史保留条数上限 */
    const val LIMIT = 20

    /** 解码持久化字符串：丢弃空行；null/空串 → 空列表 */
    fun decode(raw: String?): List<String> =
        raw?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    /** 编码为持久化字符串（最近在前的顺序保持不变） */
    fun encode(words: List<String>): String = words.joinToString("\n")

    /**
     * 记入一条搜索历史：置顶去重 + 上限截断。
     * 空白词原样返回（不产生变更）。
     */
    fun add(current: List<String>, query: String, limit: Int = LIMIT): List<String> {
        val word = query.trim()
        if (word.isEmpty()) return current
        return (listOf(word) + current.filter { it != word }).take(limit)
    }

    /** 删除一条搜索历史（精确匹配；不存在则原样返回） */
    fun remove(current: List<String>, query: String): List<String> =
        current.filterNot { it == query }
}
