package com.nxdeveloper.unapk.decoder

import com.googlecode.d2j.dex.ClassVisitorFactory
import com.googlecode.d2j.dex.DexExceptionHandler
import com.googlecode.d2j.dex.ExDex2Asm
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.node.DexMethodNode
import com.googlecode.d2j.reader.DexFileReader
import org.benf.cfr.reader.api.CfrDriver
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
                val classCount = convertDexToJar(dexFile, jarTarget, warnings)
                if (classCount > 0) {
                    convertedJars.add(jarTarget)
                    totalClasses += classCount
                } else {
                    warnings.add("dex2jar produced no classes for ${dexFile.name}")
                }
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

    private fun convertDexToJar(dexFile: File, jarTarget: File, warnings: MutableList<String>): Int {
        if (jarTarget.exists()) {
            jarTarget.delete()
        }
        jarTarget.parentFile?.mkdirs()

        val reader = DexFileReader(dexFile)
        val fileNode = DexFileNode()
        reader.accept(fileNode)

        val writtenNames = HashSet<String>()
        var classCount = 0

        ZipOutputStream(FileOutputStream(jarTarget)).use { zipOut ->
            val factory = object : ClassVisitorFactory {
                override fun create(name: String): ClassVisitor {
                    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
                    return object : ClassVisitor(Opcodes.ASM9, writer) {
                        override fun visitEnd() {
                            super.visitEnd()
                            val data = try {
                                writer.toByteArray()
                            } catch (error: Throwable) {
                                warnings.add("ASM serialization failed for $name: ${error.message}")
                                return
                            }
                            if (writtenNames.contains(name)) {
                                return
                            }
                            try {
                                zipOut.putNextEntry(ZipEntry("$name.class"))
                                zipOut.write(data)
                                zipOut.closeEntry()
                                writtenNames.add(name)
                                classCount++
                            } catch (error: Throwable) {
                                warnings.add("could not write class $name to JAR: ${error.message}")
                            }
                        }
                    }
                }
            }

            val handler = LenientExceptionHandler(warnings, dexFile.name)
            val converter = ExDex2Asm(handler)
            converter.convertDex(fileNode, factory)
        }

        return classCount
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

private class LenientExceptionHandler(
    private val warnings: MutableList<String>,
    private val dexName: String
) : DexExceptionHandler {

    override fun handleFileException(e: Exception) {
        warnings.add("dex2jar file exception in $dexName: ${e.message}")
    }

    override fun handleMethodTranslateException(
        method: com.googlecode.d2j.Method?,
        methodNode: DexMethodNode?,
        mv: MethodVisitor?,
        e: Exception
    ) {
        warnings.add("method translate failed in $dexName for $method: ${e.message}")
    }
}

data class JavaDecompileReport(
    val outputDirectory: File,
    val producedClassCount: Int,
    val discoveredClassCount: Int,
    val warnings: List<String>,
    val fatalError: Throwable?
)
