package com.example.adblocker

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.adblocker.filter.FilterManager

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val title = TextView(context).apply { text = "Settings"; textSize = 20f }
            addView(title)

            val remoteLabel = TextView(context).apply { text = "Filter update URL:" }
            addView(remoteLabel)
            val remote = EditText(context).apply { hint = "https://filters.example.com/list.txt" }
            addView(remote)

            val save = Button(context).apply {
                text = "Save & Schedule updates"
                setOnClickListener {
                    val url = remote.text.toString().trim()
                    if (url.isNotEmpty()) {
                        val fm = FilterManager(this@SettingsActivity)
                        fm.scheduleUpdates(url, 60)
                        finish()
                    }
                }
            }
            addView(save)
        }
        setContentView(layout)
    }
}
