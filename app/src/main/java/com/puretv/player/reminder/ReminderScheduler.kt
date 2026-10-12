package com.puretv.player.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.puretv.player.data.local.dao.ReminderDao
import com.puretv.player.data.local.entity.ReminderEntity
import com.puretv.player.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 节目预约调度器（需求 5.3.4：预约播放）。
 * schedule(reminder)：写入 ReminderDao → AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, startAt)
 * 指向 [ReminderReceiver]，extras 携带 reminderId/title/channelName/programId；
 * cancel(id)：撤销闹钟并删除记录。
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminderDao: ReminderDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** 全部预约（按开播时间升序） */
    val reminders: Flow<List<ReminderEntity>> = reminderDao.observeAll().flowOn(io)

    /** 查询某节目是否已预约 */
    suspend fun getByProgramId(programId: String): ReminderEntity? = withContext(io) {
        reminderDao.getByProgramId(programId)
    }

    /** 预约节目：落库并设置精确闹钟，返回带自增 id 的记录 */
    suspend fun schedule(reminder: ReminderEntity): ReminderEntity = withContext(io) {
        val id = reminderDao.upsert(reminder)
        val stored = reminder.copy(id = id)
        setAlarm(stored)
        stored
    }

    /** 取消预约 */
    suspend fun cancel(id: Long) = withContext(io) {
        cancelAlarm(id)
        reminderDao.delete(id)
    }

    /** 按节目取消预约 */
    suspend fun cancelByProgramId(programId: String) = withContext(io) {
        reminderDao.getByProgramId(programId)?.let {
            cancelAlarm(it.id)
            reminderDao.delete(it.id)
        }
    }

    /** 清理已过期预约 */
    suspend fun pruneExpired() = withContext(io) {
        reminderDao.prune(System.currentTimeMillis())
    }

    private fun setAlarm(reminder: ReminderEntity) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = pendingIntent(reminder)
        val triggerAt = reminder.startAt
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (_: SecurityException) {
            // 无精确闹钟权限（Android 12+）：退化为非精确闹钟，保证功能可用不崩溃
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }
    }

    private fun cancelAlarm(id: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        manager.cancel(pendingIntentFor(id))
    }

    private fun pendingIntent(reminder: ReminderEntity): PendingIntent =
        pendingIntentFor(reminder.id, reminder)

    private fun pendingIntentFor(id: Long, reminder: ReminderEntity? = null): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "$ACTION_PREFIX.$id"
            putExtra(EXTRA_REMINDER_ID, id)
            reminder?.let {
                putExtra(EXTRA_TITLE, it.title)
                putExtra(EXTRA_CHANNEL_NAME, it.channelName)
                putExtra(EXTRA_PROGRAM_ID, it.programId)
            }
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, id.toInt(), intent, flags)
    }

    companion object {
        private const val ACTION_PREFIX = "com.puretv.player.action.REMINDER"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_TITLE = "reminder_title"
        const val EXTRA_CHANNEL_NAME = "reminder_channel_name"
        const val EXTRA_PROGRAM_ID = "reminder_program_id"
    }
}
