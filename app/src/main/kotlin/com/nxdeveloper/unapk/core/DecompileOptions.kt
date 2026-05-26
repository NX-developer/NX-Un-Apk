package com.nxdeveloper.unapk.core

import java.io.File

data class DecompileOptions(
    val sourceApk: File,
    val outputDirectory: File,
    val workspaceDirectory: File,
    val produceJavaSources: Boolean,
    val produceSmaliSources: Boolean,
    val decodeResources: Boolean,
    val packAsZip: Boolean,
    val keepIntermediateFiles: Boolean
)
