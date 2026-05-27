package com.nxdeveloper.unapk.decoder

import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.reader.MultiDexFileReader
import org.benf.cfr.reader.api.CfrDriver
import java.io.File

class DexJavaDecompiler {

    fun decompile(
        dexFiles: List<File>,
        outputDirectory: File,
        intermediateDirectory: File,
        onProgress: (Float) -> Unit
    ): JavaDecompileReport {
        outputDirectory.mkdirs()
        intermediateDirectory.mkdirs()
        val warnings = mutableListOf<String>()
        if (dexFiles.isEmpty()) {
            return JavaDecompileReport(
                outputDirectory = outputDirectory,
                producedClassCount = 0,
                discoveredClassCount = 0,
                warnings = warnings + "no DEX files were provided",
                fatalError = null
            )
        }

        val convertedJars = mutableListOf<File>()
        var totalClasses = 0
        for ((index, dexFile) in dexFiles.withIndex()) {
            val jarTarget = File(intermediateDirectory, "${dexFile.nameWithoutExtension}.jar")
            try {
                Dex2jar.from(dexFile)
                    .skipDebug(false)
                    .topoLogicalSort()
                    .noCode(false)
                    .to(jarTarget.toPath())
                convertedJars.add(jarTarget)
                totalClasses += countClassesInJar(jarTarget)
            } catch (oom: OutOfMemoryError) {
                warnings.add("dex2jar ran out of memory on ${dexFile.name}: ${oom.message}")
            } catch (error: Throwable) {
                warnings.add("dex2jar failed for ${dexFile.name}: ${error.message}")
            }
            onProgress(0.45f * (index + 1).toFloat() / dexFiles.size.toFloat())
        }

        if (convertedJars.isEmpty()) {
            return JavaDecompileReport(
                outputDirectory = outputDirectory,
                producedClassCount = 0,
                discoveredClassCount = totalClasses,
                warnings = warnings + "no JAR archives were produced from DEX files",
                fatalError = null
            )
        }

        var totalDecompiled = 0
        for ((index, jarFile) in convertedJars.withIndex()) {
            val perJarOutput = File(outputDirectory, jarFile.nameWithoutExtension)
            perJarOutput.mkdirs()
            try {
                val options = HashMap<String, String>()
                options["outputdir"] = perJarOutput.absolutePath
                options["silent"] = "true"
                options["recover"] = "true"
                options["recovertypeclash"] = "true"
                options["recovertypehints"] = "true"
                options["showversion"] = "false"
                options["hideutf"] = "false"
                options["caseinsensitivefs"] = "false"
                options["comments"] = "false"
                options["decodelambdas"] = "true"
                options["decodestringswitch"] = "true"
                options["sugarstringbuilder"] = "true"
                options["sugarboxing"] = "true"
                options["arrayiter"] = "true"
                options["collectioniter"] = "true"
                options["innerclasses"] = "true"

                val driver = CfrDriver.Builder()
                    .withOptions(options)
                    .build()
                driver.analyse(listOf(jarFile.absolutePath))

                totalDecompiled += countFilesByExtension(perJarOutput, ".java")
            } catch (oom: OutOfMemoryError) {
                warnings.add("CFR ran out of memory on ${jarFile.name}: ${oom.message}")
            } catch (error: Throwable) {
                warnings.add("CFR failed for ${jarFile.name}: ${error.message}")
            }
            val progress = 0.45f + 0.55f * (index + 1).toFloat() / convertedJars.size.toFloat()
            onProgress(progress.coerceIn(0f, 1f))
        }

        return JavaDecompileReport(
            outputDirectory = outputDirectory,
            producedClassCount = totalDecompiled,
            discoveredClassCount = totalClasses,
            warnings = warnings,
            fatalError = null
        )
    }

    private fun countClassesInJar(jar: File): Int {
        if (!jar.exists()) {
            return 0
        }
        var count = 0
        try {
            java.util.zip.ZipFile(jar).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.endsWith(".class")) {
                        count++
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
        return count
    }

    private fun countFilesByExtension(directory: File, extension: String): Int {
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
                } else if (child.name.endsWith(extension)) {
                    count++
                }
            }
        }
        return count
    }
}

data class JavaDecompileReport(
    val outputDirectory: File,
    val producedClassCount: Int,
    val discoveredClassCount: Int,
    val warnings: List<String>,
    val fatalError: Throwable?
)
