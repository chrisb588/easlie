package com.chrisb588.easlie

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
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

/** Standalone entry point retained for previews and component tests. */
@Composable
internal fun AboutSection() {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.about)) }
    if (open) AboutDialog { open = false }
}

@Composable
internal fun AboutDialog(onClose: () -> Unit) {
    AlertDialog(
        shape = RoundedCornerShape(16.dp),
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                )
                Text(stringResource(R.string.about_name, stringResource(R.string.app_name)))
                Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.close_about)) }
        }
    )
}
