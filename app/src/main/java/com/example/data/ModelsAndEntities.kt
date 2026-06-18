package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ExtractedImage(
    val url: String,
    val name: String,
    val type: String, // "JPEG", "PNG", "SVG", "WEBP", "Background", etc.
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0,
    val altText: String? = null,
    val sourceSiteUrl: String
)

@Entity(tableName = "extraction_history")
data class ExtractionHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val siteUrl: String,
    val siteTitle: String,
    val timestamp: Long,
    val imagesJson: String // List<ExtractedImage> serialized to JSON
)

@Entity(tableName = "saved_images")
data class SavedImage(
    @PrimaryKey val url: String,
    val name: String,
    val type: String,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0,
    val sourceSiteUrl: String,
    val sourceSiteTitle: String,
    val savedAt: Long,
    val isFavorite: Boolean = false
)
