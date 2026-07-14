package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.ui.theme.CardCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

@Composable
fun NothingButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: NothingButtonVariant = NothingButtonVariant.PRIMARY
) {
    val colors = when (variant) {
        NothingButtonVariant.PRIMARY -> ButtonDefaults.buttonColors(
            containerColor = NothingWhite,
            contentColor = NothingBlack,
            disabledContainerColor = NothingWhite.copy(alpha = 0.05f),
            disabledContentColor = NothingWhite.copy(alpha = 0.2f)
        )
        NothingButtonVariant.SECONDARY -> ButtonDefaults.buttonColors(
            containerColor = NothingWhite.copy(alpha = 0.1f),
            contentColor = NothingWhite,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = NothingWhite.copy(alpha = 0.1f)
        )
        NothingButtonVariant.DANGER -> ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = NothingRed,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = NothingRed.copy(alpha = 0.3f)
        )
    }

    val border = if (variant == NothingButtonVariant.DANGER) {
        BorderStroke(1.dp, NothingRed.copy(alpha = 0.4f))
    } else {
        null
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(CardCornerRadius),
        colors = colors,
        border = border,
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp
        )
    }
}

enum class NothingButtonVariant {
    PRIMARY, SECONDARY, DANGER
}