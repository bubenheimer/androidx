/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.foundation.text.selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.max

internal class MultiWidgetSelectionDelegate(
    override val selectableId: Long,
    private val coordinatesCallback: () -> LayoutCoordinates?,
    private val layoutResultCallback: () -> TextLayoutResult?
) : Selectable {

    override fun getSelection(
        startPosition: Offset,
        endPosition: Offset,
        containerLayoutCoordinates: LayoutCoordinates,
        adjustment: SelectionAdjustment,
        ensureAtLeastOneChar: Boolean,
        previousSelection: Selection?,
        isStartHandle: Boolean
    ): Selection? {
        val layoutCoordinates = getLayoutCoordinates() ?: return null
        val textLayoutResult = layoutResultCallback() ?: return null

        val relativePosition = containerLayoutCoordinates.localPositionOf(
            layoutCoordinates, Offset.Zero
        )
        val startPx = startPosition - relativePosition
        val endPx = endPosition - relativePosition

        return getTextSelectionInfo(
            textLayoutResult = textLayoutResult,
            selectionCoordinates = Pair(startPx, endPx),
            selectable = this,
            adjustment = adjustment,
            previousSelection = previousSelection,
            isStartHandle = isStartHandle,
            ensureAtLeastOneChar = ensureAtLeastOneChar
        )
    }

    override fun getHandlePosition(selection: Selection, isStartHandle: Boolean): Offset {
        // Check if the selection handles's selectable is the current selectable.
        if (isStartHandle && selection.start.selectableId != this.selectableId ||
            !isStartHandle && selection.end.selectableId != this.selectableId
        ) {
            return Offset.Zero
        }

        if (getLayoutCoordinates() == null) return Offset.Zero

        val textLayoutResult = layoutResultCallback() ?: return Offset.Zero
        return getSelectionHandleCoordinates(
            textLayoutResult = textLayoutResult,
            offset = if (isStartHandle) selection.start.offset else selection.end.offset,
            isStart = isStartHandle,
            areHandlesCrossed = selection.handlesCrossed
        )
    }

    override fun getLayoutCoordinates(): LayoutCoordinates? {
        val layoutCoordinates = coordinatesCallback()
        if (layoutCoordinates == null || !layoutCoordinates.isAttached) return null
        return layoutCoordinates
    }

    override fun getText(): AnnotatedString {
        val textLayoutResult = layoutResultCallback() ?: return AnnotatedString("")
        return textLayoutResult.layoutInput.text
    }

    override fun getBoundingBox(offset: Int): Rect {
        val textLayoutResult = layoutResultCallback() ?: return Rect.Zero
        return textLayoutResult.getBoundingBox(
            offset.coerceIn(
                0,
                textLayoutResult.layoutInput.text.text.length - 1
            )
        )
    }
}

/**
 * Return information about the current selection in the Text.
 *
 * @param textLayoutResult a result of the text layout.
 * @param selectionCoordinates The positions of the start and end of the selection in Text
 * composable coordinate system.
 * @param selectable current [Selectable] for which the [Selection] is being calculated
 * @param adjustment [Selection] range is adjusted according to this param
 * @param ensureAtLeastOneChar should selection contain at least one character
 * @param previousSelection previous selection result
 * @param isStartHandle true if the start handle is being dragged
 *
 * @return [Selection] of the current composable, or null if the composable is not selected.
 */
internal fun getTextSelectionInfo(
    textLayoutResult: TextLayoutResult,
    selectionCoordinates: Pair<Offset, Offset>,
    selectable: Selectable,
    adjustment: SelectionAdjustment,
    ensureAtLeastOneChar: Boolean = true,
    previousSelection: Selection? = null,
    isStartHandle: Boolean = true
): Selection? {
    val startPosition = selectionCoordinates.first
    val endPosition = selectionCoordinates.second

    val bounds = Rect(
        0.0f,
        0.0f,
        textLayoutResult.size.width.toFloat(),
        textLayoutResult.size.height.toFloat()
    )

    val lastOffset = textLayoutResult.layoutInput.text.text.length

    val containsWholeSelectionStart =
        bounds.contains(Offset(startPosition.x, startPosition.y))

    val containsWholeSelectionEnd =
        bounds.contains(Offset(endPosition.x, endPosition.y))

    val rawStartOffset =
        if (containsWholeSelectionStart)
            textLayoutResult.getOffsetForPosition(startPosition).coerceIn(0, lastOffset)
        else
        // If the composable is selected, the start offset cannot be -1 for this composable. If the
        // final start offset is still -1, it means this composable is not selected.
            -1
    val rawEndOffset =
        if (containsWholeSelectionEnd)
            textLayoutResult.getOffsetForPosition(endPosition).coerceIn(0, lastOffset)
        else
        // If the composable is selected, the end offset cannot be -1 for this composable. If the
        // final end offset is still -1, it means this composable is not selected.
            -1

    return getRefinedSelectionInfo(
        rawStartOffset = rawStartOffset,
        rawEndOffset = rawEndOffset,
        containsWholeSelectionStart = containsWholeSelectionStart,
        containsWholeSelectionEnd = containsWholeSelectionEnd,
        startPosition = startPosition,
        endPosition = endPosition,
        bounds = bounds,
        textLayoutResult = textLayoutResult,
        lastOffset = lastOffset,
        selectable = selectable,
        adjustment = adjustment,
        previousSelection = previousSelection,
        isStartHandle = isStartHandle,
        ensureAtLeastOneChar = ensureAtLeastOneChar
    )
}

/**
 * This method refines the selection info by processing the initial raw selection info.
 *
 * @param rawStartOffset unprocessed start offset calculated directly from input position
 * @param rawEndOffset unprocessed end offset calculated directly from input position
 * @param containsWholeSelectionStart a flag to check if current composable contains the overall
 * selection start
 * @param containsWholeSelectionEnd a flag to check if current composable contains the overall
 * selection end
 * @param startPosition graphical position of the start of the selection, in composable's
 * coordinates.
 * @param endPosition graphical position of the end of the selection, in composable's coordinates.
 * @param bounds bounds of the current composable
 * @param textLayoutResult a result of the text layout.
 * @param lastOffset last offset of the text. It's actually the length of the text.
 * @param selectable current [Selectable] for which the [Selection] is being calculated
 * @param adjustment [Selection] range is adjusted according to this param
 * @param ensureAtLeastOneChar should selection contain at least one character
 * @param previousSelection previous selection result
 * @param isStartHandle true if the start handle is being dragged
 *
 * @return [Selection] of the current composable, or null if the composable is not selected.
 */
private fun getRefinedSelectionInfo(
    rawStartOffset: Int,
    rawEndOffset: Int,
    containsWholeSelectionStart: Boolean,
    containsWholeSelectionEnd: Boolean,
    startPosition: Offset,
    endPosition: Offset,
    bounds: Rect,
    textLayoutResult: TextLayoutResult,
    lastOffset: Int,
    selectable: Selectable,
    adjustment: SelectionAdjustment,
    ensureAtLeastOneChar: Boolean,
    previousSelection: Selection? = null,
    isStartHandle: Boolean = true
): Selection? {
    val shouldProcessAsSinglecomposable =
        containsWholeSelectionStart && containsWholeSelectionEnd

    val (startOffset, endOffset, handlesCrossed) =
        if (shouldProcessAsSinglecomposable) {
            processAsSingleComposable(
                rawStartOffset = rawStartOffset,
                rawEndOffset = rawEndOffset,
                previousSelection = previousSelection?.toTextRange(),
                isStartHandle = isStartHandle,
                lastOffset = lastOffset,
                handlesCrossed = previousSelection?.handlesCrossed ?: false,
                ensureAtLeastOneChar = ensureAtLeastOneChar
            )
        } else {
            processCrossComposable(
                startPosition = startPosition,
                endPosition = endPosition,
                rawStartOffset = rawStartOffset,
                rawEndOffset = rawEndOffset,
                lastOffset = lastOffset,
                bounds = bounds,
                containsWholeSelectionStart = containsWholeSelectionStart,
                containsWholeSelectionEnd = containsWholeSelectionEnd
            )
        }
    // nothing is selected
    if (startOffset == -1 && endOffset == -1) return null

    val (start, end) = adjustSelection(
        textLayoutResult = textLayoutResult,
        startOffset = startOffset,
        endOffset = endOffset,
        handlesCrossed = handlesCrossed,
        adjustment = adjustment
    )

    return getAssembledSelectionInfo(
        startOffset = start,
        endOffset = end,
        handlesCrossed = handlesCrossed,
        selectableId = selectable.selectableId,
        textLayoutResult = textLayoutResult
    )
}

/**
 * [Selection] contains a lot of parameters. It looks more clean to assemble an object of this
 * class in a separate method.
 *
 * @param startOffset the final start offset to be returned.
 * @param endOffset the final end offset to be returned.
 * @param handlesCrossed true if the selection handles are crossed
 * @param selectableId the id of the current [Selectable] for which the [Selection] is being
 * calculated
 * @param textLayoutResult a result of the text layout.
 *
 * @return an assembled object of [Selection] using the offered selection info.
 */
private fun getAssembledSelectionInfo(
    startOffset: Int,
    endOffset: Int,
    handlesCrossed: Boolean,
    selectableId: Long,
    textLayoutResult: TextLayoutResult
): Selection {
    return Selection(
        start = Selection.AnchorInfo(
            direction = textLayoutResult.getBidiRunDirection(startOffset),
            offset = startOffset,
            selectableId = selectableId
        ),
        end = Selection.AnchorInfo(
            direction = textLayoutResult.getBidiRunDirection(max(endOffset - 1, 0)),
            offset = endOffset,
            selectableId = selectableId
        ),
        handlesCrossed = handlesCrossed
    )
}
