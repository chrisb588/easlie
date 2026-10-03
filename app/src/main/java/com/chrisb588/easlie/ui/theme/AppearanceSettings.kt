package com.chrisb588.easlie.ui.theme

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.*

/** Temporary control placement; the host redesign decides its final location. */
@Composable
fun AppearanceSettings(store: AppearanceStore, floatingMode: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text("Appearance") }
    if (open) {
        val choices: @Composable () -> Unit = {
            Column {
                Appearance.entries.forEach { choice ->
                    val select = { store.select(choice); if (floatingMode) open = false }
                    Row {
                        RadioButton(selected = store.appearance == choice, onClick = select)
                        TextButton(onClick = select) {
                            Text(when (choice) {
                                Appearance.LIGHT -> "Light"
                                Appearance.DARK -> "Dark"
                                Appearance.SYSTEM -> "Use system default"
                            })
                        }
                    }
                }
            }
        }
        if (floatingMode) {
            // Keep this panel inside the overlay; a separate activity dialog has no window token.
            Surface { choices() }
        } else {
            AlertDialog(onDismissRequest = { open = false }, title = { Text("Appearance") },
                text = choices, confirmButton = {
                    TextButton(onClick = { open = false }) { Text("Close") }
                })
        }
    }
}
