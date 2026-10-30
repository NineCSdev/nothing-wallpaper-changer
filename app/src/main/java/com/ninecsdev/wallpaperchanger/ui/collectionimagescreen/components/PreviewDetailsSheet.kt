package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components

import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.source.SourceDetails
import com.ninecsdev.wallpaperchanger.model.EditParams
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.SourceType
import com.ninecsdev.wallpaperchanger.ui.components.NothingSheetHandle
import com.ninecsdev.wallpaperchanger.ui.components.cropRuleLabelRes
import com.ninecsdev.wallpaperchanger.ui.components.nothingSheetSurface
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme
import com.ninecsdev.wallpaperchanger.ui.walleditscreen.components.formatOffsetPercent
import com.ninecsdev.wallpaperchanger.ui.walleditscreen.components.formatZoom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val READING = "…"

private const val UNKNOWN = "—"

private const val DetailsFlingVelocity = 1000f // px/s

/** Every page carries its own sheet, and they all share this one position. */
@Stable
internal class PreviewDetailsSheetState(private val scope: CoroutineScope) {

    /** 0 hidden, 1 fully open. A fraction rather than pixels, so a page with fewer rows stays open. */
    var fraction by mutableFloatStateOf(0f)
        private set

    private var dragging by mutableStateOf(false)
    private var settleJob: Job? = null
    // Travel that arrived before the sheet's first layout, applied once there is a height to scale by
    private var pendingDelta = 0f

    /** The current page's sheet height. Zero until it has been laid out once. */
    internal var height by mutableFloatStateOf(0f)

    private val easedHeight = Animatable(0f)

    val isShown: Boolean get() = dragging || fraction > 0f

    /** How much of the sheet is on screen, in px. Eased, so a swipe between sheets of different heights doesn't jump. */
    val shownHeight: Float get() = easedHeight.value * fraction

    internal suspend fun easeHeight() {
        snapshotFlow { height }.collectLatest { target ->
            if (easedHeight.value == 0f) easedHeight.snapTo(target) else easedHeight.animateTo(target)
        }
    }

    fun onDragStart() {
        settleJob?.cancel()
        dragging = true
    }

    /** [deltaY] is the finger's travel, positive downwards. */
    fun onDrag(deltaY: Float) {
        if (height <= 0f) {
            pendingDelta += deltaY
            return
        }
        fraction = (fraction - (deltaY + pendingDelta) / height).coerceIn(0f, 1f)
        pendingDelta = 0f
    }

    fun onDragEnd(velocityY: Float) {
        dragging = false
        pendingDelta = 0f
        val open = when {
            velocityY < -DetailsFlingVelocity -> true
            velocityY > DetailsFlingVelocity -> false
            else -> fraction > 0.5f
        }
        animateTo(if (open) 1f else 0f)
    }

    fun close() = animateTo(0f)

    fun reset() {
        settleJob?.cancel()
        dragging = false
        pendingDelta = 0f
        fraction = 0f
    }

    private fun animateTo(target: Float) {
        settleJob?.cancel()
        if (fraction == target) return
        settleJob = scope.launch {
            animate(initialValue = fraction, targetValue = target) { value, _ -> fraction = value }
        }
    }
}

@Composable
internal fun rememberPreviewDetailsSheetState(): PreviewDetailsSheetState {
    val scope = rememberCoroutineScope()
    val state = remember(scope) { PreviewDetailsSheetState(scope) }
    LaunchedEffect(state) { state.easeHeight() }
    return state
}

/** Only the current page's sheet [reports its height][reportsHeight], which is what drags are scaled by. */
@Composable
internal fun PreviewDetailsSheet(
    state: PreviewDetailsSheetState,
    wallpaper: WallpaperImage,
    cropRule: CropRule,
    loadSourceDetails: suspend (WallpaperImage) -> SourceDetails,
    reportsHeight: Boolean,
    modifier: Modifier = Modifier
) {
    // Tagged with its uri, so a page whose read has not landed yet never shows the last page's values
    val loaded by produceState<Pair<String, SourceDetails>?>(null, wallpaper.uriString, wallpaper.isAvailable) {
        val details = if (wallpaper.isAvailable) loadSourceDetails(wallpaper) else SourceDetails()
        value = wallpaper.uriString to details
    }
    val details = loaded?.takeIf { it.first == wallpaper.uriString }?.second

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                // Slid in placement, so it reads the fraction without recomposing on every frame
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    if (reportsHeight) state.height = placeable.height.toFloat()
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelativeWithLayer(0, 0) {
                            translationY = placeable.height * (1f - state.fraction)
                        }
                    }
                }
                .draggable(
                    state = rememberDraggableState { state.onDrag(it) },
                    orientation = Orientation.Vertical,
                    onDragStarted = { state.onDragStart() },
                    onDragStopped = { state.onDragEnd(it) }
                )
                .nothingSheetSurface()
        ) {
            NothingSheetHandle()

            PreviewDetailsContent(
                wallpaper = wallpaper,
                details = details,
                cropRule = cropRule
            )
        }
    }
}

/** [details] is null while it is being read. */
@Composable
private fun PreviewDetailsContent(
    wallpaper: WallpaperImage,
    details: SourceDetails?,
    cropRule: CropRule
) {
    val context = LocalContext.current

    val folderName = details?.folderName
    val sourceLabel = when (wallpaper.sourceType) {
        SourceType.INTERNALIZED -> stringResource(R.string.preview_details_source_local_copy)
        SourceType.MEDIA_STORE -> stringResource(R.string.preview_details_source_photo)
        SourceType.FOLDER_DOC ->
            if (folderName != null) stringResource(R.string.preview_details_source_folder_named, folderName)
            else stringResource(R.string.preview_details_source_folder)
    }
    // A local copy's file is app-internal, named by the app and always re-encoded to WebP, so it
    // is titled by what it is. A reference the source cannot name falls back to the same.
    val isLocalCopy = wallpaper.sourceType == SourceType.INTERNALIZED
    val title = when {
        !wallpaper.isAvailable -> stringResource(R.string.preview_details_unavailable)
        isLocalCopy -> sourceLabel
        details == null -> READING
        else -> details.displayName?.let(::withoutImageExtension) ?: sourceLabel
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 24.dp)
            .navigationBarsPadding()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = NothingType.titleCaps,
                color = if (wallpaper.isAvailable) NothingWhite else NothingRed,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (title != sourceLabel && title != stringResource(R.string.preview_details_unavailable)) {
                Text(
                    text = sourceLabel,
                    style = NothingType.metaLabel,
                    color = NothingWhite.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RowDivider()
            // A local copy is always WebP, so only its size says anything
            DetailRow(
                label = stringResource(R.string.preview_details_file),
                value = when (details) {
                    null -> READING
                    else -> listOfNotNull(
                        details.mimeType?.takeUnless { isLocalCopy }?.let(::formatLabel),
                        details.sizeBytes?.let { Formatter.formatFileSize(context, it) }
                    ).joinToString(" · ").ifEmpty { UNKNOWN }
                }
            )
            RowDivider()

            val resolution = details?.let { d -> if (d.width != null && d.height != null) d.width to d.height else null }
            DetailRow(
                label = stringResource(R.string.preview_details_resolution),
                value = when {
                    details == null -> READING
                    resolution == null -> UNKNOWN
                    else -> stringResource(R.string.preview_details_resolution_value, resolution.first, resolution.second)
                }
            )
            RowDivider()

            // In the editor's own units, so the two read the same
            val edit = wallpaper.editParams
            DetailRow(
                label = stringResource(R.string.preview_details_framing),
                value = if (edit != null) {
                    stringResource(
                        R.string.preview_details_framing_edit,
                        formatZoom(edit.zoom),
                        formatOffsetPercent(edit.offsetX),
                        formatOffsetPercent(edit.offsetY)
                    )
                } else {
                    stringResource(cropRuleLabelRes(cropRule))
                }
            )
            RowDivider()

            DetailRow(
                label = stringResource(R.string.preview_details_added),
                value = formatDate(wallpaper.addedAt)
            )
        }
    }
}

@Composable
private fun RowDivider() { HorizontalDivider(color = NothingWhite.copy(alpha = 0.10f), thickness = 1.dp) }

@Composable
private fun DetailRow(
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = NothingType.overline,
            color = NothingWhite.copy(alpha = 0.45f)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = NothingType.rowTitle,
            color = NothingWhite,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** "PXL_0001.jpg" reads as "PXL_0001": the File row already names the format. Other dots stay. */
private fun withoutImageExtension(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    val isImage = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.startsWith("image/") == true
    return if (isImage) name.substringBeforeLast('.').ifEmpty { name } else name
}

private fun formatLabel(mimeType: String): String = mimeType.substringAfter('/').uppercase()

private fun formatDate(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

@Composable
private fun DetailsContentPreview(
    wallpaper: WallpaperImage,
    details: SourceDetails,
    cropRule: CropRule
) {
    WallpaperChangerTheme {
        Box(modifier = Modifier.fillMaxSize().padding(top = 200.dp), contentAlignment = Alignment.BottomCenter) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().nothingSheetSurface()
            ) {
                NothingSheetHandle()
                PreviewDetailsContent(wallpaper, details, cropRule)
            }
        }
    }
}

@Preview(showSystemUi = true, backgroundColor = 0xFF444444, showBackground = true, name = "Preview details: folder reference, edited")
@Composable
private fun PreviewDetailsSheetPreview() {
    DetailsContentPreview(
        wallpaper = WallpaperImage(
            collectionId = 1,
            uriString = "",
            sourceType = SourceType.FOLDER_DOC,
            editParams = EditParams(zoom = 1.6f, offsetX = 0.1f, offsetY = -0.25f)
        ),
        details = SourceDetails(
            displayName = "PXL_20260912_183344.jpg",
            sizeBytes = 3_400_000,
            mimeType = "image/jpeg",
            width = 3072,
            height = 4080,
            folderName = "Camera"
        ),
        cropRule = CropRule.CENTER
    )
}

@Preview(showSystemUi = true, backgroundColor = 0xFF444444, showBackground = true, name = "Preview details: unavailable photo reference")
@Composable
private fun PreviewDetailsSheetUnavailablePreview() {
    DetailsContentPreview(
        wallpaper = WallpaperImage(
            collectionId = 1,
            uriString = "",
            sourceType = SourceType.MEDIA_STORE,
            isAvailable = false
        ),
        details = SourceDetails(),
        cropRule = CropRule.LEFT
    )
}

@Preview(showSystemUi = true, backgroundColor = 0xFF444444, showBackground = true, name = "Preview details: local copy")
@Composable
private fun PreviewDetailsSheetLocalCopyPreview() {
    DetailsContentPreview(
        wallpaper = WallpaperImage(collectionId = 1, uriString = "", sourceType = SourceType.INTERNALIZED),
        details = SourceDetails(sizeBytes = 1_200_000, mimeType = "image/webp", width = 1080, height = 2400),
        cropRule = CropRule.FIT
    )
}
