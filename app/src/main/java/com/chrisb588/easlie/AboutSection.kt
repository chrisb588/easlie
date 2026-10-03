package com.chrisb588.easlie

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource

/** Temporary full-screen entry point; the redesign decides its final placement. */
@Composable
internal fun AboutSection() {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.about)) }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.about)) },
            text = {
                Column {
                    Text(stringResource(R.string.app_name))
                    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.close_about))
                }
            }
        )
    }
}
