package com.nxdeveloper.unapk.core

import java.io.File

enum class Stage {
    PREPARING,
    EXTRACTING,
    DECODING_RESOURCES,
    DECODING_MANIFEST,
    DECOMPILING_JAVA,
    DISASSEMBLING_SMALI,
    PACKAGING,
    DONE,
    FAILED
}

data class ProgressUpdate(
    val stage: Stage,
    val percent: Int,
    val message: String
)

sealed class DecompileResult {
    data class Success(
        val outputDirectory: File,
        val zipArchive: File?,
        val warnings: List<String>
    ) : DecompileResult()

    data class Failure(
        val stage: Stage,
        val reason: String,
        val cause: Throwable?
    ) : DecompileResult()
}
