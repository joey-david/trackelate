package com.joey.trackelate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val viewModel: JournalViewModel by viewModels()
    private var launchTarget by mutableStateOf<NotificationLaunchTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        launchTarget = intent.toNotificationLaunchTarget()
        setContent {
            TrackelateApp(
                viewModel = viewModel,
                launchTarget = launchTarget,
                onLaunchTargetConsumed = { launchTarget = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchTarget = intent.toNotificationLaunchTarget()
    }

    private fun Intent.toNotificationLaunchTarget(): NotificationLaunchTarget? {
        if (action != ACTION_OPEN_GRADING) return null
        val date = getStringExtra(EXTRA_TARGET_DATE)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        } ?: LocalDate.now()
        return NotificationLaunchTarget(date)
    }
}
