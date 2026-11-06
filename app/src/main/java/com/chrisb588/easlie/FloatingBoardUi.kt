package com.chrisb588.easlie

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.chrisb588.easlie.canvas.FullScreenCanvas

/** Window handles sit above this host; its canvas keeps a single drawing/input coordinate space. */
@Composable
internal fun FloatingBoardUi(board: BoardStore, onReturn: () -> Unit, onClose: () -> Unit,
    onAddImages: () -> Unit, onCreateBoard: () -> Unit, modifier: Modifier = Modifier,
    controlsVisible: Boolean = true, onBoardMenuVisibilityChanged: (Boolean) -> Unit = {}) {
    var boardMenu by remember { mutableStateOf(false) }
    LaunchedEffect(boardMenu) { onBoardMenuVisibilityChanged(boardMenu) }
    DisposableEffect(Unit) { onDispose { onBoardMenuVisibilityChanged(false) } }
    BoxWithConstraints(modifier.fillMaxSize().clip(RoundedCornerShape(32.dp))
        .background(MaterialTheme.colorScheme.background).testTag("floating-host")) {
        if (board.canEdit) FullScreenCanvas(board, floatingMode = true)
        AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    BoardSelector(board, boardMenu, { boardMenu = it }, {
                        board.openBoard(it.id)
                        boardMenu = false
                    }, { boardMenu = false; onCreateBoard() }, Modifier.widthIn(max = 192.dp).fillMaxWidth(), compact = true)
                }
                RoundControl(UiIcon.Fullscreen, stringResource(R.string.floating_board_return_label), onClick = onReturn, compact = true)
                RoundControl(UiIcon.Close, stringResource(R.string.floating_board_close_label), onClick = onClose, compact = true)
            }
        }
        AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 88.dp, end = 16.dp)) {
            RoundControl(UiIcon.Add, "Add images", board.canEdit, onAddImages, compact = true)
        }
        // Leave the corner resize targets and centered Deselect action clear.
        NoticeStack(board, null, Modifier.align(Alignment.BottomStart)
            .padding(start = 16.dp, end = 80.dp, bottom = 80.dp)
            .widthIn(max = 560.dp).fillMaxWidth().heightIn(max = (maxHeight - 192.dp).coerceAtLeast(48.dp)))
    }
}
