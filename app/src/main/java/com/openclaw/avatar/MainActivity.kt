package com.openclaw.avatar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

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

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
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

        btnPermission.setOnClickListener { grantMissingPermissions() }
        btnStart.setOnClickListener     { startAvatarService() }
        btnStop.setOnClickListener      { stopAvatarService() }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun hasOverlayPermission() = Settings.canDrawOverlays(this)
    private fun hasMicPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun grantMissingPermissions() {
        if (!hasOverlayPermission()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermLauncher.launch(intent)
        } else if (!hasMicPermission()) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startAvatarService() {
        if (!hasOverlayPermission()) return
        startForegroundService(Intent(this, AvatarService::class.java))
    }

    private fun stopAvatarService() {
        stopService(Intent(this, AvatarService::class.java))
    }

    private fun refreshUi() {
        val hasOverlay = hasOverlayPermission()
        val hasMic = hasMicPermission()
        tvStatus.text = when {
            !hasOverlay -> "Overlay permission required"
            !hasMic     -> "Microphone permission recommended for voice chat"
            else        -> "Ready — tap avatar to talk"
        }
        btnPermission.isEnabled = !hasOverlay || !hasMic
        btnPermission.text = when {
            !hasOverlay -> "Grant Overlay"
            !hasMic     -> "Grant Microphone"
            else        -> "All Set"
        }
        btnStart.isEnabled = hasOverlay
    }
}
