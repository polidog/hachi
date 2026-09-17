package dev.polidog.hachi

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** ponytail: one field for now -- the rest of the settings land with the features that need them. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        val pad = (resources.displayMetrics.density * 16).toInt()
        val key = EditText(this).apply {
            hint = getString(R.string.settings_api_key)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(settings.geminiKey)
        }
        val cap = EditText(this).apply {
            hint = getString(R.string.settings_daily_cap)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(settings.get("dailyCap", Settings.DEFAULT_DAILY_CAP))
        }
        val save = Button(this).apply {
            text = getString(R.string.settings_save)
            setOnClickListener {
                settings.setSecret("geminiKey", key.text.toString().trim())
                settings.set("dailyCap", cap.text.toString().trim().ifBlank { "0" })
                Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(TextView(context).apply { text = getString(R.string.settings_title); textSize = 22f })
                addView(key)
                addView(TextView(context).apply { text = getString(R.string.settings_daily_cap) })
                addView(cap)
                addView(save)
            }
        )
    }
}
