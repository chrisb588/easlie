package com.chrisb588.easlie

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.chrisb588.easlie.ui.theme.EaslieTheme
import com.chrisb588.easlie.ui.theme.cardHeaderStyle

/** Activity-owned picker and keyboard windows work independently of overlay window permissions. */
class FloatingBoardActionActivity : ComponentActivity() {
    private val board get() = (application as EaslieApplication).board
    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        board.enqueueImport(contentResolver, uris, floatingMode = true)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!FloatingBoardService.isBoardAttached) { finish(); return }
        startService(FloatingBoardService.startIntent(this).setAction(FloatingBoardService.ACTION_DIM))
        when (intent.action) {
            ADD_IMAGES -> if (savedInstanceState == null) {
                try {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (failure: RuntimeException) {
                    board.message = "Could not open image picker: ${failure.message}"
                    finish()
                }
            }
            CREATE_BOARD -> setContent {
                EaslieTheme {
                    var name by rememberSaveable { mutableStateOf(defaultBoardName(board.boards)) }
                    AlertDialog(onDismissRequest = ::finish,
                        title = { Text("Create board", style = cardHeaderStyle) },
                        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Board name") }) },
                        confirmButton = { TextButton(enabled = name.isNotBlank() && board.collectionReady,
                            onClick = { board.createBoard(name); finish() }) { Text("Create") } },
                        dismissButton = { TextButton(onClick = ::finish) { Text("Cancel") } })
                }
            }
            else -> finish()
        }
    }

    override fun onDestroy() {
        if (isFinishing && FloatingBoardService.isServiceRunning) {
            startService(FloatingBoardService.startIntent(this).setAction(FloatingBoardService.ACTION_SHOW))
        }
        super.onDestroy()
    }

    companion object {
        internal const val ADD_IMAGES = "com.chrisb588.easlie.action.FLOATING_ADD_IMAGES"
        internal const val CREATE_BOARD = "com.chrisb588.easlie.action.FLOATING_CREATE_BOARD"
    }
}
