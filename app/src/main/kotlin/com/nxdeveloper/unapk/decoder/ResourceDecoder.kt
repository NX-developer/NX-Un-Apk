package com.nxdeveloper.unapk.decoder

import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

class ResourceDecoder {

    fun decode(apkFile: File, outputDirectory: File, onProgress: (Float) -> Unit): ResourceReport {
        outputDirectory.mkdirs()
        val warnings = mutableListOf<String>()
        var manifestDecoded = false
        var resourcesDecoded = false
        var xmlFilesDecoded = 0

        val entryNames = mutableListOf<String>()
        val entryContents = HashMap<String, ByteArray>()

        try {
            ZipFile(apkFile).use { zip ->
                val all = zip.entries().toList().filter { entry -> !entry.isDirectory }
                for (entry in all) {
                    val name = entry.name
                    if (name == "AndroidManifest.xml" || name == "resources.arsc" || (name.startsWith("res/") && name.endsWith(".xml"))) {
                        BufferedInputStream(zip.getInputStream(entry)).use { stream ->
                            entryContents[name] = stream.readBytes()
                        }
                        entryNames.add(name)
                    }
                }
            }
        } catch (error: Throwable) {
            return ResourceReport(
                outputDirectory = outputDirectory,
                manifestDecoded = false,
                resourcesDecoded = false,
                xmlFilesDecoded = 0,
                warnings = warnings + "could not read APK archive: ${error.message}",
                fatalError = error
            )
        }

        entryContents["AndroidManifest.xml"]?.let { bytes ->
            try {
                val manifest = AndroidManifestBlock()
                manifest.readBytes(ByteArrayInputStream(bytes))
                val target = File(outputDirectory, "AndroidManifest.xml")
                target.writeText(manifest.toString())
                manifestDecoded = true
            } catch (error: Throwable) {
                warnings.add("manifest decoding failed, raw form will be kept: ${error.message}")
            }
        }
        onProgress(0.30f)

        entryContents["resources.arsc"]?.let { bytes ->
            try {
                val table = TableBlock()
                table.readBytes(ByteArrayInputStream(bytes))
                val resDir = File(outputDirectory, "res").also { it.mkdirs() }
                val dump = File(resDir, "resources.dump.txt")
                dump.writeText(table.toString())
                resourcesDecoded = true
            } catch (error: Throwable) {
                warnings.add("resource table decoding failed: ${error.message}")
            }
        }
        onProgress(0.55f)

        val xmlEntries = entryNames.filter { it.startsWith("res/") && it.endsWith(".xml") }
        val total = xmlEntries.size.coerceAtLeast(1)
        xmlEntries.forEachIndexed { index, path ->
            val bytes = entryContents[path] ?: return@forEachIndexed
            try {
                val document = ResXmlDocument()
                document.readBytes(ByteArrayInputStream(bytes))
                val target = File(outputDirectory, path)
                target.parentFile?.mkdirs()
                target.writeText(document.toString())
                xmlFilesDecoded++
            } catch (error: Throwable) {
                warnings.add("could not decode $path: ${error.message}")
            }
            onProgress(0.55f + 0.45f * (index + 1).toFloat() / total.toFloat())
        }
        onProgress(1f)

        return ResourceReport(
            outputDirectory = outputDirectory,
            manifestDecoded = manifestDecoded,
            resourcesDecoded = resourcesDecoded,
            xmlFilesDecoded = xmlFilesDecoded,
            warnings = warnings,
            fatalError = null
        )
    }
}

data class ResourceReport(
    val outputDirectory: File,
    val manifestDecoded: Boolean,
    val resourcesDecoded: Boolean,
    val xmlFilesDecoded: Int,
    val warnings: List<String>,
    val fatalError: Throwable?
)
