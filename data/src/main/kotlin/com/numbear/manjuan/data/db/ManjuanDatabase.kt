package com.numbear.manjuan.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SourceEntity::class, BookEntity::class, ProgressEntity::class, BookmarkEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ManjuanDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun books(): BookDao
    abstract fun progress(): ProgressDao
    abstract fun bookmarks(): BookmarkDao

    companion object {
        fun create(context: Context): ManjuanDatabase =
            Room.databaseBuilder(context.applicationContext, ManjuanDatabase::class.java, "manjuan.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
