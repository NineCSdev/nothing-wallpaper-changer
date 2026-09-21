package com.ninecsdev.wallpaperchanger.ui.settingsscreen.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.components.SettingsRowHeader
import com.ninecsdev.wallpaperchanger.ui.components.SettingsSection
import com.ninecsdev.wallpaperchanger.ui.components.safeClick
import com.ninecsdev.wallpaperchanger.ui.openPlayStoreListing
import com.ninecsdev.wallpaperchanger.ui.openUrl
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

private const val REPO_URL = "https://github.com/NineCSdev/nothing-wallpaper-changer"
private const val ISSUES_URL = "$REPO_URL/issues"
private const val PROFILE_URL = "https://github.com/NineCSdev"

/** The rows of the ABOUT section: the store listing, the repository and its issue tracker.*/
@Composable
internal fun AboutSection() {
    val context = LocalContext.current

    AboutLinkRow(
        title = stringResource(R.string.settings_rate_title),
        subtitle = stringResource(R.string.settings_rate_subtitle),
        onClick = safeClick({ openPlayStoreListing(context) })
    )

    AboutLinkRow(
        title = stringResource(R.string.settings_source_title),
        subtitle = stringResource(R.string.settings_source_subtitle),
        onClick = safeClick({ openUrl(context, REPO_URL) })
    )

    AboutLinkRow(
        title = stringResource(R.string.settings_issues_title),
        subtitle = stringResource(R.string.settings_issues_subtitle),
        onClick = safeClick({ openUrl(context, ISSUES_URL) })
    )
}

@Composable
private fun AboutLinkRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowHeader(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f)
        )

        Icon(
            painter = painterResource(R.drawable.icon_external_link),
            contentDescription = null,
            tint = NothingWhite.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
internal fun AppVersionFooter(
    version: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(
                onClickLabel = stringResource(R.string.cd_open_developer_profile),
                onClick = safeClick({ openUrl(context, PROFILE_URL) })
            ),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "v$version",
            style = NothingType.metaLabel,
            color = NothingWhite.copy(alpha = 0.25f)
        )
        Text(
            text = stringResource(R.string.settings_version_byline),
            style = NothingType.metaLabel,
            color = NothingWhite.copy(alpha = 0.25f)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun AboutSectionPreview() {
    WallpaperChangerTheme {
        Column(
            modifier = Modifier
                .background(NothingBlack)
                .padding(horizontal = 24.dp)
        ) {
            SettingsSection(
                label = stringResource(R.string.settings_section_about),
                showDivider = false
            ) {
                AboutSection()
            }

            AppVersionFooter(version = "0.4.1-beta")
        }
    }
}
