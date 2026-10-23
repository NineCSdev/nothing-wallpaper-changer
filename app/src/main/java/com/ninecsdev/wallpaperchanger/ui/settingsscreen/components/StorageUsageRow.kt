package com.ninecsdev.wallpaperchanger.ui.settingsscreen.components

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.logic.DeviceStorage
import com.ninecsdev.wallpaperchanger.logic.StorageUsage
import com.ninecsdev.wallpaperchanger.ui.components.SettingsRowHeader
import com.ninecsdev.wallpaperchanger.ui.settingsscreen.StorageBreakdown
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

private val OthersColor = Color(0xFF8A8A8A)
private val LocalCopiesColor = NothingWhite
// The one slice the button below can shrink, so it is the one in the accent color
private val CacheColor = NothingRed
private val FreeColor = Color(0xFF2A2A2A)

private data class Slice(val bytes: Long, val color: Color)

/**
 * The app's share of the device's storage, and the button that clears its cache.
 *
 * [storage] is null until the first count finishes; an empty bar and dashes show meanwhile.
 */
@Composable
internal fun StorageUsageRow(
    storage: StorageBreakdown?,
    isClearing: Boolean,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val format = { bytes: Long? -> bytes?.let { Formatter.formatFileSize(context, it) } ?: "—" }
    val device = storage?.device

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsRowHeader(
                title = stringResource(R.string.settings_storage_usage_title),
                subtitle = null,
                infoDialogTitle = stringResource(R.string.settings_storage_dialog_title),
                infoDialogBody = stringResource(R.string.settings_storage_dialog_body),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = format(storage?.appBytes),
                style = NothingType.rowTitle,
                color = NothingWhite
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.settings_storage_by_app),
                style = NothingType.caption,
                color = NothingWhite.copy(alpha = 0.45f)
            )
        }

        StorageBar(
            slices = listOfNotNull(
                storage?.othersBytes?.let { Slice(it, OthersColor) },
                storage?.let { Slice(it.localCopies.totalBytes, LocalCopiesColor) },
                storage?.let { Slice(it.cacheBytes, CacheColor) },
                device?.let { Slice(it.freeBytes, FreeColor) }
            ),
            modifier = Modifier.padding(top = 10.dp)
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 12.dp)
        ) {
            val localCopiesLabel = storage?.let {
                stringResource(R.string.settings_storage_local_copies_count, it.localCopies.fileCount)
            } ?: stringResource(R.string.settings_storage_local_copies)

            // Without the device's figures there is nothing to put the app's share against
            if (storage == null || device != null) {
                LegendRow {
                    LegendItem(OthersColor, stringResource(R.string.settings_storage_others), format(storage?.othersBytes), Modifier.weight(1f))
                    LegendItem(FreeColor, stringResource(R.string.settings_storage_free), format(device?.freeBytes), Modifier.weight(1f))
                }
                LegendRow {
                    LegendItem(LocalCopiesColor, localCopiesLabel, format(storage?.localCopies?.totalBytes), Modifier.weight(1f))
                    LegendItem(CacheColor, stringResource(R.string.settings_storage_cache), format(storage?.cacheBytes), Modifier.weight(1f))
                }
            } else {
                LegendRow {
                    LegendItem(LocalCopiesColor, localCopiesLabel, format(storage?.localCopies?.totalBytes), Modifier.weight(1f))
                    LegendItem(CacheColor, stringResource(R.string.settings_storage_cache), format(storage?.cacheBytes), Modifier.weight(1f))
                }
            }
        }

        TextButton(
            onClick = onClearCache,
            enabled = !isClearing && (storage?.cacheBytes ?: 0L) > 0L,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.25f)),
            colors = ButtonDefaults.textButtonColors(
                containerColor = NothingBlack,
                contentColor = NothingWhite,
                disabledContainerColor = NothingBlack,
                disabledContentColor = NothingWhite.copy(alpha = 0.3f)
            ),
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = stringResource(R.string.settings_storage_clear_cache, format(storage?.cacheBytes)),
                style = NothingType.metaLabel
            )
        }
    }
}

/**
 * One bar split proportionally between [slices], left to right. A slice that holds anything is
 * at least a sliver wide, which the app's own slices need next to a device's worth of others.
 */
@Composable
private fun StorageBar(slices: List<Slice>, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(3.dp))
    ) {
        val visible = slices.filter { it.bytes > 0 }
        val total = visible.sumOf { it.bytes }
        if (total == 0L) {
            drawRect(FreeColor)
            return@Canvas
        }

        val gap = 2.dp.toPx()
        val available = size.width - gap * (visible.size - 1)
        val widths = visible
            .map { maxOf(it.bytes.toFloat() / total * available, 3.dp.toPx()) }
            .toMutableList()
        // The slivers' extra width comes out of the widest slice
        val widest = widths.indices.maxBy { widths[it] }
        widths[widest] -= widths.sum() - available

        var x = 0f
        visible.forEachIndexed { index, slice ->
            drawRect(slice.color, Offset(x, 0f), Size(widths[index], size.height))
            x += widths[index] + gap
        }
    }
}

@Composable
private fun LegendRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable
private fun LegendItem(color: Color, label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Text(
            text = label,
            style = NothingType.caption,
            color = NothingWhite.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        )
        Text(
            text = value,
            style = NothingType.caption,
            color = NothingWhite
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, locale = "es")
@Composable
private fun StorageUsageRowPreview() {
    WallpaperChangerTheme {
        Box(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            StorageUsageRow(
                storage = StorageBreakdown(
                    localCopies = StorageUsage(totalBytes = 148_897_792, fileCount = 87),
                    cacheBytes = 31_457_280,
                    device = DeviceStorage(totalBytes = 256_000_000_000, freeBytes = 129_800_000_000)
                ),
                isClearing = false,
                onClearCache = {}
            )
        }
    }
}
