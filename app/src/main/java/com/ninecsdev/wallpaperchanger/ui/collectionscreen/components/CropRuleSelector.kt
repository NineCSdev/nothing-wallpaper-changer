package com.ninecsdev.wallpaperchanger.ui.collectionscreen.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.ui.components.NothingSegmentedRow
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * Single-choice crop-rule row shared by the create and edit collection cards; a thin icon
 * wrapper over [NothingSegmentedRow].
 */
@Composable
internal fun CropRuleSelector(
    selectedRule: CropRule,
    onRuleSelected: (CropRule) -> Unit,
    modifier: Modifier = Modifier
) {
    NothingSegmentedRow(
        options = CropRule.entries,
        selected = selectedRule,
        onSelect = onRuleSelected,
        modifier = modifier
    ) { rule, isSelected ->
        val icon = when (rule) {
            CropRule.CENTER -> painterResource(R.drawable.icon_crop_center)
            CropRule.LEFT -> painterResource(R.drawable.icon_align_left)
            CropRule.RIGHT -> painterResource(R.drawable.icon_align_right)
            CropRule.FIT -> painterResource(R.drawable.icon_fit_screen)
        }
        Icon(
            painter = icon,
            contentDescription = rule.name,
            tint = if (isSelected) NothingBlack else NothingWhite,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Preview(name = "Crop Rule Selector")
@Composable
fun CropRuleSelectorPreview() {
    WallpaperChangerTheme {
        Box(modifier = Modifier
            .background(NothingBlack)
            .padding(16.dp)) {
            CropRuleSelector(
                selectedRule = CropRule.CENTER,
                onRuleSelected = {}
            )
        }
    }
}
