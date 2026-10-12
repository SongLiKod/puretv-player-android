package com.puretv.player.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.puretv.player.data.local.dao.ChannelDao
import com.puretv.player.data.local.dao.ChannelSettingDao
import com.puretv.player.data.local.dao.EpgDao
import com.puretv.player.data.local.dao.FavoriteDao
import com.puretv.player.data.local.dao.HistoryDao
import com.puretv.player.data.local.dao.PluginDao
import com.puretv.player.data.local.dao.ReminderDao
import com.puretv.player.data.local.dao.SourceDao
import com.puretv.player.data.local.dao.VodDao
import com.puretv.player.data.local.entity.ChannelEntity
import com.puretv.player.data.local.entity.ChannelSettingEntity
import com.puretv.player.data.local.entity.EpgProgramEntity
import com.puretv.player.data.local.entity.FavoriteEntity
import com.puretv.player.data.local.entity.PluginEntity
import com.puretv.player.data.local.entity.ReminderEntity
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.local.entity.VodEpisodeEntity
import com.puretv.player.data.local.entity.VodItemEntity
import com.puretv.player.data.local.entity.WatchHistoryEntity

/**
 * 本地数据库：存储频道列表、播放记录、收藏、EPG缓存、点播缓存、插件状态
 * （技术文档：Room Database）
 */
@Database(
    entities = [
        ChannelEntity::class,
        EpgProgramEntity::class,
        FavoriteEntity::class,
        WatchHistoryEntity::class,
        ReminderEntity::class,
        SourceEntity::class,
        ChannelSettingEntity::class,
        VodItemEntity::class,
        VodEpisodeEntity::class,
        PluginEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class PureTvDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun epgDao(): EpgDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun reminderDao(): ReminderDao
    abstract fun sourceDao(): SourceDao
    abstract fun channelSettingDao(): ChannelSettingDao
    abstract fun vodDao(): VodDao
    abstract fun pluginDao(): PluginDao

    companion object {
        const val NAME = "puretv.db"
    }
}
