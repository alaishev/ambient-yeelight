package com.ambient.yeelight.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ambient.yeelight.R
import com.ambient.yeelight.common.Settings
import com.ambient.yeelight.databinding.ActivitySettingsBinding

/**
 * The ambient light parameters. A slider saves its value at once; the light takes the new values
 * the next time it is turned on.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var settings: Settings
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        binding.intervalSlider.valueFrom = Settings.COLOR_INTERVAL_RANGE_MS.first.toFloat()
        binding.intervalSlider.valueTo = Settings.COLOR_INTERVAL_RANGE_MS.last.toFloat()
        binding.deltaESlider.valueFrom = Settings.MIN_DELTA_E_RANGE.start
        binding.deltaESlider.valueTo = Settings.MIN_DELTA_E_RANGE.endInclusive
        binding.transitionSlider.valueFrom = Settings.TRANSITION_RANGE_MS.first.toFloat()
        binding.transitionSlider.valueTo = Settings.TRANSITION_RANGE_MS.last.toFloat()
        binding.brightnessSlider.valueFrom = Settings.BRIGHTNESS_RANGE.first.toFloat()
        binding.brightnessSlider.valueTo = Settings.BRIGHTNESS_RANGE.last.toFloat()

        // Each slider shows its value; one the user moves also saves it
        binding.intervalSlider.addOnChangeListener { _, value, fromUser ->
            binding.intervalValue.text = getString(R.string.value_ms, value.toInt())
            if (fromUser) {
                settings.colorIntervalMs = value.toLong()
            }
        }
        binding.deltaESlider.addOnChangeListener { _, value, fromUser ->
            binding.deltaEValue.text = getString(R.string.value_delta_e, value)
            if (fromUser) {
                settings.minDeltaE = value
            }
        }
        binding.transitionSlider.addOnChangeListener { _, value, fromUser ->
            binding.transitionValue.text = getString(R.string.value_ms, value.toInt())
            if (fromUser) {
                settings.transitionMs = value.toInt()
            }
        }
        binding.brightnessSlider.addOnChangeListener { _, value, fromUser ->
            binding.brightnessValue.text = getString(R.string.value_percent, value.toInt())
            if (fromUser) {
                settings.brightness = value.toInt()
            }
        }

        binding.backButton.setOnClickListener {
            finish()
        }

        binding.logButton.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        binding.resetButton.setOnClickListener {
            settings.resetParameters()
            showValues()
        }

        showValues()
    }

    /** Sets the sliders to the saved values; their listeners show the values as text. */
    private fun showValues() {
        binding.intervalSlider.value = settings.colorIntervalMs.toFloat()
        binding.deltaESlider.value = settings.minDeltaE
        binding.transitionSlider.value = settings.transitionMs.toFloat()
        binding.brightnessSlider.value = settings.brightness.toFloat()
    }
}
