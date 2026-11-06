package com.chrisb588.easlie

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.Manifest
import androidx.core.net.toUri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.chrisb588.easlie.images.sharedImageUris
import com.chrisb588.easlie.ui.theme.EaslieTheme

class MainActivity : ComponentActivity() {
    private val board get() = (application as EaslieApplication).board
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
        if (intent.action == FloatingBoardService.ACTION_RETURN_TO_APP) {
            stopService(FloatingBoardService.startIntent(this))
            waitForFloatingBoardToClose { showBoard(savedInstanceState) }
            return
        }
        if (FloatingBoardService.isBoardAttached) {
            if (savedInstanceState == null) receiveImages(intent)
            finish()
            return
        }
        showBoard(savedInstanceState)
    }

    private fun waitForFloatingBoardToClose(ready: () -> Unit) {
        if (!FloatingBoardService.isServiceRunning && !FloatingBoardService.isBoardAttached) {
            ready()
        } else {
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) waitForFloatingBoardToClose(ready)
            }, 50)
        }
    }

    private fun showBoard(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        refreshOverlayPermission()
        if (savedInstanceState == null) receiveImages(intent)
        setContent {
            EaslieTheme {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
                    board.enqueueImport(contentResolver, uris)
                }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    FloatingBoardScreen(
                        board = board,
                        onAddImages = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == FloatingBoardService.ACTION_RETURN_TO_APP) {
            stopService(FloatingBoardService.startIntent(this))
            return
        }
        receiveImages(intent)
    }

    private fun receiveImages(intent: Intent) {
        board.enqueueImport(contentResolver, sharedImageUris(intent))
    }

    override fun onStop() {
        board.save()
        super.onStop()
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
            "package:$packageName".toUri()
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
