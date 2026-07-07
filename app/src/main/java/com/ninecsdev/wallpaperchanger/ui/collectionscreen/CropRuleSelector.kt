package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

@Composable
internal fun CropRuleSelector(
    selectedRule: CropRule,
    onRuleSelected: (CropRule) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CropRule.entries.forEach { rule ->
            val isSelected = selectedRule == rule
            val icon = when (rule) {
                CropRule.CENTER -> painterResource(R.drawable.icon_crop_center)
                CropRule.LEFT -> painterResource(R.drawable.icon_align_left)
                CropRule.RIGHT -> painterResource(R.drawable.icon_align_right)
                CropRule.FIT -> painterResource(R.drawable.icon_fit_screen)
            }

            IconButton(
                onClick = { onRuleSelected(rule) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) NothingWhite else Color(0xFF151515))
                    .border(
                        1.dp,
                        if (isSelected) NothingWhite else NothingWhite.copy(alpha = 0.1f),
                        RoundedCornerShape(8.dp)
                    )
            ) {
                Icon(
                    painter = icon,
                    contentDescription = rule.name,
                    tint = if (isSelected) NothingBlack else NothingWhite,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Preview(name = "Crop Rule Selector")
@Composable
fun CropRuleSelectorPreview() {
    MaterialTheme {
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