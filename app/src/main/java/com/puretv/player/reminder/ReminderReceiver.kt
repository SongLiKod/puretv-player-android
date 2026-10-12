package com.puretv.player.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 节目预约到点提醒接收器（需求 5.3.4：预约播放）。
 * 由 AlarmManager 在节目开始时触发，交由 [ReminderNotifier] 展示通知提醒用户。
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var notifier: ReminderNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1L)
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: return
        val channelName = intent.getStringExtra(ReminderScheduler.EXTRA_CHANNEL_NAME).orEmpty()
        if (reminderId < 0) return

        // 通知构建轻量，直接在主线程完成；内部已处理通知权限缺失
        notifier.notifyProgram(reminderId, title, channelName)
    }
}
