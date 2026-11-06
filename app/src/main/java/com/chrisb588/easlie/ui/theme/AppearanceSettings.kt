package com.chrisb588.easlie.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.*

/** The floating host keeps its inline panel; full-screen opens it from Actions. */
@Composable
fun AppearanceSettings(
    store: AppearanceStore,
    floatingMode: Boolean = false,
    dialogOnly: Boolean = false,
    onClose: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    val close = { open = false; onClose() }
    if (!dialogOnly) TextButton(onClick = { open = !open }) { Text("Appearance") }
    if (open || dialogOnly) {
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
            AlertDialog(shape = RoundedCornerShape(16.dp),
                onDismissRequest = close, title = { Text("Appearance", style = cardHeaderStyle) },
                text = choices, confirmButton = {
                    TextButton(onClick = close) { Text("Close") }
                })
        }
    }
}
