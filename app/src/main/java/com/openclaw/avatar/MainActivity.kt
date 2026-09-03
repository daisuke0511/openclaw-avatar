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
import com.openclaw.avatar.system.AvatarAccessibilityService

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

    private fun hasAccessibilityService(): Boolean {
        // Runtime instance check; when the singleton exists the service is connected.
        if (AvatarAccessibilityService.isEnabled()) return true
        // Fallback: parse the enabled services list from Settings.
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabled.contains("$packageName/.system.AvatarAccessibilityService")
    }

    private fun grantMissingPermissions() {
        if (!hasOverlayPermission()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermLauncher.launch(intent)
        } else if (!hasMicPermission()) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (!hasAccessibilityService()) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
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
        val hasA11y = hasAccessibilityService()
        tvStatus.text = when {
            !hasOverlay -> "Overlay permission required"
            !hasMic     -> "Microphone permission recommended for voice chat"
            !hasA11y    -> "Accessibility recommended so I can tap the screen"
            else        -> "Ready — tap avatar to talk"
        }
        btnPermission.isEnabled = !hasOverlay || !hasMic || !hasA11y
        btnPermission.text = when {
            !hasOverlay -> "Grant Overlay"
            !hasMic     -> "Grant Microphone"
            !hasA11y    -> "Enable Accessibility"
            else        -> "All Set"
        }
        btnStart.isEnabled = hasOverlay
    }
}
