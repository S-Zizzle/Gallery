package org.fossify.gallery.helpers

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ImageExifExtractor(private val context: Context) {

    fun extractMetadataTags(path: String): String {
        val tags = mutableListOf<String>()

        try {
            val exif = ExifInterface(path)

            val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.lowercase()
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.lowercase()
            if (make != null) tags.add(make)
            if (model != null) tags.add(model)

            val cal: Calendar? = run {
                val dateString = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (dateString != null) {
                    try {
                        val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault())
                        val date = sdf.parse(dateString)
                        if (date != null) Calendar.getInstance().apply { time = date } else null
                    } catch (e: Exception) { null }
                } else null
            } ?: extractDateFromFilename(path)

            if (cal != null) {
                tags.add(cal.get(Calendar.YEAR).toString())
                tags.add(cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault())?.lowercase() ?: "")
                tags.add(when (cal.get(Calendar.HOUR_OF_DAY)) {
                    in 5..11 -> "morning"
                    in 12..16 -> "afternoon"
                    in 17..20 -> "evening"
                    else -> "night"
                })
            }

            val latLon = FloatArray(2)
            if (exif.getLatLong(latLon)) {
                tags.addAll(reverseGeocode(latLon[0].toDouble(), latLon[1].toDouble()))
            }

        } catch (e: Exception) { }

        return tags.filter { it.isNotBlank() }.distinct().joinToString(", ")
    }

    private fun reverseGeocode(lat: Double, lon: Double): List<String> {
        return try {
            val dbFile = File(context.filesDir, "cities.db")
            if (!dbFile.exists()) copyDatabaseFromAssets(dbFile)

            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery("""
                SELECT c.name, co.name
                FROM cities c
                LEFT JOIN countries co ON c.country = co.iso
                WHERE c.lat BETWEEN ? AND ? AND c.lon BETWEEN ? AND ?
                ORDER BY ((c.lat - ?) * (c.lat - ?) + (c.lon - ?) * (c.lon - ?)) ASC
                LIMIT 1
            """, arrayOf(
                (lat - 1.0).toString(), (lat + 1.0).toString(),
                (lon - 1.0).toString(), (lon + 1.0).toString(),
                lat.toString(), lat.toString(), lon.toString(), lon.toString()
            ))

            val results = mutableListOf<String>()
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.lowercase()?.let { results.add(it) }
                cursor.getString(1)?.lowercase()?.let { results.add(it) }
            }
            cursor.close()
            db.close()
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun copyDatabaseFromAssets(destination: File) {
        context.assets.open("cities.db").use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun extractDateFromFilename(path: String): Calendar? {
        val filename = File(path).nameWithoutExtension

        val patterns = listOf(
            Pair("\\d{8}_\\d{6}".toRegex(), "yyyyMMdd_HHmmss"),
            Pair("\\d{4}-\\d{2}-\\d{2}[_ ]\\d{2}-\\d{2}-\\d{2}".toRegex(), "yyyy-MM-dd_HH-mm-ss"),
            Pair("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}".toRegex(), "yyyy-MM-dd-HH-mm-ss"),
            Pair("\\d{4}-\\d{2}-\\d{2}".toRegex(), "yyyy-MM-dd"),
            Pair("\\d{8}".toRegex(), "yyyyMMdd"),
            Pair("\\d{13}".toRegex(), "epoch")
        )

        for ((regex, format) in patterns) {
            val match = regex.find(filename) ?: continue
            try {
                val cal = Calendar.getInstance()
                if (format == "epoch") {
                    cal.timeInMillis = match.value.toLong()
                } else {
                    val sdf = SimpleDateFormat(format, Locale.getDefault())
                    cal.time = sdf.parse(match.value) ?: continue
                }
                if (cal.get(Calendar.YEAR) in 2000..2100) return cal
            } catch (e: Exception) { continue }
        }
        return null
    }
}
