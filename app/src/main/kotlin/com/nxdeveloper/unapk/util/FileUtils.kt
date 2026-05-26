package com.nxdeveloper.unapk.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object FileUtils {

    fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        var name: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    name = cursor.getString(idx)
                }
            }
        }
        return name
    }

    fun copyUriToFile(resolver: ContentResolver, uri: Uri, destination: File): Long {
        var copied = 0L
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destination).use { output ->
                copied = copyStream(input, output)
            }
        } ?: throw IllegalStateException("Cannot open input stream for $uri")
        return copied
    }

    fun copyStream(input: InputStream, output: FileOutputStream, bufferSize: Int = DEFAULT_BUFFER): Long {
        val buffer = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
                break
            }
            output.write(buffer, 0, read)
            total += read
        }
        output.flush()
        return total
    }

    fun deleteRecursively(file: File): Boolean {
        if (!file.exists()) {
            return true
        }
        if (file.isDirectory) {
            val children = file.listFiles() ?: emptyArray()
            for (child in children) {
                deleteRecursively(child)
            }
        }
        return file.delete()
    }

    fun ensureCleanDirectory(directory: File): File {
        if (directory.exists()) {
            deleteRecursively(directory)
        }
        directory.mkdirs()
        return directory
    }

    fun humanReadableSize(bytes: Long): String {
        if (bytes < 1024) {
            return "$bytes B"
        }
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format("%.2f %s", value, units[unitIndex])
    }

    private const val DEFAULT_BUFFER = 64 * 1024
}
