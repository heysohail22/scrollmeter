package com.scrollmeter.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.scrollmeter.app.capture.ScreenCaptureManager
import com.scrollmeter.app.capture.ScreenCaptureService
import com.scrollmeter.app.ui.DebugDashboardScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                Surface {
                    val telemetry by ScreenCaptureManager.detector.telemetry.collectAsState()
                    val isServiceRunning = ScreenCaptureService.isRunning

                    // Permission launcher for Screen Capture (MediaProjection)
                    val captureLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                            ScreenCaptureManager.startService(
                                context = this,
                                resultCode = result.resultCode,
                                data = result.data!!
                            )
                        } else {
                            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                        }
                    }

                    // Notification permission launcher (Android 13+)
                    val notificationLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        captureLauncher.launch(projectionManager.createScreenCaptureIntent())
                    }

                    val startCaptureFlow = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
                        }
                    }

                    var isForceReelsMode by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(ScreenCaptureManager.detector.contextDetector.config.isForceReelsMode)
                    }

                    DebugDashboardScreen(
                        telemetry = telemetry,
                        isServiceRunning = telemetry.isCaptureActive,
                        isForceReelsMode = isForceReelsMode,
                        onStartCaptureClick = startCaptureFlow,
                        onStopCaptureClick = {
                            ScreenCaptureManager.stopService(this)
                        },
                        onResetCountClick = {
                            ScreenCaptureManager.resetCounter()
                        },
                        onToggleForceReelsMode = { enabled ->
                            isForceReelsMode = enabled
                            ScreenCaptureManager.detector.contextDetector.config.isForceReelsMode = enabled
                        }
                    )
                }
            }
        }
    }
}
