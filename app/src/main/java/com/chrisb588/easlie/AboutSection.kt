package com.chrisb588.easlie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.chrisb588.easlie.ui.theme.cardHeaderStyle

/** Standalone entry point retained for previews and component tests. */
@Composable
internal fun AboutSection() {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.about)) }
    if (open) AboutDialog { open = false }
}

@Composable
internal fun AboutDialog(onClose: () -> Unit) {
    val title = stringResource(R.string.about_title)
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().semantics { paneTitle = title }) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(64.dp))
                Text(stringResource(R.string.app_name), style = cardHeaderStyle, textAlign = TextAlign.Center)
                Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), textAlign = TextAlign.Center)
                TextButton(onClick = onClose) { Text(stringResource(R.string.close_about)) }
            }
        }
    }
}
