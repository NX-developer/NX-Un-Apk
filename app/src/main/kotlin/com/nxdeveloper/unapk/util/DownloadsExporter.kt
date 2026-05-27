package com.nxdeveloper.unapk.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

object DownloadsExporter {

    private const val SUBDIR = "NX-Un-Apk"

    fun export(context: Context, source: File, displayFileName: String): ExportResult {
        if (!source.exists()) {
            return ExportResult.Failure("source archive does not exist: ${source.absolutePath}")
        }
        val sanitized = sanitize(displayFileName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(context, source, sanitized)
        } else {
            exportDirect(source, sanitized)
        }
    }

    private fun exportDirect(source: File, displayFileName: String): ExportResult {
        return try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val folder = File(downloads, SUBDIR)
            if (!folder.exists() && !folder.mkdirs()) {
                return ExportResult.Failure("could not create folder ${folder.absolutePath}")
            }
            val target = File(folder, displayFileName)
            source.copyTo(target, overwrite = true)
            ExportResult.Success(target.absolutePath, null)
        } catch (error: Throwable) {
            ExportResult.Failure("legacy export failed: ${error.message}")
        }
    }

    private fun exportViaMediaStore(context: Context, source: File, displayFileName: String): ExportResult {
        return try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val baseValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayFileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }

            removePrevious(context, displayFileName)

            val targetUri: Uri = resolver.insert(collection, baseValues)
                ?: return ExportResult.Failure("MediaStore insert returned null")

            FileInputStream(source).use { input ->
                resolver.openOutputStream(targetUri, "w")?.use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                } ?: return ExportResult.Failure("could not open output stream for $targetUri")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finalize = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(targetUri, finalize, null, null)
            }

            val visiblePath = "/storage/emulated/0/${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/$displayFileName"
            ExportResult.Success(visiblePath, targetUri)
        } catch (error: Throwable) {
            ExportResult.Failure("MediaStore export failed: ${error.message}")
        }
    }

    private fun removePrevious(context: Context, displayName: String) {
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection =
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf(
                displayName,
                "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR%"
            )
            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    resolver.delete(uri, null, null)
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned.isBlank()) "output.zip" else cleaned
    }
}

sealed class ExportResult {
    data class Success(val visiblePath: String, val uri: Uri?) : ExportResult()
    data class Failure(val reason: String) : ExportResult()
}
