package com.ninecsdev.wallpaperchanger.ui.mainscreen.components

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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.ui.theme.CardCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

// Opaque so the divider's ends don't brighten where they cross the border.
private val CardRuleColor = NothingWhite.copy(alpha = 0.5f).compositeOver(NothingBlack)

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
        border = BorderStroke(2.dp, CardRuleColor)
    ) {
        Column(content = content)
    }
}

@Composable
internal fun NothingCardDivider() {
    HorizontalDivider(
        color = CardRuleColor,
        thickness = 2.dp
    )
}
