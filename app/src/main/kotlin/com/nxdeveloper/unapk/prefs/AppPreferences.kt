package com.nxdeveloper.unapk.prefs

import android.content.Context
import androidx.core.content.edit

class AppPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var backgroundExecutionEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND, true)
        set(value) = prefs.edit { putBoolean(KEY_BACKGROUND, value) }

    var keepIntermediateFiles: Boolean
        get() = prefs.getBoolean(KEY_KEEP_INTERMEDIATE, false)
        set(value) = prefs.edit { putBoolean(KEY_KEEP_INTERMEDIATE, value) }

    var produceJavaSources: Boolean
        get() = prefs.getBoolean(KEY_PRODUCE_JAVA, true)
        set(value) = prefs.edit { putBoolean(KEY_PRODUCE_JAVA, value) }

    var produceSmaliSources: Boolean
        get() = prefs.getBoolean(KEY_PRODUCE_SMALI, true)
        set(value) = prefs.edit { putBoolean(KEY_PRODUCE_SMALI, value) }

    var decodeResources: Boolean
        get() = prefs.getBoolean(KEY_DECODE_RESOURCES, true)
        set(value) = prefs.edit { putBoolean(KEY_DECODE_RESOURCES, value) }

    var packAsZip: Boolean
        get() = prefs.getBoolean(KEY_PACK_ZIP, true)
        set(value) = prefs.edit { putBoolean(KEY_PACK_ZIP, value) }

    var analyzeNativeLibraries: Boolean
        get() = prefs.getBoolean(KEY_NATIVE_ANALYSIS, true)
        set(value) = prefs.edit { putBoolean(KEY_NATIVE_ANALYSIS, value) }

    companion object {
        private const val PREFS_NAME = "nx_un_apk_prefs"
        private const val KEY_BACKGROUND = "background_execution"
        private const val KEY_KEEP_INTERMEDIATE = "keep_intermediate"
        private const val KEY_PRODUCE_JAVA = "produce_java"
        private const val KEY_PRODUCE_SMALI = "produce_smali"
        private const val KEY_DECODE_RESOURCES = "decode_resources"
        private const val KEY_PACK_ZIP = "pack_zip"
        private const val KEY_NATIVE_ANALYSIS = "native_analysis"
    }
}
