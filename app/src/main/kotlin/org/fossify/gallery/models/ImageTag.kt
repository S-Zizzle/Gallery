package org.fossify.gallery.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "image_tags", indices = [Index(value = ["full_path"], unique = true)])
data class ImageTag(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long?,
    @ColumnInfo(name = "full_path") val fullPath: String,
    @ColumnInfo(name = "tags") val tags: String?,
    @ColumnInfo(name = "tagged_at") val taggedAt: Long?
)
