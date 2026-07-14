package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * Represents a selectable language entry in the picker.
 * [tag] is the locale tag, or "" for "System default".
 * [nativeName] is the name in that language itself (used as primary label).
 * [displayName] is the name in the device's current locale (used as secondary label).
 */
private data class Language(
    val tag: String,
    val nativeName: String,
    val displayName: String?
)

/**
 * Language selection dropdown for the Settings screen.
 *
 * Supported languages are read directly from locales_config at runtime.
 *
 * The locale is applied in a [LaunchedEffect] keyed on the selected tag so
 * the side effect is properly scoped to the Compose lifecycle.
 */
@Composable
internal fun LanguageSelector() {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val density = LocalDensity.current

    // The LocaleManager service is not supported in the Android Studio Preview (layoutlib)
    // and throws an AssertionError if we try to access it.
    val localeManager = remember(context, isPreview) {
        if (!isPreview) context.getSystemService(Context.LOCALE_SERVICE) as? LocaleManager else null
    }

    val languages = remember { buildLanguageList(context) }

    var selectedTag by remember {
        val locales = localeManager?.applicationLocales
        val tag = if (locales == null || locales.isEmpty) "" else locales.get(0)?.toLanguageTag() ?: ""
        mutableStateOf(tag)
    }

    LaunchedEffect(selectedTag) {
        if (isPreview || localeManager == null) return@LaunchedEffect
        val localeList = if (selectedTag.isEmpty()) {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList.forLanguageTags(selectedTag)
        }
        localeManager.applicationLocales = localeList
    }

    var expanded by remember { mutableStateOf(false) }
    // Used to make the drop menu size match the select language box
    var buttonSize by remember { mutableStateOf(Size.Zero) }

    val selectedLanguage = languages.find { it.tag == selectedTag } ?: languages.first()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        SettingsRowHeader(
            title = stringResource(R.string.settings_language_title),
            subtitle = stringResource(R.string.settings_language_subtitle),
            modifier = Modifier.weight(1f)
        )

        Box(
            modifier = Modifier.onGloballyPositioned { coordinates ->
                buttonSize = coordinates.size.toSize()
            }
        ) {
            TextButton(
                onClick = { expanded = true },
                border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.25f)),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = NothingBlack,
                    contentColor = NothingWhite
                ),
                shape = MaterialTheme.shapes.small,
                contentPadding = ButtonDefaults.TextButtonContentPadding
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedLanguage.nativeName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = NothingWhite.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = NothingBlack,
                border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.15f)),
                modifier = Modifier.width(with(density) { buttonSize.width.toDp() })
            ) {
                languages.forEach { language ->
                    val isSelected = language.tag == selectedTag
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = language.nativeName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) NothingWhite else NothingWhite.copy(alpha = 0.6f),
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal,
                                    letterSpacing = 1.sp
                                )
                                if (language.displayName != null) {
                                    Text(
                                        text = language.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NothingWhite.copy(alpha = 0.35f),
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        },
                        onClick = {
                            selectedTag = language.tag
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * Reads locales_config, builds a [Language] for each declared locale,
 * and prepends a "System default" entry (empty tag).
 */
private fun buildLanguageList(context: Context): List<Language> {
    val result = mutableListOf<Language>()

    val parser = context.resources.getXml(R.xml.locales_config)
    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.START_TAG && parser.name == "locale") {
            for (i in 0 until parser.attributeCount) {
                if (parser.getAttributeName(i) == "name") {
                    val tag = parser.getAttributeValue(i)
                    val locale = Locale.forLanguageTag(tag)
                    val displayName = locale.getDisplayName(locale).replaceFirstChar { it.uppercase() }
                    val nativeName = locale.getDisplayName(Locale.getDefault()).replaceFirstChar { it.uppercase() }
                    if (displayName.isNotEmpty()) {
                        result.add(Language(tag, displayName, nativeName))
                    }
                }
            }
        }
        eventType = parser.next()
    }
    result.sortBy { it.nativeName }
    result.add(0, Language("", context.getString(R.string.settings_language_system), null))
    return result
}