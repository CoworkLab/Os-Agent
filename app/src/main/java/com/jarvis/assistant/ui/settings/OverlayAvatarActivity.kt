package com.jarvis.assistant.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.RadioGroup
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager

/**
 * Floating avatar settings. The agent ships one companion — the ghost — which the
 * agent can also reposition itself while a task is running.
 */
class OverlayAvatarActivity : AppCompatActivity() {

    private val preferences get() = JarvisApp.instance.preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay_avatar)
        ThemeManager.applyDynamicBackground(findViewById(R.id.overlayAvatarRoot))

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        val overlaySwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchOverlayEnabled)
        val modeGroup = findViewById<RadioGroup>(R.id.radioOverlayMode)

        overlaySwitch.isChecked = preferences.isTaskOverlayEnabled
        modeGroup.check(
            if (preferences.taskOverlayMode == "working") R.id.rbOverlayWorking else R.id.rbOverlayAlways
        )

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            preferences.taskOverlayMode = if (checkedId == R.id.rbOverlayWorking) "working" else "always"
        }

        setEnabled(modeGroup, preferences.isTaskOverlayEnabled)
        overlaySwitch.setOnCheckedChangeListener { _, enabled ->
            preferences.isTaskOverlayEnabled = enabled
            setEnabled(modeGroup, enabled)
        }
    }

    private fun setEnabled(group: RadioGroup, enabled: Boolean) {
        group.isEnabled = enabled
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = enabled
    }
}
