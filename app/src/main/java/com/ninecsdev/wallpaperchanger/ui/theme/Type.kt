package com.ninecsdev.wallpaperchanger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Size, line height and family come from these M3 slots; the styles below override only weight and tracking.
private val Base = Typography()

object NothingType {

    val titleCaps: TextStyle = Base.labelLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 2.sp)

    val overlayMessage: TextStyle = Base.labelLarge.copy(letterSpacing = 2.sp)

    val badgeCaps: TextStyle = Base.labelLarge.copy(fontWeight = FontWeight.Black)

    // letterSpacing = 1.sp so Spanish doesn't overflow
    val dialogButton: TextStyle = Base.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

    val errorBanner: TextStyle = Base.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp)

    val overline: TextStyle = Base.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp)

    val caption: TextStyle = Base.labelSmall.copy(letterSpacing = 0.5.sp)

    val labelStrong: TextStyle = Base.labelSmall.copy(fontWeight = FontWeight.Bold)

    val actionEmphasis: TextStyle = Base.labelSmall.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp)

    val rowLabel: TextStyle = Base.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

    val metaLabel: TextStyle = Base.labelSmall.copy(letterSpacing = 1.sp)

    val rowTitle: TextStyle = Base.bodySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

    val sliderPercent: TextStyle = Base.bodySmall.copy(fontWeight = FontWeight.Bold)

    val statusValue: TextStyle = Base.headlineSmall.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp)

    val createDialogTitle: TextStyle = Base.bodyLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
}