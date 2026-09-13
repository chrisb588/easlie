package com.chrisb588.easlie

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.chrisb588.easlie.ui.theme.EaslieTheme

class MainActivity : ComponentActivity() {
    private var overlayPermissionGranted by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)
    private val floatingBoardResultReceiver = object : ResultReceiver(
        Handler(Looper.getMainLooper())
    ) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            when (resultCode) {
                FloatingBoardService.RESULT_ATTACHED -> finish()
                FloatingBoardService.RESULT_FAILED -> {
                    val detail = resultData?.getString(
                        FloatingBoardService.EXTRA_RESULT_MESSAGE
                    ) ?: getString(R.string.floating_board_unknown_error)
                    errorMessage = getString(
                        R.string.floating_board_start_failed,
                        detail
                    )
                }
            }
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startFloatingBoardService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshOverlayPermission()
        setContent {
            EaslieTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    FloatingBoardScreen(
                        overlayPermissionGranted = overlayPermissionGranted,
                        errorMessage = errorMessage,
                        onOpenOverlaySettings = ::openOverlaySettings,
                        onStartFloatingBoard = ::startFloatingBoard,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayPermission()
    }

    private fun refreshOverlayPermission() {
        overlayPermissionGranted = Settings.canDrawOverlays(this)
    }

    private fun openOverlaySettings() {
        errorMessage = null
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(settingsIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun startFloatingBoard() {
        if (!Settings.canDrawOverlays(this)) {
            refreshOverlayPermission()
            return
        }

        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        startFloatingBoardService()
    }

    private fun startFloatingBoardService() {
        errorMessage = null
        try {
            ContextCompat.startForegroundService(
                this,
                FloatingBoardService.startIntent(this).putExtra(
                    FloatingBoardService.EXTRA_START_RESULT_RECEIVER,
                    floatingBoardResultReceiver
                )
            )
        } catch (exception: RuntimeException) {
            errorMessage = getString(
                R.string.floating_board_start_failed,
                exception.message ?: exception.javaClass.simpleName
            )
        }
    }
}

@Composable
private fun FloatingBoardScreen(
    overlayPermissionGranted: Boolean,
    errorMessage: String?,
    onOpenOverlaySettings: () -> Unit,
    onStartFloatingBoard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.floating_board_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(text = stringResource(R.string.floating_board_permission_explanation))
        Text(text = stringResource(R.string.floating_board_full_screen_note))

        if (overlayPermissionGranted) {
            Text(text = stringResource(R.string.floating_board_permission_granted))
            Button(onClick = onStartFloatingBoard) {
                Text(text = stringResource(R.string.start_floating_board))
            }
        } else {
            Text(text = stringResource(R.string.floating_board_permission_denied))
            Button(onClick = onOpenOverlaySettings) {
                Text(text = stringResource(R.string.open_overlay_settings))
            }
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FloatingBoardScreenPreview() {
    EaslieTheme {
        FloatingBoardScreen(
            overlayPermissionGranted = false,
            errorMessage = null,
            onOpenOverlaySettings = {},
            onStartFloatingBoard = {}
        )
    }
}
