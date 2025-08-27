package com.example.adblocker

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val VPN_REQUEST_CODE = 0x1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val title = TextView(context).apply { text = "AdBlocker Starter"; textSize = 20f }
            addView(title)
            val info = TextView(context).apply { text = "System-wide blocking (VPN) prototype" }
            addView(info)

            val start = Button(context).apply {
                text = "Start VPN (Enable)"
                setOnClickListener {
                    val intent = VpnService.prepare(context)
                    if (intent != null) {
                        (context as Activity).startActivityForResult(intent, VPN_REQUEST_CODE)
                    } else {
                        startService(Intent(context, com.example.adblocker.vpn.AdBlockVpnService::class.java))
                        Toast.makeText(context, "VPN service started", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            addView(start)

            val stop = Button(context).apply {
                text = "Stop VPN (Disable)"
                setOnClickListener {
                    stopService(Intent(context, com.example.adblocker.vpn.AdBlockVpnService::class.java))
                    Toast.makeText(context, "VPN service stopped", Toast.LENGTH_SHORT).show()
                }
            }
            addView(stop)
        }

        setContentView(layout)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startService(Intent(this, com.example.adblocker.vpn.AdBlockVpnService::class.java))
            }
        }
    }
}
