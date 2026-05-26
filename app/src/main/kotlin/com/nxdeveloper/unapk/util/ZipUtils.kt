package com.nxdeveloper.unapk.util

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipUtils {

    fun zipDirectory(sourceDir: File, destinationZip: File, level: Int = Deflater.BEST_SPEED) {
        if (destinationZip.exists()) {
            destinationZip.delete()
        }
        destinationZip.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(destinationZip)).use { output ->
            output.setLevel(level)
            val base = sourceDir.absolutePath.length + 1
            walk(sourceDir, output, base)
        }
    }

    private fun walk(current: File, output: ZipOutputStream, basePathLength: Int) {
        val children = current.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                val name = child.absolutePath.substring(basePathLength).replace('\\', '/') + "/"
                output.putNextEntry(ZipEntry(name))
                output.closeEntry()
                walk(child, output, basePathLength)
            } else {
                val name = child.absolutePath.substring(basePathLength).replace('\\', '/')
                val entry = ZipEntry(name)
                entry.time = child.lastModified()
                output.putNextEntry(entry)
                BufferedInputStream(FileInputStream(child)).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                }
                output.closeEntry()
            }
        }
    }
}
