package com.numbear.manjuan.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: Long): SourceEntity?

    @Query("SELECT * FROM sources WHERE type = :type LIMIT 1")
    suspend fun firstOfType(type: String): SourceEntity?

    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM books WHERE sourceId = :sourceId AND remotePath = :remotePath LIMIT 1")
    suspend fun findRemote(sourceId: Long, remotePath: String): BookEntity?

    @Query("SELECT * FROM books WHERE sourceId = :sourceId AND remotePath = :remotePath")
    suspend fun listRemote(sourceId: Long, remotePath: String): List<BookEntity>

    @Query("SELECT * FROM books")
    suspend fun all(): List<BookEntity>

    @Query("UPDATE books SET cachedPath = '' WHERE id = :id")
    suspend fun clearCachedPath(id: Long)
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress")
    fun observeAll(): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun get(bookId: Long): ProgressEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ProgressEntity)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    suspend fun forBook(bookId: Long): List<BookmarkEntity>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND locator = :locator")
    suspend fun deleteLocator(bookId: Long, locator: String)
}
