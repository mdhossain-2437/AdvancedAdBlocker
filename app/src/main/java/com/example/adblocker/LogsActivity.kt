package com.example.adblocker

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LogsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val title = TextView(context).apply { text = "Logs"; textSize = 20f }
            addView(title)
            val info = TextView(context).apply { text = "Runtime logs are written to logcat. Implement persistent log storage for production." }
            addView(info)
        }
        setContentView(layout)
    }
}
