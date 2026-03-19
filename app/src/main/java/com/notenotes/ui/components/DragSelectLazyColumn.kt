package com.notenotes.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun DragSelectLazyColumn(
    listState: LazyListState,
    haptic: HapticFeedback,
    onKeySelected: (Any) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: LazyListScope.() -> Unit
) {
    var isDragSelecting by remember { mutableStateOf(false) }
    var currentDragY by remember { mutableStateOf(0f) }
    val dragVisited = remember { mutableSetOf<Any>() }

    LaunchedEffect(isDragSelecting) {
        if (!isDragSelecting) return@LaunchedEffect
        val edgeZone = 120f
        while (isDragSelecting) {
            val y = currentDragY
            val viewportHeight = listState.layoutInfo.viewportSize.height.toFloat()
            val scrollAmount = when {
                y < edgeZone -> -(edgeZone - y) / edgeZone * 25f
                y > viewportHeight - edgeZone -> (y - viewportHeight + edgeZone) / edgeZone * 25f
                else -> 0f
            }
            if (scrollAmount != 0f) {
                listState.scrollBy(scrollAmount)
                                val hitKey = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { y >= it.offset && y < it.offset + it.size }
                    ?.key
                if (hitKey != null && dragVisited.add(hitKey)) {
                    onKeySelected(hitKey)
                }
            }
            delay(16)
        }
    }

    LazyColumn(
        state = listState,
        userScrollEnabled = !isDragSelecting,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(listState) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        isDragSelecting = true
                        currentDragY = offset.y
                        dragVisited.clear()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                                                val hitKey = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { offset.y >= it.offset && offset.y < it.offset + it.size }
                            ?.key
                        if (hitKey != null && dragVisited.add(hitKey)) {
                            onKeySelected(hitKey)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        currentDragY = change.position.y
                                                val hitKey = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { currentDragY >= it.offset && currentDragY < it.offset + it.size }
                            ?.key
                        if (hitKey != null && dragVisited.add(hitKey)) {
                            onKeySelected(hitKey)
                        }
                    },
                    onDragEnd = {
                        isDragSelecting = false
                        dragVisited.clear()
                    },
                    onDragCancel = {
                        isDragSelecting = false
                        dragVisited.clear()
                    }
                )
            },
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        content = content
    )
}
