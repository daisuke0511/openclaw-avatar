package com.openclaw.avatar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnPermission: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus     = findViewById(R.id.tvStatus)
        btnPermission = findViewById(R.id.btnPermission)
        btnStart     = findViewById(R.id.btnStart)
        btnStop      = findViewById(R.id.btnStop)

        btnPermission.setOnClickListener { openOverlaySettings() }
        btnStart.setOnClickListener     { startAvatarService() }
        btnStop.setOnClickListener      { stopAvatarService() }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun hasOverlayPermission() = Settings.canDrawOverlays(this)

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermLauncher.launch(intent)
    }

    private fun startAvatarService() {
        if (!hasOverlayPermission()) return
        startForegroundService(Intent(this, AvatarService::class.java))
    }

    private fun stopAvatarService() {
        stopService(Intent(this, AvatarService::class.java))
    }

    private fun refreshUi() {
        val hasPerm = hasOverlayPermission()
        tvStatus.text = if (hasPerm) "Overlay permission granted" else "Overlay permission required"
        btnPermission.isEnabled = !hasPerm
        btnStart.isEnabled      = hasPerm
    }
}
