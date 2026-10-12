package com.puretv.player.core.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 时间格式化工具（EPG 时间轴、播放进度、节目预告通用） */
object TimeUtils {

    private val hourMinute = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val monthDay = SimpleDateFormat("MM-dd", Locale.getDefault())
    private val fullTime = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    /** HH:mm */
    fun formatClock(timeMs: Long): String = hourMinute.format(timeMs)

    /** MM-dd */
    fun formatMonthDay(timeMs: Long): String = monthDay.format(timeMs)

    /** MM-dd HH:mm */
    fun formatDateTime(timeMs: Long): String = fullTime.format(timeMs)

    /** 播放进度 01:23:45 / 12:34 */
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** 节目进度百分比（EPG 时间轴预览） */
    fun progressPercent(startAt: Long, endAt: Long, now: Long = System.currentTimeMillis()): Int {
        if (endAt <= startAt) return 0
        return (((now - startAt) * 100) / (endAt - startAt)).coerceIn(0, 100).toInt()
    }

    /** 距开播剩余描述 */
    fun untilStart(startAt: Long, now: Long = System.currentTimeMillis()): String {
        val diff = startAt - now
        return when {
            diff <= 0 -> "正在播出"
            diff < 60_000 -> "即将开播"
            diff < 3_600_000 -> "${diff / 60_000} 分钟后开播"
            diff < 86_400_000 -> "${diff / 3_600_000} 小时后开播"
            else -> "${diff / 86_400_000} 天后开播"
        }
    }

    /** 今天 0 点时间戳 */
    fun startOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** 相对今天/昨天/星期描述（播放记录列表） */
    fun formatRelativeDay(timeMs: Long): String {
        val today = startOfToday()
        val diffDays = ((today - timeMs) / 86_400_000L).toInt()
        return when {
            timeMs >= today -> "今天 ${formatClock(timeMs)}"
            diffDays == 1 -> "昨天 ${formatClock(timeMs)}"
            diffDays in 2..6 -> "周" + "日一二三四五六"[Calendar.getInstance()
                .apply { timeInMillis = timeMs }
                .get(Calendar.DAY_OF_WEEK) - 1] + " ${formatClock(timeMs)}"
            else -> formatDateTime(timeMs)
        }
    }
}
