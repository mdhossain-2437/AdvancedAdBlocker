package com.example.adblocker

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.adblocker.filter.FilterManager
import java.io.File

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fm = FilterManager(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val title = TextView(context).apply { text = "Settings"; textSize = 20f }
            addView(title)

            val remoteLabel = TextView(context).apply { text = "Filter subscription URLs (one per line):" }
            addView(remoteLabel)
            val remote = EditText(context).apply {
                hint = """
                    https://easylist.to/easylist/easylist.txt
                    https://easylist.to/easylist/easyprivacy.txt
                    https://ublockorigin.github.io/uAssets/filters/filters.txt
                    https://ublockorigin.github.io/uAssets/filters/privacy.txt
                """.trimIndent()
                setLines(6)
            }
            addView(remote)

            val save = Button(context).apply {
                text = "Save & Schedule updates (hourly)"
                setOnClickListener {
                    val lines = remote.text.toString()
                        .split('\n', ',', ';')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (lines.isNotEmpty()) {
                        fm.scheduleUpdates(lines, 60)
                        Toast.makeText(this@SettingsActivity, "Scheduled ${lines.size} subscriptions", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this@SettingsActivity, "Enter at least one URL", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            addView(save)

            val defaults = Button(context).apply {
                text = "Use Default Subscriptions"
                setOnClickListener {
                    remote.setText(
                        listOf(
                            "https://easylist.to/easylist/easylist.txt",
                            "https://easylist.to/easylist/easyprivacy.txt",
                            "https://ublockorigin.github.io/uAssets/filters/filters.txt",
                            "https://ublockorigin.github.io/uAssets/filters/privacy.txt"
                        ).joinToString("\n")
                    )
                }
            }
            addView(defaults)

            val export = Button(context).apply {
                text = "Export Blocklist Now"
                setOnClickListener {
                    try {
                        val out = File(filesDir, "blocked_domains.txt")
                        val count = fm.exportBlockedDomains(out)
                        Toast.makeText(this@SettingsActivity, "Exported $count domains", Toast.LENGTH_SHORT).show()
                    } catch (t: Throwable) {
                        Toast.makeText(this@SettingsActivity, "Export failed: ${t.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            addView(export)
        }

        val scroll = ScrollView(this)
        scroll.addView(container)
        setContentView(scroll)
    }
}
