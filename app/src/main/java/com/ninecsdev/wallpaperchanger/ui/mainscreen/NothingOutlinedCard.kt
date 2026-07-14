package com.ninecsdev.wallpaperchanger.ui.mainscreen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.ui.theme.CardCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * The black, white-bordered [OutlinedCard] chrome shared by the main-screen cards
 * ([WallpaperSelectionCard], [DefaultWallpaperCard]). Lays out its [content] in a [Column];
 * use [NothingCardDivider] to separate sections with the matching 2dp rule.
 */
@Composable
internal fun NothingOutlinedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        colors = CardDefaults.outlinedCardColors(
            containerColor = NothingBlack,
            contentColor = NothingWhite
        ),
        border = BorderStroke(2.dp, NothingWhite.copy(alpha = 0.5f))
    ) {
        Column(content = content)
    }
}

/** The 2dp half-opacity divider used between sections of a [NothingOutlinedCard]. */
@Composable
internal fun NothingCardDivider() {
    HorizontalDivider(
        color = NothingWhite.copy(alpha = 0.5f),
        thickness = 2.dp
    )
}
