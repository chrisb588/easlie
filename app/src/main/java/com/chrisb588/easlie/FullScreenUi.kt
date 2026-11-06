package com.chrisb588.easlie

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.chrisb588.easlie.canvas.FullScreenCanvas
import com.chrisb588.easlie.ui.theme.AppearanceSettings
import com.chrisb588.easlie.ui.theme.cardHeaderStyle
import kotlinx.coroutines.delay

private val cardCorners = RoundedCornerShape(16.dp)
private val menuCorners = RoundedCornerShape(4.dp)

@Composable
internal fun FloatingBoardScreen(
    board: BoardStore,
    onAddImages: () -> Unit,
    overlayPermissionGranted: Boolean,
    errorMessage: String?,
    onOpenOverlaySettings: () -> Unit,
    onStartFloatingBoard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var home by rememberSaveable { mutableStateOf(false) }
    var boardMenu by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<StoredBoard?>(null) }
    var deleting by remember { mutableStateOf<StoredBoard?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var about by rememberSaveable { mutableStateOf(false) }
    var appearance by rememberSaveable { mutableStateOf(false) }
    var permission by rememberSaveable { mutableStateOf(false) }
    val libraryTitleStyle = MaterialTheme.typography.titleLarge.let { it.copy(fontSize = (it.fontSize.value - 1).sp) }
    val createIconSize = with(LocalDensity.current) { libraryTitleStyle.fontSize.toDp() }
    val mainMenu = home || board.activeBoardId == null
    val create = { name = defaultBoardName(board.boards); creating = true; boardMenu = false }
    val open: (StoredBoard) -> Unit = { entry ->
        board.openBoard(entry.id)
        if (entry.id == board.activeBoardId) home = false
        boardMenu = false
    }
    var observedId by rememberSaveable { mutableStateOf(board.activeBoardId) }
    LaunchedEffect(board.activeBoardId) {
        // Keep Home visible across rotation, but leave it after a successful board switch.
        if (observedId != board.activeBoardId) home = false
        observedId = board.activeBoardId
    }
    BackHandler(enabled = !mainMenu) { home = true; board.save() }

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val controlsInset = 24.dp
        val actionsMenuMaxWidth = (maxWidth - controlsInset * 2 - 64.dp).coerceAtLeast(112.dp)
        if (!mainMenu && board.canEdit) {
            FullScreenCanvas(board)
        }
        if (mainMenu) {
            LazyColumn(
                Modifier.align(Alignment.TopCenter).widthIn(max = 960.dp).fillMaxSize()
                    .padding(horizontal = 24.dp),
                contentPadding = PaddingValues(top = (maxHeight * .2f).coerceIn(112.dp, 192.dp), bottom = 160.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("easlie", style = MaterialTheme.typography.displaySmall)
                        Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Your boards", Modifier.weight(1f), style = libraryTitleStyle)
                        IconButton(onClick = create, enabled = board.collectionReady, modifier = Modifier.size(48.dp)) {
                            Icon(UiIcon.Add.vector, "Create board", Modifier.size(createIconSize))
                        }
                    }
                }
                if (board.boards.isEmpty() && board.collectionReady) {
                    item { Text("Create or open a board to continue.") }
                }
                items(board.boards, key = { it.id }) { entry ->
                    BoardLibraryRow(board, entry, board.collectionReady, { open(entry) },
                        { name = entry.name; renaming = entry }, { deleting = entry })
                }
            }
        }
        if (!mainMenu) {
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(controlsInset).padding(end = 68.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RoundControl(UiIcon.Home, "Home", onClick = { home = true; board.save() })
                BoardSelector(board, boardMenu, { boardMenu = it }, { open(it) }, create,
                    Modifier.weight(1f, fill = false).width(256.dp))
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(controlsInset), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box {
                RoundControl(UiIcon.Menu, "Actions", onClick = { actions = true })
                SideActionsMenu(actions, { actions = false }, maxWidth = actionsMenuMaxWidth) {
                    if (!mainMenu) {
                        DropdownMenuItem(text = { Text("Switch to floating mode") }, enabled = board.canEdit, onClick = {
                            actions = false
                            if (overlayPermissionGranted) onStartFloatingBoard() else permission = true
                        })
                    }
                    DropdownMenuItem(text = { Text("Change theme") }, onClick = { actions = false; appearance = true })
                    DropdownMenuItem(text = { Text("About") }, onClick = { actions = false; about = true })
                }
            }
            if (!mainMenu) RoundControl(UiIcon.Add, "Add images", board.canEdit, onAddImages)
        }
        if (!board.collectionReady && !board.migrationFailed) {
            Text("Opening board…", Modifier.align(Alignment.Center))
        }
        NoticeStack(board, errorMessage, Modifier.align(Alignment.BottomStart).padding(16.dp)
            .padding(bottom = if (mainMenu) 0.dp else 72.dp)
            .widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight * .45f))
    }
    if (creating || renaming != null) {
        val rename = renaming
        AlertDialog(shape = cardCorners,
            onDismissRequest = { creating = false; renaming = null },
            title = { Text(if (rename != null) "Rename board" else "Create board", style = cardHeaderStyle) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Board name") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank() && board.collectionReady, onClick = {
                if (rename != null) board.renameBoard(rename.id, name) else board.createBoard(name)
                creating = false; renaming = null
            }) { Text(if (rename != null) "Rename" else "Create") } },
            dismissButton = { TextButton(onClick = { creating = false; renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { entry ->
        AlertDialog(shape = cardCorners, onDismissRequest = { deleting = null }, title = { Text("Delete ${entry.name}?", style = cardHeaderStyle) },
            text = { Text("This board and its images will be removed from easlie.") },
            confirmButton = { TextButton(enabled = board.collectionReady, onClick = { board.deleteBoard(entry.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
    if (permission) {
        AlertDialog(shape = cardCorners, onDismissRequest = { permission = false }, title = { Text("Allow floating mode", style = cardHeaderStyle) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(androidx.compose.ui.res.stringResource(R.string.floating_board_permission_explanation))
                Text(androidx.compose.ui.res.stringResource(R.string.floating_board_full_screen_note))
            } },
            confirmButton = { TextButton(onClick = { permission = false; onOpenOverlaySettings() }) { Text(androidx.compose.ui.res.stringResource(R.string.open_overlay_settings)) } },
            dismissButton = { TextButton(onClick = { permission = false }) { Text("Cancel") } })
    }
    if (about) AboutDialog { about = false }
    if (appearance) (LocalContext.current.applicationContext as? EaslieApplication)?.let {
        AppearanceSettings(it.appearance, dialogOnly = true, onClose = { appearance = false })
    }
}

/** Keep Actions beside its button rather than covering the Add images control below. */
@Composable
private fun SideActionsMenu(expanded: Boolean, onDismiss: () -> Unit, maxWidth: Dp,
    content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Popup(popupPositionProvider = remember(gap) { SideMenuPositionProvider(gap) },
        onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(shape = menuCorners, color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 2.dp, modifier = Modifier.widthIn(max = maxWidth).width(IntrinsicSize.Max)) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = content)
        }
    }
}

internal class SideMenuPositionProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
        (anchorBounds.left - popupContentSize.width - gap)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        anchorBounds.top.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}

@Composable
private fun BoardLibraryRow(board: BoardStore, entry: StoredBoard, enabled: Boolean,
    onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val preview by produceState<ImageBitmap?>(null, entry.id, board.items, board.importing) {
        value = board.preview(entry.id)
    }
    Surface(shape = menuCorners, color = MaterialTheme.colorScheme.surfaceContainer,
        enabled = enabled, onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = menuCorners, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(56.dp)) {
                if (preview != null) Image(preview!!, "Preview of ${entry.name}")
                else Box(contentAlignment = Alignment.Center) { Icon(UiIcon.Image.vector, null, Modifier.size(24.dp)) }
            }
            Text(entry.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box {
                IconButton(enabled = enabled, onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(UiIcon.More.vector, "Board actions for ${entry.name}")
                }
                DropdownMenu(menu, { menu = false }, shape = menuCorners) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
internal fun RoundControl(icon: UiIcon, label: String, enabled: Boolean = true, onClick: () -> Unit, compact: Boolean = false) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = Modifier.size(if (compact) 48.dp else 56.dp)) {
        Icon(icon.vector, label, Modifier.size(if (compact) 20.dp else 24.dp))
    }
}

private data class Notice(val feedback: BoardFeedback, val boardId: String?, val loadGeneration: Long)

@Composable
internal fun NoticeStack(board: BoardStore, error: String?, modifier: Modifier = Modifier) {
    val notices = remember { mutableStateListOf<Notice>() }
    var externalId by remember { mutableLongStateOf(-1) }
    LaunchedEffect(board.activeBoardId, board.loadGeneration, board.migrationFailed, board.canRetryImport) {
        notices.removeAll {
            when (it.feedback.kind) {
                BoardMessageKind.Migration -> !board.migrationFailed
                BoardMessageKind.Import -> !board.canRetryImport
                BoardMessageKind.BoardRecovery -> it.boardId != board.activeBoardId || it.loadGeneration != board.loadGeneration
                else -> false
            }
        }
    }
    LaunchedEffect(board.feedback) {
        board.feedback?.let { feedback ->
            // Coalesce repeated reports and supersede stale success feedback.
            notices.removeAll {
                it.feedback.text == feedback.text || it.feedback.kind == BoardMessageKind.Success ||
                    (feedback.kind == BoardMessageKind.Migration && it.feedback.kind == BoardMessageKind.Migration)
            }
            if ((feedback.kind != BoardMessageKind.Import || board.canRetryImport) &&
                (feedback.kind != BoardMessageKind.Migration || board.migrationFailed)) {
                notices.add(Notice(feedback, board.activeBoardId, board.loadGeneration))
            }
        }
    }
    LaunchedEffect(error) {
        error?.let { notices.add(Notice(BoardFeedback(externalId--, it, BoardMessageKind.Error), null, board.loadGeneration)) }
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (board.importing) NoticeSurface("Importing images…", UiIcon.Image) { CircularProgressIndicator(Modifier.size(24.dp)) }
        if (board.awaitingImportDestination) NoticeSurface("Shared images are waiting. Create or open their destination board.", UiIcon.Warning)
        if (board.migrationFailed && notices.none { it.feedback.kind == BoardMessageKind.Migration }) {
            NoticeSurface("Your saved board needs recovery before editing.", UiIcon.Error) {
                IconButton(onClick = board::retryMigration) { Icon(UiIcon.Retry.vector, "Retry") }
            }
        }
        val importNotice = notices.lastOrNull { it.feedback.kind == BoardMessageKind.Import }
        if (board.canRetryImport && importNotice == null) {
            NoticeSurface("Some images could not be imported.", UiIcon.Warning) {
                IconButton(enabled = !board.importing, onClick = board::retryImport) { Icon(UiIcon.Retry.vector, "Retry failed images") }
            }
        }
        notices.asReversed().forEach { notice ->
            val feedback = notice.feedback
            key(feedback.id) {
                var visible by remember { mutableStateOf(true) }
                LaunchedEffect(feedback.id) {
                    if (feedback.kind in setOf(BoardMessageKind.Success, BoardMessageKind.Warning, BoardMessageKind.Error)) {
                        delay(5000)
                        visible = false
                        delay(300)
                        notices.remove(notice)
                        if (board.feedback?.id == feedback.id) board.message = null
                    }
                }
                AnimatedVisibility(visible, exit = fadeOut()) {
                    NoticeSurface(feedback.text, when (feedback.kind) {
                        BoardMessageKind.Success -> UiIcon.Image
                        BoardMessageKind.Warning, BoardMessageKind.Import, BoardMessageKind.BoardRecovery -> UiIcon.Warning
                        else -> UiIcon.Error
                    }) {
                        if (feedback.kind == BoardMessageKind.Migration || (notice == importNotice && board.canRetryImport)) {
                            IconButton(enabled = !board.importing, onClick = {
                                if (feedback.kind == BoardMessageKind.Migration) board.retryMigration() else board.retryImport()
                            }) { Icon(UiIcon.Retry.vector, if (feedback.kind == BoardMessageKind.Migration) "Retry" else "Retry failed images") }
                        }
                        IconButton(onClick = {
                            notices.remove(notice)
                            if (board.feedback?.id == feedback.id) board.message = null
                        }) { Icon(UiIcon.Close.vector, "Dismiss") }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeSurface(text: String, icon: UiIcon, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(shape = menuCorners, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon.vector, null, Modifier.size(24.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}

internal enum class UiIcon(val path: String) {
    Home("M3,10 L12,3 L21,10 L21,21 L15,21 L15,14 L9,14 L9,21 L3,21 Z"),
    Menu("M3,5 L21,5 L21,7 L3,7 Z M3,11 L21,11 L21,13 L3,13 Z M3,17 L21,17 L21,19 L3,19 Z"),
    Add("M11,3 L13,3 L13,11 L21,11 L21,13 L13,13 L13,21 L11,21 L11,13 L3,13 L3,11 L11,11 Z"),
    Down("M5,8 L12,15 L19,8 L20,10 L12,18 L4,10 Z"),
    More("M10,3 L14,3 L14,7 L10,7 Z M10,10 L14,10 L14,14 L10,14 Z M10,17 L14,17 L14,21 L10,21 Z"),
    Image("M3,3 L21,3 L21,21 L3,21 Z M5,19 L19,19 L19,17 L14,11 L10,15 L8,13 Z M6,6 L6,9 L9,9 L9,6 Z"),
    Warning("M12,2 L23,22 L1,22 Z M11,8 L11,15 L13,15 L13,8 Z M11,17 L11,19 L13,19 L13,17 Z"),
    Error("M12,1 A11,11 0,1 0,12 23 A11,11 0,1 0,12 1 Z M11,6 L11,14 L13,14 L13,6 Z M11,17 L11,19 L13,19 L13,17 Z"),
    Retry("M20,4 L20,10 L14,10 L16.5,7.5 A6,6 0,1 0,18,14 L20,14 A8,8 0,1 1,18,6 Z"),
    Fullscreen("M3,3 L9,3 L9,5 L5,5 L5,9 L3,9 Z M15,3 L21,3 L21,9 L19,9 L19,5 L15,5 Z M3,15 L5,15 L5,19 L9,19 L9,21 L3,21 Z M19,15 L21,15 L21,21 L15,21 L15,19 L19,19 Z"),
    Close("M5,4 L12,11 L19,4 L20,5 L13,12 L20,19 L19,20 L12,13 L5,20 L4,19 L11,12 L4,5 Z");

    val vector: ImageVector by lazy {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
                pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd).build()
    }
}

@Composable
internal fun BoardSelector(board: BoardStore, expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    onOpen: (StoredBoard) -> Unit, onCreate: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val active = board.boards.firstOrNull { it.id == board.activeBoardId }
    val newBoardIconSize = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.fontSize.toDp() }
    BoxWithConstraints(modifier) {
        val selectorWidth = maxWidth
        Surface(modifier = Modifier.fillMaxWidth(), shape = CircleShape, enabled = board.collectionReady,
            color = MaterialTheme.colorScheme.surfaceContainer, onClick = { onExpandedChange(true) }) {
            Row(Modifier.heightIn(min = if (compact) 48.dp else 56.dp).padding(horizontal = if (compact) 12.dp else 16.dp)
                .semantics { contentDescription = "Open board" }, verticalAlignment = Alignment.CenterVertically) {
                Text(active?.name ?: "Open a board", Modifier.weight(1f), maxLines = 1,
                    style = if (compact) MaterialTheme.typography.bodyMedium else LocalTextStyle.current,
                    overflow = TextOverflow.Ellipsis,
                    fontStyle = if (active == null) FontStyle.Italic else FontStyle.Normal)
                Icon(UiIcon.Down.vector, null, Modifier.padding(start = 8.dp).size(if (compact) 18.dp else 20.dp))
            }
        }
        DropdownMenu(expanded, { onExpandedChange(false) }, shape = menuCorners, offset = DpOffset(0.dp, 8.dp),
            modifier = Modifier.width(selectorWidth).heightIn(max = 360.dp)) {
            board.boards.forEach { entry ->
                DropdownMenuItem(text = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    enabled = board.collectionReady, onClick = { onOpen(entry) })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("New board") },
                trailingIcon = { Icon(UiIcon.Add.vector, null, Modifier.size(newBoardIconSize)) },
                enabled = board.collectionReady, onClick = onCreate)
        }
    }
}
