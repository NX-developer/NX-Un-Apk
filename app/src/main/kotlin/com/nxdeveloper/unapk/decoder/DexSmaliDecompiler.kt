package com.nxdeveloper.unapk.decoder

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import java.io.File

class DexSmaliDecompiler {

    fun disassemble(apkFile: File, outputDirectory: File, onProgress: (Float) -> Unit): SmaliReport {
        outputDirectory.mkdirs()
        val warnings = mutableListOf<String>()
        val args = JadxArgs().apply {
            inputFiles = mutableListOf(apkFile)
            outDir = outputDirectory
            outDirSrc = outputDirectory
            outDirRes = File(outputDirectory, "_unused_resources")
            isSkipResources = true
            isSkipSources = true
            isShowInconsistentCode = true
            isExportAsGradleProject = false
            isDeobfuscationOn = false
            threadsCount = THREAD_COUNT
        }

        var produced = 0
        var discovered = 0
        try {
            JadxDecompiler(args).use { jadx ->
                jadx.load()
                val classes = jadx.classes
                discovered = classes.size
                if (discovered == 0) {
                    warnings.add("no classes were available for smali extraction")
                    return SmaliReport(outputDirectory, produced, discovered, warnings, null)
                }
                for ((index, cls) in classes.withIndex()) {
                    if (writeSmali(cls, outputDirectory)) {
                        produced++
                    }
                    if (index % SMALI_TICK == 0) {
                        onProgress(index.toFloat() / discovered.toFloat())
                    }
                }
                onProgress(1f)
            }
        } catch (oom: OutOfMemoryError) {
            return SmaliReport(
                outputDirectory = outputDirectory,
                producedClassCount = produced,
                discoveredClassCount = discovered,
                warnings = warnings + "smali pass ran out of memory: ${oom.message}",
                fatalError = oom
            )
        } catch (error: Throwable) {
            return SmaliReport(
                outputDirectory = outputDirectory,
                producedClassCount = produced,
                discoveredClassCount = discovered,
                warnings = warnings + "smali pass aborted: ${error.message}",
                fatalError = error
            )
        }

        File(outputDirectory, "_unused_resources").let { stub ->
            if (stub.exists()) {
                stub.deleteRecursively()
            }
        }

        return SmaliReport(
            outputDirectory = outputDirectory,
            producedClassCount = produced,
            discoveredClassCount = discovered,
            warnings = warnings,
            fatalError = null
        )
    }

    private fun writeSmali(cls: JavaClass, outputDirectory: File): Boolean {
        val smaliText = try {
            cls.smali
        } catch (error: Throwable) {
            null
        } ?: return false
        if (smaliText.isBlank()) {
            return false
        }
        val rawName = try {
            cls.rawName
        } catch (error: Throwable) {
            cls.fullName
        } ?: return false
        val sanitized = rawName.replace('.', '/').replace('$', '_')
        val target = File(outputDirectory, "$sanitized.smali")
        target.parentFile?.mkdirs()
        target.writeText(smaliText)
        return true
    }

    companion object {
        private const val THREAD_COUNT = 1
        private const val SMALI_TICK = 16
    }
}

data class SmaliReport(
    val outputDirectory: File,
    val producedClassCount: Int,
    val discoveredClassCount: Int,
    val warnings: List<String>,
    val fatalError: Throwable?
)
