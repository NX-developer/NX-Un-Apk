package com.nxdeveloper.unapk.decoder

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.DexFile
import java.io.File

class DexSmaliDecompiler {

    fun disassemble(
        dexFiles: List<File>,
        outputDirectory: File,
        onProgress: (Float) -> Unit
    ): SmaliReport {
        outputDirectory.mkdirs()
        val warnings = mutableListOf<String>()
        if (dexFiles.isEmpty()) {
            return SmaliReport(
                outputDirectory = outputDirectory,
                producedClassCount = 0,
                discoveredClassCount = 0,
                warnings = warnings + "no DEX files were provided",
                fatalError = null
            )
        }

        var totalDiscovered = 0
        var totalProduced = 0

        for ((index, dexFile) in dexFiles.withIndex()) {
            val perDexDir = File(outputDirectory, dexFile.nameWithoutExtension)
            perDexDir.mkdirs()
            try {
                val opcodes = Opcodes.forApi(API_LEVEL)
                val parsedDex: DexFile = DexFileFactory.loadDexFile(dexFile, opcodes)
                val classCount = parsedDex.classes.size
                totalDiscovered += classCount

                val options = BaksmaliOptions().apply {
                    apiLevel = API_LEVEL
                    deodex = false
                    debugInfo = true
                    parameterRegisters = true
                    localsDirective = true
                    sequentialLabels = true
                    accessorComments = true
                    allowOdex = true
                }

                val success = Baksmali.disassembleDexFile(
                    parsedDex,
                    perDexDir,
                    THREAD_COUNT,
                    options
                )
                if (!success) {
                    warnings.add("baksmali reported failure on ${dexFile.name}")
                }

                totalProduced += countSmaliFiles(perDexDir)
            } catch (oom: OutOfMemoryError) {
                warnings.add("baksmali ran out of memory on ${dexFile.name}: ${oom.message}")
            } catch (error: Throwable) {
                warnings.add("baksmali failed for ${dexFile.name}: ${error.message}")
            }
            onProgress((index + 1).toFloat() / dexFiles.size.toFloat())
        }

        return SmaliReport(
            outputDirectory = outputDirectory,
            producedClassCount = totalProduced,
            discoveredClassCount = totalDiscovered,
            warnings = warnings,
            fatalError = null
        )
    }

    private fun countSmaliFiles(directory: File): Int {
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
                } else if (child.name.endsWith(".smali")) {
                    count++
                }
            }
        }
        return count
    }

    private companion object {
        const val API_LEVEL = 35
        const val THREAD_COUNT = 1
    }
}

data class SmaliReport(
    val outputDirectory: File,
    val producedClassCount: Int,
    val discoveredClassCount: Int,
    val warnings: List<String>,
    val fatalError: Throwable?
)
