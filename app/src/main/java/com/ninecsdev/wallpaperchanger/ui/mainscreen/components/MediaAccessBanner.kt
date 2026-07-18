package com.ninecsdev.wallpaperchanger.ui.mainscreen.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.components.safeClick
import com.ninecsdev.wallpaperchanger.ui.theme.CardCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * Warning card shown when photo access (`READ_MEDIA_IMAGES`) is missing while referenced images
 * exist — they can't load until it's re-granted (they stay badged unavailable, nothing is deleted).
 * [onGrantClick] fires the system permission request; a grant self-heals every reference
 * (see [com.ninecsdev.wallpaperchanger.ui.mainscreen.MainViewModel.refreshMediaAccess]).
 */
@Composable
internal fun MediaAccessBanner(
    lostCount: Int,
    onGrantClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        colors = CardDefaults.cardColors(containerColor = NothingBlack),
        border = BorderStroke(1.dp, NothingRed.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.media_access_banner_title),
                    style = NothingType.titleCaps,
                    color = NothingRed
                )
                Spacer(modifier = Modifier.padding(top = 4.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.media_access_banner_message, lostCount, lostCount
                    ),
                    style = NothingType.metaLabel,
                    color = NothingWhite.copy(alpha = 0.55f)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            OutlinedButton(
                onClick = safeClick(onGrantClick),
                shape = RoundedCornerShape(CardCornerRadius),
                border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.5f))
            ) {
                Text(
                    text = stringResource(R.string.media_access_banner_action),
                    style = NothingType.dialogButton,
                    color = NothingWhite
                )
            }
        }
    }
}

@Preview
@Composable
private fun MediaAccessBannerPreview() {
    WallpaperChangerTheme {
        Box(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            MediaAccessBanner(lostCount = 42, onGrantClick = {})
        }
    }
}
