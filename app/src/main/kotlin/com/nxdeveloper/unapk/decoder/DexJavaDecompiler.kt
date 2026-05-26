package com.nxdeveloper.unapk.decoder

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import java.io.File

class DexJavaDecompiler {

    fun decompile(apkFile: File, outputDirectory: File, onProgress: (Float) -> Unit): JavaDecompileReport {
        outputDirectory.mkdirs()
        val warnings = mutableListOf<String>()
        val args = JadxArgs().apply {
            inputFiles = mutableListOf(apkFile)
            outDir = outputDirectory
            outDirSrc = File(outputDirectory, "sources")
            outDirRes = File(outputDirectory, "resources_unused")
            isSkipResources = true
            isSkipSources = false
            isShowInconsistentCode = true
            isRespectBytecodeAccessModifiers = true
            isExportAsGradleProject = false
            isDeobfuscationOn = false
            isReplaceConsts = true
            isFsCaseSensitive = true
            isUseImports = true
            threadsCount = THREAD_COUNT
        }

        var classCount = 0
        var savedCount = 0
        try {
            JadxDecompiler(args).use { jadx ->
                jadx.load()
                val classes = jadx.classes
                classCount = classes.size
                if (classCount == 0) {
                    warnings.add("jadx loaded the APK but no classes were discovered")
                    return JavaDecompileReport(outputDirectory, 0, 0, warnings, null)
                }
                onProgress(0.05f)
                jadx.save(SAVE_TICK_MS) { done, total ->
                    val ratio = if (total <= 0L) {
                        0f
                    } else {
                        done.toFloat() / total.toFloat()
                    }
                    onProgress(ratio.coerceIn(0f, 1f))
                }
                savedCount = countJavaFiles(File(outputDirectory, "sources"))
            }
        } catch (oom: OutOfMemoryError) {
            return JavaDecompileReport(
                outputDirectory = outputDirectory,
                producedClassCount = savedCount,
                discoveredClassCount = classCount,
                warnings = warnings + "jadx ran out of memory: ${oom.message}",
                fatalError = oom
            )
        } catch (error: Throwable) {
            return JavaDecompileReport(
                outputDirectory = outputDirectory,
                producedClassCount = savedCount,
                discoveredClassCount = classCount,
                warnings = warnings + "jadx aborted: ${error.message}",
                fatalError = error
            )
        }

        File(outputDirectory, "resources_unused").let { stub ->
            if (stub.exists()) {
                stub.deleteRecursively()
            }
        }

        return JavaDecompileReport(
            outputDirectory = outputDirectory,
            producedClassCount = savedCount,
            discoveredClassCount = classCount,
            warnings = warnings,
            fatalError = null
        )
    }

    private fun countJavaFiles(directory: File): Int {
        if (!directory.exists() || !directory.isDirectory) {
            return 0
        }
        var count = 0
        val stack = ArrayDeque<File>()
        stack.addLast(directory)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.addLast(child)
                } else if (child.name.endsWith(".java")) {
                    count++
                }
            }
        }
        return count
    }

    companion object {
        private const val THREAD_COUNT = 1
        private const val SAVE_TICK_MS = 500
    }
}

data class JavaDecompileReport(
    val outputDirectory: File,
    val producedClassCount: Int,
    val discoveredClassCount: Int,
    val warnings: List<String>,
    val fatalError: Throwable?
)
