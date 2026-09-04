package com.messaging.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.messaging.data.datasource.local.MessagingDatabase
import com.messaging.data.datasource.local.ChatDao
import com.messaging.data.datasource.local.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * AppModule - Provides application-level dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE chats ADD COLUMN otherBio TEXT")
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE chats ADD COLUMN isBlockedByMe INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Singleton
    @Provides
    fun provideDatabase(
        @ApplicationContext context: Context
    ): MessagingDatabase {
        return Room.databaseBuilder(
            context,
            MessagingDatabase::class.java,
            "messaging_db"
        ).addMigrations(MIGRATION_3_4, MIGRATION_4_5).fallbackToDestructiveMigration().build()
    }

    @Singleton
    @Provides
    fun provideChatDao(database: MessagingDatabase): ChatDao {
        return database.chatDao()
    }

    @Singleton
    @Provides
    fun provideMessageDao(database: MessagingDatabase): MessageDao {
        return database.messageDao()
    }
}
