package com.nxdeveloper.unapk.decoder

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class ApkExtractor {

    fun extract(apkFile: File, destination: File): ExtractionReport {
        val dexFiles = mutableListOf<File>()
        val rawXmlFiles = mutableListOf<File>()
        val rawAssetFiles = mutableListOf<File>()
        val nativeLibraries = mutableListOf<File>()
        val signatureFiles = mutableListOf<File>()
        val otherFiles = mutableListOf<File>()
        var resourcesArsc: File? = null
        var manifestFile: File? = null

        destination.mkdirs()

        ZipFile(apkFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) {
                    continue
                }
                val outFile = File(destination, entry.name)
                outFile.parentFile?.mkdirs()
                BufferedInputStream(zip.getInputStream(entry)).use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) {
                                break
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                classifyEntry(
                    entry.name,
                    outFile,
                    dexFiles,
                    rawXmlFiles,
                    rawAssetFiles,
                    nativeLibraries,
                    signatureFiles,
                    otherFiles
                ) { kind ->
                    when (kind) {
                        EntryKind.RESOURCES_ARSC -> resourcesArsc = outFile
                        EntryKind.MANIFEST -> manifestFile = outFile
                        else -> Unit
                    }
                }
            }
        }

        return ExtractionReport(
            workspace = destination,
            dexFiles = dexFiles,
            rawXmlFiles = rawXmlFiles,
            rawAssetFiles = rawAssetFiles,
            nativeLibraries = nativeLibraries,
            signatureFiles = signatureFiles,
            otherFiles = otherFiles,
            resourcesArsc = resourcesArsc,
            manifestFile = manifestFile
        )
    }

    private fun classifyEntry(
        name: String,
        file: File,
        dexFiles: MutableList<File>,
        rawXmlFiles: MutableList<File>,
        rawAssetFiles: MutableList<File>,
        nativeLibraries: MutableList<File>,
        signatureFiles: MutableList<File>,
        otherFiles: MutableList<File>,
        onSpecial: (EntryKind) -> Unit
    ) {
        when {
            name == "AndroidManifest.xml" -> onSpecial(EntryKind.MANIFEST)
            name == "resources.arsc" -> onSpecial(EntryKind.RESOURCES_ARSC)
            name.endsWith(".dex") && !name.contains('/') -> dexFiles.add(file)
            name.startsWith("META-INF/") -> signatureFiles.add(file)
            name.startsWith("lib/") -> nativeLibraries.add(file)
            name.startsWith("assets/") -> rawAssetFiles.add(file)
            name.startsWith("res/") && name.endsWith(".xml") -> rawXmlFiles.add(file)
            else -> otherFiles.add(file)
        }
    }

    private enum class EntryKind {
        MANIFEST,
        RESOURCES_ARSC,
        DEX,
        XML,
        ASSET,
        NATIVE,
        SIGNATURE,
        OTHER
    }
}

data class ExtractionReport(
    val workspace: File,
    val dexFiles: List<File>,
    val rawXmlFiles: List<File>,
    val rawAssetFiles: List<File>,
    val nativeLibraries: List<File>,
    val signatureFiles: List<File>,
    val otherFiles: List<File>,
    val resourcesArsc: File?,
    val manifestFile: File?
)
