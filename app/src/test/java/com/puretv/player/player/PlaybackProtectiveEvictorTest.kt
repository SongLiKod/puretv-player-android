package com.puretv.player.player

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import java.io.File
import java.util.NavigableSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放保护型 LRU 淘汰器（[PlaybackProtectiveEvictor]）单元测试。
 *
 * 回归背景（用户反馈）：整集已预缓冲，播放中还时不时弹「缓冲中…」——
 * 根因是缓存写满时后台预缓冲下一集的并发写入把本集尚未播到的分片 LRU 淘汰，
 * 播放中途读取回源网络。本淘汰器保证：正在播放集（protectedKeys）的分片永不被淘汰。
 */
class PlaybackProtectiveEvictorTest {

    private fun span(key: String, length: Long, ts: Long, position: Long = 0L) =
        CacheSpan(key, position, length, ts, File("cache.bin"))

    /** 假缓存：镜像 SimpleCache 的回调契约（add/remove 触发淘汰器的 onSpanAdded/Removed） */
    private class FakeCache(private val evictor: PlaybackProtectiveEvictor) : Cache {
        private val spans = linkedMapOf<String, MutableList<CacheSpan>>()
        var space = 0L
            private set

        fun add(span: CacheSpan) {
            spans.getOrPut(span.key) { mutableListOf() }.add(span)
            space += span.length
            evictor.onSpanAdded(this, span)
        }

        /** 模拟 SimpleCache 的 touch：先同步替换存储分片的时间戳，再回调 onSpanTouched */
        fun touch(oldSpan: CacheSpan, newSpan: CacheSpan) {
            val list = spans[oldSpan.key] ?: return
            val idx = list.indexOfFirst {
                it.position == oldSpan.position && it.length == oldSpan.length
            }
            if (idx >= 0) list[idx] = newSpan
            evictor.onSpanTouched(this, oldSpan, newSpan)
        }

        fun keys(): Set<String> = spans.keys.toSet()

        override fun removeSpan(span: CacheSpan) {
            val list = spans[span.key] ?: return
            if (list.remove(span)) {
                space -= span.length
                if (list.isEmpty()) spans.remove(span.key)
                evictor.onSpanRemoved(this, span)
            }
        }

        override fun removeResource(key: String) {
            spans.remove(key)?.toList()?.forEach { evictor.onSpanRemoved(this, it) }
        }

        override fun getCachedBytes(key: String, position: Long, length: Long): Long =
            spans[key].orEmpty()
                .filter { it.position < position + length && it.position + it.length > position }
                .sumOf { it.length }

        override fun getUid(): Long = 0L
        override fun release() = Unit
        override fun addListener(key: String, listener: Cache.Listener): NavigableSet<CacheSpan> =
            throw UnsupportedOperationException()

        override fun removeListener(key: String, listener: Cache.Listener) = Unit
        override fun getCachedSpans(key: String): NavigableSet<CacheSpan> =
            throw UnsupportedOperationException()

        override fun getKeys(): MutableSet<String> = spans.keys.toMutableSet()
        override fun getCacheSpace(): Long = space
        override fun startReadWrite(key: String, position: Long, length: Long): CacheSpan =
            throw UnsupportedOperationException()

        override fun startReadWriteNonBlocking(key: String, position: Long, length: Long): CacheSpan =
            throw UnsupportedOperationException()

        override fun startFile(key: String, position: Long, length: Long): File =
            throw UnsupportedOperationException()

        override fun commitFile(file: File, length: Long) = Unit
        override fun releaseHoleSpan(holeSpan: CacheSpan) = Unit
        override fun isCached(key: String, position: Long, length: Long): Boolean = false
        override fun getCachedLength(key: String, position: Long, length: Long): Long = 0L
        override fun applyContentMetadataMutations(
            key: String,
            mutations: ContentMetadataMutations,
        ) = Unit

        override fun getContentMetadata(key: String): ContentMetadata =
            throw UnsupportedOperationException()
    }

    @Test
    fun underLimitNothingEvicted() {
        val evictor = PlaybackProtectiveEvictor(100)
        val cache = FakeCache(evictor)
        cache.add(span("a", length = 40, ts = 1))
        cache.add(span("b", length = 40, ts = 2))
        assertEquals(setOf("a", "b"), cache.keys())
        assertEquals(80L, cache.space)
    }

    @Test
    fun overflowEvictsLeastRecentlyUsedFirst() {
        val evictor = PlaybackProtectiveEvictor(100)
        val cache = FakeCache(evictor)
        cache.add(span("a", length = 40, ts = 1))
        cache.add(span("b", length = 40, ts = 2))
        cache.add(span("c", length = 40, ts = 3)) // 120 > 100 → 淘汰最旧的 a
        assertEquals(setOf("b", "c"), cache.keys())
        assertEquals(80L, cache.space)
    }

    @Test
    fun protectedKeysAreNeverEvicted() {
        val evictor = PlaybackProtectiveEvictor(100)
        val cache = FakeCache(evictor)
        evictor.protectedKeys = setOf("a") // a = 正在播放的集
        cache.add(span("a", length = 40, ts = 1))
        cache.add(span("b", length = 40, ts = 2))
        cache.add(span("c", length = 40, ts = 3)) // 超限：跳过保护的 a，淘汰次旧的 b
        assertEquals(setOf("a", "c"), cache.keys())
        assertEquals(80L, cache.space)
    }

    @Test
    fun allProtectedAllowsTemporaryOverflowWithoutCrash() {
        val evictor = PlaybackProtectiveEvictor(100)
        val cache = FakeCache(evictor)
        evictor.protectedKeys = setOf("a", "b")
        cache.add(span("a", length = 60, ts = 1))
        cache.add(span("b", length = 60, ts = 2)) // 120 > 100 但候选全是保护键 → 允许超限
        assertEquals(setOf("a", "b"), cache.keys())
        assertEquals(120L, cache.space)
    }

    @Test
    fun touchedSpanIsReorderedToSurviveEviction() {
        val evictor = PlaybackProtectiveEvictor(100)
        val cache = FakeCache(evictor)
        val a1 = span("a", length = 40, ts = 1)
        cache.add(a1)
        cache.add(span("b", length = 40, ts = 2))
        // 播放器读取 a：刷新 a 的访问时间到 3（a 变为最新，b 成为最旧）
        val a2 = span("a", length = 40, ts = 3)
        cache.touch(a1, a2)
        cache.add(span("c", length = 40, ts = 4)) // 超限：最旧现在是 b → 淘汰 b 而非 a
        assertEquals(setOf("a", "c"), cache.keys())
    }

    @Test
    fun onStartFileReservesSpaceForUpcomingWrite() {
        val evictor = PlaybackProtectiveEvictor(100)
        val cache = FakeCache(evictor)
        cache.add(span("a", length = 40, ts = 1))
        // 即将写入 b 的 70 字节：40 + 70 > 100 → 写前先淘汰 a 腾地方
        evictor.onStartFile(cache, "b", 0L, 70L)
        assertEquals(emptySet<String>(), cache.keys())
        assertEquals(0L, cache.space)
    }

    @Test
    fun evictionStopsWhenOnlyProtectedRemains() {
        val evictor = PlaybackProtectiveEvictor(100)
        val cache = FakeCache(evictor)
        evictor.protectedKeys = setOf("playing")
        cache.add(span("playing", length = 80, ts = 1))
        cache.add(span("other", length = 80, ts = 2)) // 超限 → 淘汰 other，playing 保留
        assertEquals(setOf("playing"), cache.keys())
        assertFalse(cache.keys().contains("other"))
        assertTrue(cache.space <= 100)
    }

    @Test
    fun evictionTerminatesWhenCacheMissesVictim() {
        // 回归：账本里的分片已不在缓存内时，removeSpan 不会回调 onSpanRemoved——
        // evict 必须自我收敛终止，绝不允许死循环挂死加载线程（曾致测试挂起 3 分钟+）
        val evictor = PlaybackProtectiveEvictor(100)
        val cache = FakeCache(evictor)
        val ghost = span("ghost", length = 60, ts = 1)
        evictor.onSpanAdded(cache, ghost) // 只进账本，fake 缓存里没有它 → removeSpan 无回调
        cache.add(span("real", length = 60, ts = 2)) // 120 > 100 → 先淘汰 ghost（靠防御摘除收敛）
        assertEquals(setOf("real"), cache.keys())
        assertEquals(60L, cache.space)
    }
}
