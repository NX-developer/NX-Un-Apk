package com.nxdeveloper.unapk.core

import com.nxdeveloper.unapk.decoder.ApkExtractor
import com.nxdeveloper.unapk.decoder.DexJavaDecompiler
import com.nxdeveloper.unapk.decoder.DexSmaliDecompiler
import com.nxdeveloper.unapk.decoder.NativeAnalyzer
import com.nxdeveloper.unapk.decoder.ResourceDecoder
import com.nxdeveloper.unapk.util.FileUtils
import com.nxdeveloper.unapk.util.ZipUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class UnApkEngine(private val dispatcher: CoroutineDispatcher = Dispatchers.Default) {

    suspend fun run(options: DecompileOptions, onProgress: (ProgressUpdate) -> Unit): DecompileResult = withContext(dispatcher) {
        val warnings = mutableListOf<String>()
        try {
            emit(onProgress, Stage.PREPARING, 1, "Preparing workspace")
            val workspace = FileUtils.ensureCleanDirectory(options.workspaceDirectory)
            val output = FileUtils.ensureCleanDirectory(options.outputDirectory)
            val extractedRaw = File(workspace, "raw").also { it.mkdirs() }

            emit(onProgress, Stage.EXTRACTING, 5, "Reading APK archive")
            val extractor = ApkExtractor()
            val extractionReport = extractor.extract(options.sourceApk, extractedRaw)
            copySafeArtifacts(extractionReport, output, warnings)
            emit(onProgress, Stage.EXTRACTING, 15, "Archive read complete")

            if (options.decodeResources) {
                emit(onProgress, Stage.DECODING_RESOURCES, 18, "Decoding resources")
                val resourceDecoder = ResourceDecoder()
                val resourceReport = resourceDecoder.decode(options.sourceApk, output) { ratio ->
                    val percent = 18 + (12 * ratio).toInt()
                    emit(onProgress, Stage.DECODING_RESOURCES, percent, "Decoding resources")
                }
                warnings += resourceReport.warnings
                if (resourceReport.fatalError != null) {
                    warnings.add("falling back to raw resources because decoder failed")
                    copyRawResources(extractionReport, output)
                } else if (!resourceReport.manifestDecoded) {
                    copyRawManifest(extractionReport, output)
                }
            } else {
                copyRawResources(extractionReport, output)
                copyRawManifest(extractionReport, output)
            }

            var javaSucceeded = false
            if (options.produceJavaSources) {
                emit(onProgress, Stage.DECOMPILING_JAVA, 35, "Decompiling Java sources")
                val javaDecompiler = DexJavaDecompiler()
                val javaOut = File(output, "java")
                val javaIntermediate = File(workspace, "jars")
                val javaReport = javaDecompiler.decompile(
                    extractionReport.dexFiles,
                    javaOut,
                    javaIntermediate
                ) { ratio ->
                    val percent = 35 + (25 * ratio).toInt()
                    emit(onProgress, Stage.DECOMPILING_JAVA, percent, "Decompiling Java sources")
                }
                warnings += javaReport.warnings
                javaSucceeded = javaReport.fatalError == null && javaReport.producedClassCount > 0
                if (!javaSucceeded) {
                    warnings.add("Java decompilation produced no usable output, smali fallback will be primary")
                }
            }

            if (options.produceSmaliSources || !javaSucceeded) {
                emit(onProgress, Stage.DISASSEMBLING_SMALI, 65, "Disassembling smali")
                val smaliDecompiler = DexSmaliDecompiler()
                val smaliOut = File(output, "smali")
                val smaliReport = smaliDecompiler.disassemble(
                    extractionReport.dexFiles,
                    smaliOut
                ) { ratio ->
                    val percent = 65 + (15 * ratio).toInt()
                    emit(onProgress, Stage.DISASSEMBLING_SMALI, percent, "Disassembling smali")
                }
                warnings += smaliReport.warnings
                if (smaliReport.fatalError != null && !javaSucceeded) {
                    warnings.add("smali fallback also failed, raw DEX files have been preserved")
                    copyRawDex(extractionReport, output)
                }
            }

            if (options.analyzeNativeLibraries) {
                emit(onProgress, Stage.ANALYZING_NATIVE, 82, "Analyzing native libraries")
                val nativeOut = File(output, "native_analysis")
                val nativeRoot = File(extractionReport.workspace, "lib")
                val nativeAnalyzer = NativeAnalyzer()
                val nativeReport = nativeAnalyzer.analyze(nativeRoot, nativeOut) { ratio ->
                    val percent = 82 + (8 * ratio).toInt()
                    emit(onProgress, Stage.ANALYZING_NATIVE, percent, "Analyzing native libraries")
                }
                warnings += nativeReport.warnings
                if (nativeReport.discoveredFiles == 0) {
                    nativeOut.deleteRecursively()
                }
            }

            val zipFile = if (options.packAsZip) {
                emit(onProgress, Stage.PACKAGING, 92, "Packaging output archive")
                val baseName = options.sourceApk.nameWithoutExtension
                val zipTarget = File(options.outputDirectory.parentFile, "$baseName-decoded.zip")
                ZipUtils.zipDirectory(output, zipTarget)
                zipTarget
            } else {
                null
            }

            if (!options.keepIntermediateFiles) {
                FileUtils.deleteRecursively(workspace)
            }

            emit(onProgress, Stage.DONE, 100, "Completed")
            DecompileResult.Success(
                outputDirectory = output,
                zipArchive = zipFile,
                warnings = warnings
            )
        } catch (error: Throwable) {
            emit(onProgress, Stage.FAILED, 100, "Failed: ${error.message ?: error::class.java.simpleName}")
            DecompileResult.Failure(
                stage = Stage.FAILED,
                reason = error.message ?: "Unexpected failure",
                cause = error
            )
        }
    }

    private fun copySafeArtifacts(report: com.nxdeveloper.unapk.decoder.ExtractionReport, output: File, warnings: MutableList<String>) {
        for (file in report.rawAssetFiles) {
            copyInto(report.workspace, file, output, warnings)
        }
        for (file in report.nativeLibraries) {
            copyInto(report.workspace, file, output, warnings)
        }
        for (file in report.signatureFiles) {
            copyInto(report.workspace, file, output, warnings)
        }
        for (file in report.otherFiles) {
            copyInto(report.workspace, file, output, warnings)
        }
    }

    private fun copyRawResources(report: com.nxdeveloper.unapk.decoder.ExtractionReport, output: File) {
        for (xml in report.rawXmlFiles) {
            val rel = xml.absolutePath.removePrefix(report.workspace.absolutePath).trimStart(File.separatorChar)
            val target = File(output, rel)
            target.parentFile?.mkdirs()
            xml.copyTo(target, overwrite = true)
        }
        report.resourcesArsc?.let { arsc ->
            val target = File(output, "resources.arsc")
            arsc.copyTo(target, overwrite = true)
        }
    }

    private fun copyRawManifest(report: com.nxdeveloper.unapk.decoder.ExtractionReport, output: File) {
        report.manifestFile?.let { manifest ->
            val target = File(output, "AndroidManifest.bin.xml")
            manifest.copyTo(target, overwrite = true)
        }
    }

    private fun copyRawDex(report: com.nxdeveloper.unapk.decoder.ExtractionReport, output: File) {
        for (dex in report.dexFiles) {
            val target = File(output, dex.name)
            dex.copyTo(target, overwrite = true)
        }
    }

    private fun copyInto(workspaceRoot: File, source: File, output: File, warnings: MutableList<String>) {
        try {
            val rel = source.absolutePath.removePrefix(workspaceRoot.absolutePath).trimStart(File.separatorChar)
            val target = File(output, rel)
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        } catch (error: Throwable) {
            warnings.add("could not copy ${source.name}: ${error.message}")
        }
    }

    private fun emit(callback: (ProgressUpdate) -> Unit, stage: Stage, percent: Int, message: String) {
        callback(ProgressUpdate(stage, percent.coerceIn(0, 100), message))
    }
}
