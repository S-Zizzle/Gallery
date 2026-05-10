package org.fossify.gallery.interfaces

import androidx.room.*
import org.fossify.gallery.models.ImageTag
import androidx.sqlite.db.SimpleSQLiteQuery

@Dao
interface ImageTagsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(imageTag: ImageTag)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(imageTags: List<ImageTag>)

    @RawQuery
    fun searchRaw(query: SimpleSQLiteQuery): List<ImageTag>

    fun search(queryString: String): List<ImageTag> {
        val terms = queryString.trim().lowercase().split(" ").filter { it.isNotBlank() }
        val sql = "SELECT * FROM image_tags WHERE " +
            terms.joinToString(" AND ") { "tags LIKE ?" }
        val args = terms.map { "%$it%" }.toTypedArray()
        return searchRaw(SimpleSQLiteQuery(sql, args))
    }

    @Query("SELECT full_path FROM image_tags")
    fun getAllTaggedPaths(): List<String>

    @Query("SELECT full_path FROM image_tags WHERE tagged_at IS NULL")
    fun getUntaggedPaths(): List<String>

    @Query("DELETE FROM image_tags WHERE full_path = :path COLLATE NOCASE")
    fun deletePath(path: String)

    @Query("SELECT * FROM image_tags WHERE full_path = :path COLLATE NOCASE")
    fun getTagsForPath(path: String): ImageTag?
}
