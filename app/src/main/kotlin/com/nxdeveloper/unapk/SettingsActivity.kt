package com.nxdeveloper.unapk

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.nxdeveloper.unapk.databinding.ActivitySettingsBinding
import com.nxdeveloper.unapk.prefs.AppPreferences

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        preferences = AppPreferences(this)

        bind(binding.switchBackground, preferences.backgroundExecutionEnabled) { value ->
            preferences.backgroundExecutionEnabled = value
        }
        bind(binding.switchJava, preferences.produceJavaSources) { value ->
            preferences.produceJavaSources = value
        }
        bind(binding.switchSmali, preferences.produceSmaliSources) { value ->
            preferences.produceSmaliSources = value
        }
        bind(binding.switchResources, preferences.decodeResources) { value ->
            preferences.decodeResources = value
        }
        bind(binding.switchZip, preferences.packAsZip) { value ->
            preferences.packAsZip = value
        }
        bind(binding.switchKeepIntermediate, preferences.keepIntermediateFiles) { value ->
            preferences.keepIntermediateFiles = value
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun bind(switchView: MaterialSwitch, initialValue: Boolean, onChange: (Boolean) -> Unit) {
        switchView.isChecked = initialValue
        switchView.setOnCheckedChangeListener { _, value ->
            onChange(value)
        }
    }
}
