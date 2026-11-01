@file:OptIn(ExperimentalFoundationApi::class)

package info.cemu.cemu.games.list

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import info.cemu.cemu.R
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.nativeinterface.NativeGameTitles.Game
import kotlinx.coroutines.launch

// colors of the Wii U Menu, it always looks like this regardless of the app theme
private val MenuBackgroundTop = Color(0xFFF9F9F9)
private val MenuBackgroundBottom = Color(0xFFDADADA)
private val MenuStripe = Color(0x0A000000)
private val MenuSelection = Color(0xFF2EC4F3)
private val MenuEmptySlot = Color(0x12000000)
private val MenuEmptySlotEdge = Color(0xB3FFFFFF)
private val MenuText = Color(0xFF3C3C3C)
private val MenuArrow = Color(0xFF8C8C8C)
private val MenuDot = Color(0xFFBDBDBD)

private val PageArrowWidth = 56.dp
private val BubbleSpace = 44.dp
private val PageIndicatorSpace = 28.dp

private fun Modifier.wiiUMenuBackground() = drawBehind {
    drawRect(Brush.verticalGradient(listOf(MenuBackgroundTop, MenuBackgroundBottom)))
    val spacing = 5.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawLine(MenuStripe, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        y += spacing
    }
}

// the games as icons on pages of slots like the Wii U Menu. Tap or A starts a game, a long press opens its menu
@Composable
fun WiiUGameGrid(
    games: List<Game>,
    gridSize: Int,
    startGame: (Game) -> Unit,
    gameMenu: @Composable (game: Game, expanded: Boolean, onDismissRequest: () -> Unit) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .wiiUMenuBackground(),
    ) {
        // gridSize icons go across the short side and the long side gets as many as fit, so the whole screen is used
        val gridWidth = maxWidth - PageArrowWidth * 2
        val gridHeight = maxHeight - BubbleSpace - PageIndicatorSpace
        val isLandscape = gridWidth > gridHeight
        val rows = if (isLandscape) gridSize else maxOf(1, (gridHeight / (gridWidth / gridSize)).toInt())
        val columns = if (isLandscape) maxOf(1, (gridWidth / (gridHeight / gridSize)).toInt()) else gridSize
        val pageSize = columns * rows
        val pageCount = maxOf(1, (games.size + pageSize - 1) / pageSize)
        val currentPageCount by rememberUpdatedState(pageCount)
        val pagerState = rememberPagerState { currentPageCount }
        val scope = rememberCoroutineScope()

        val slot = minOf(gridWidth / columns, gridHeight / rows)
        val tileSize = slot * 0.8f

        // after zooming stay on the page that has the first game that was shown before
        var previousPageSize by remember { mutableStateOf(pageSize) }
        LaunchedEffect(pageSize) {
            if (pageSize != previousPageSize) {
                val firstShownGame = pagerState.currentPage * previousPageSize
                previousPageSize = pageSize
                pagerState.scrollToPage((firstShownGame / pageSize).coerceIn(0, pageCount - 1))
            }
        }

        // index of the game that should get the focus once its page is shown, used when paging with a controller
        var pendingFocus by remember { mutableStateOf<Int?>(null) }

        fun goToPage(page: Int, focusIndex: Int?) {
            if (page !in 0 until pageCount) {
                return
            }
            pendingFocus = focusIndex?.coerceAtMost(games.lastIndex)
            scope.launch { pagerState.animateScrollToPage(page) }
        }

        if (games.isEmpty()) {
            Text(
                text = tr("No games found.\nAdd the folder with your games in Settings, General settings, Add game path"),
                color = MenuText,
                textAlign = TextAlign.Center,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
            )
            return@BoxWithConstraints
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = BubbleSpace, bottom = PageIndicatorSpace),
                contentAlignment = Alignment.Center,
            ) {
                Column {
                    for (row in 0 until rows) {
                        Row {
                            for (column in 0 until columns) {
                                val index = page * pageSize + row * columns + column
                                val game = games.getOrNull(index)
                                var isFocused by remember { mutableStateOf(false) }
                                Box(
                                    modifier = Modifier
                                        .size(slot)
                                        .zIndex(if (isFocused) 1f else 0f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (game == null) {
                                        EmptySlot(tileSize)
                                        return@Box
                                    }
                                    GameTile(
                                        game = game,
                                        tileSize = tileSize,
                                        requestFocus = pendingFocus == index,
                                        onFocusRequested = { pendingFocus = null },
                                        onFocusChanged = { isFocused = it },
                                        onStart = { startGame(game) },
                                        onPreviousPage = if (column == 0) {
                                            { goToPage(page - 1, index - pageSize + columns - 1) }
                                        } else null,
                                        onNextPage = if (column == columns - 1 || index == games.lastIndex) {
                                            { goToPage(page + 1, page * pageSize + pageSize + row * columns) }
                                        } else null,
                                        onPreviousPageShoulder = { goToPage(page - 1, index - pageSize) },
                                        onNextPageShoulder = { goToPage(page + 1, index + pageSize) },
                                        gameMenu = gameMenu,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (pagerState.currentPage > 0) {
            PageArrow(
                modifier = Modifier.align(Alignment.CenterStart),
                pointsLeft = true,
                onClick = { goToPage(pagerState.currentPage - 1, null) },
            )
        }
        if (pagerState.currentPage < pageCount - 1) {
            PageArrow(
                modifier = Modifier.align(Alignment.CenterEnd),
                pointsLeft = false,
                onClick = { goToPage(pagerState.currentPage + 1, null) },
            )
        }
        if (pageCount > 1) {
            PageIndicator(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
                pageCount = pageCount,
                currentPage = pagerState.currentPage,
            )
        }
    }
}

@Composable
private fun GameTile(
    game: Game,
    tileSize: Dp,
    requestFocus: Boolean,
    onFocusRequested: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onStart: () -> Unit,
    onPreviousPage: (() -> Unit)?,
    onNextPage: (() -> Unit)?,
    onPreviousPageShoulder: () -> Unit,
    onNextPageShoulder: () -> Unit,
    gameMenu: @Composable (game: Game, expanded: Boolean, onDismissRequest: () -> Unit) -> Unit,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, label = "tileScale")

    LaunchedEffect(isFocused) { onFocusChanged(isFocused) }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focusRequester.requestFocus()
            onFocusRequested()
        }
    }

    val frameShape = RoundedCornerShape(tileSize * 0.16f)
    Box(contentAlignment = Alignment.Center) {
        if (isFocused) {
            SelectionGlow(tileSize = tileSize * scale, shape = RoundedCornerShape(tileSize * scale * 0.19f))
        }
        Box(
            modifier = Modifier
                .scale(scale)
                .size(tileSize)
                .shadow(elevation = 3.dp, shape = frameShape)
                .background(Color.White, frameShape)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_BUTTON_A -> onStart().let { true }
                        AndroidKeyEvent.KEYCODE_BUTTON_X, AndroidKeyEvent.KEYCODE_BUTTON_Y -> {
                            menuExpanded = true
                            true
                        }

                        AndroidKeyEvent.KEYCODE_BUTTON_L1 -> onPreviousPageShoulder().let { true }
                        AndroidKeyEvent.KEYCODE_BUTTON_R1 -> onNextPageShoulder().let { true }
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> onPreviousPage?.invoke() != null
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> onNextPage?.invoke() != null
                        else -> false
                    }
                }
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onStart,
                    onLongClick = { menuExpanded = true },
                )
                .padding(tileSize * 0.05f),
        ) {
            val iconShape = RoundedCornerShape(tileSize * 0.12f)
            val icon = game.icon
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = game.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(iconShape),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(iconShape)
                        .background(Color(0xFFEDEDED)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_question_mark),
                        contentDescription = game.name,
                        tint = MenuArrow,
                        modifier = Modifier.size(tileSize * 0.4f),
                    )
                }
            }
            if (game.isFavorite) {
                Icon(
                    painter = painterResource(R.drawable.ic_favorite),
                    contentDescription = null,
                    tint = MenuSelection,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(tileSize * 0.24f)
                        .background(Color.White, CircleShape)
                        .padding(tileSize * 0.03f),
                )
            }
            gameMenu(game, menuExpanded) { menuExpanded = false }
        }
        if (isFocused) {
            NameBubble(
                name = game.name ?: "",
                // sits right above the icon without taking any space in the grid
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity))
                        val gap = 2.dp.roundToPx()
                        layout(0, 0) { placeable.place(-placeable.width / 2, -placeable.height - gap) }
                    },
                maxWidth = tileSize * 2.6f,
            )
        }
    }
}

// the pulsing light blue frame around the selected icon
@Composable
private fun SelectionGlow(tileSize: Dp, shape: RoundedCornerShape) {
    val transition = rememberInfiniteTransition(label = "selection")
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "selectionAlpha",
    )
    Box(
        modifier = Modifier
            .size(tileSize + 10.dp)
            .border(4.dp, MenuSelection.copy(alpha = alpha), shape)
            .padding(4.dp)
            .border(2.dp, MenuSelection.copy(alpha = alpha * 0.35f), shape)
    )
}

private val BubbleTailShape = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width / 2, size.height)
    close()
}

@Composable
private fun NameBubble(name: String, modifier: Modifier, maxWidth: Dp) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = name,
            color = MenuText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .shadow(4.dp, RoundedCornerShape(50))
                .background(Color.White, RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Box(
            modifier = Modifier
                .size(width = 14.dp, height = 8.dp)
                .background(Color.White, BubbleTailShape)
        )
    }
}

@Composable
private fun EmptySlot(tileSize: Dp) {
    val shape = RoundedCornerShape(tileSize * 0.16f)
    Box(
        modifier = Modifier
            .size(tileSize * 0.94f)
            .background(MenuEmptySlot, shape)
            .border(1.dp, MenuEmptySlotEdge, shape)
    )
}

@Composable
private fun PageArrow(modifier: Modifier, pointsLeft: Boolean, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .padding(horizontal = 8.dp)
            .size(40.dp)
            .shadow(3.dp, CircleShape)
            .background(Color.White, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = if (pointsLeft) tr("Previous page") else tr("Next page"),
            tint = MenuArrow,
            modifier = Modifier.scale(scaleX = if (pointsLeft) -1f else 1f, scaleY = 1f),
        )
    }
}

@Composable
private fun PageIndicator(modifier: Modifier, pageCount: Int, currentPage: Int) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (page in 0 until pageCount) {
            Canvas(modifier = Modifier.size(if (page == currentPage) 10.dp else 8.dp)) {
                drawCircle(if (page == currentPage) MenuSelection else MenuDot)
            }
        }
    }
}
