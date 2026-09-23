package com.numbear.manjuan.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val displayName: String,
    val baseUrl: String = "",
    val username: String = "",
    val passwordCipher: String = "",
    val rootPath: String = "",
    val createdAt: Long = 0,
)

@Entity(
    tableName = "books",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceId"), Index(value = ["sourceId", "remotePath"])],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val title: String,
    val author: String = "",
    val format: String,
    val kind: String,
    val location: String,
    val remotePath: String = "",
    val cachedPath: String = "",
    val addedAt: Long = 0,
    val lastOpenedAt: Long = 0,
    val sizeBytes: Long = 0,
)

@Entity(
    tableName = "progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProgressEntity(
    @PrimaryKey val bookId: Long,
    val locator: String,
    val percent: Float,
    val updatedAt: Long,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val locator: String,
    val label: String,
    val createdAt: Long,
)
