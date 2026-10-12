package com.puretv.player.di

import androidx.room.Room
import android.content.Context
import com.puretv.player.data.local.PureTvDatabase
import com.puretv.player.data.local.dao.ChannelDao
import com.puretv.player.data.local.dao.ChannelSettingDao
import com.puretv.player.data.local.dao.EpgDao
import com.puretv.player.data.local.dao.FavoriteDao
import com.puretv.player.data.local.dao.HistoryDao
import com.puretv.player.data.local.dao.PluginDao
import com.puretv.player.data.local.dao.ReminderDao
import com.puretv.player.data.local.dao.SourceDao
import com.puretv.player.data.local.dao.VodDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PureTvDatabase =
        Room.databaseBuilder(context, PureTvDatabase::class.java, PureTvDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideChannelDao(db: PureTvDatabase): ChannelDao = db.channelDao()
    @Provides fun provideEpgDao(db: PureTvDatabase): EpgDao = db.epgDao()
    @Provides fun provideFavoriteDao(db: PureTvDatabase): FavoriteDao = db.favoriteDao()
    @Provides fun provideHistoryDao(db: PureTvDatabase): HistoryDao = db.historyDao()
    @Provides fun provideReminderDao(db: PureTvDatabase): ReminderDao = db.reminderDao()
    @Provides fun provideSourceDao(db: PureTvDatabase): SourceDao = db.sourceDao()
    @Provides fun provideChannelSettingDao(db: PureTvDatabase): ChannelSettingDao = db.channelSettingDao()
    @Provides fun provideVodDao(db: PureTvDatabase): VodDao = db.vodDao()
    @Provides fun providePluginDao(db: PureTvDatabase): PluginDao = db.pluginDao()
}
