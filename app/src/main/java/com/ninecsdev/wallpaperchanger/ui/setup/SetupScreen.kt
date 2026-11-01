package com.ninecsdev.wallpaperchanger.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import com.ninecsdev.wallpaperchanger.ui.components.RotationPickerContent
import com.ninecsdev.wallpaperchanger.ui.designsystem.NothingButton
import com.ninecsdev.wallpaperchanger.ui.designsystem.NothingButtonVariant
import com.ninecsdev.wallpaperchanger.ui.designsystem.SettingsToggleRow
import com.ninecsdev.wallpaperchanger.ui.designsystem.StatusLed
import com.ninecsdev.wallpaperchanger.ui.setup.components.LoopingVideo
import com.ninecsdev.wallpaperchanger.ui.theme.CardCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingDarkGray
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds

private val WELCOME_PHOTOS = listOf(R.drawable.setup_photo_1, R.drawable.setup_photo_2, R.drawable.setup_photo_3)
private const val PHOTO_HOLD_MILLIS = 3_200L
private const val PHOTO_FADE_MILLIS = 600
private const val STEP_IN_MILLIS = 280

@Composable
fun SetupScreen(
    uiState: SetupUiState,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onRestoreBackup: () -> Unit,
    onSelectMode: (WallpaperMode) -> Unit,
    onSelectRotation: (RotationPolicy) -> Unit,
    onSelectDestination: (WallpaperDestination) -> Unit
) {
    AnimatedContent(
        targetState = uiState.step,
        modifier = Modifier
            .fillMaxSize()
            .background(NothingBlack)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        transitionSpec = {
            (fadeIn(tween(STEP_IN_MILLIS)) + slideInHorizontally(tween(STEP_IN_MILLIS)) { it / 20 }) togetherWith
                fadeOut(tween(STEP_IN_MILLIS / 2))
        },
        label = "setup step"
    ) { step ->
        // Reads `step`, never `uiState.step`: the outgoing frame keeps drawing the step it leaves
        @Composable
        fun decision(title: Int, caption: Int, content: @Composable () -> Unit) = DecisionStep(
            step = step,
            uiState = uiState,
            title = stringResource(title),
            caption = stringResource(caption),
            onSkip = onSkip,
            onNext = onNext,
            onBack = onBack,
            content = content
        )

        when (step) {
            SetupStep.WELCOME -> WelcomeStep(onSkip = onSkip, onGetStarted = onNext, onRestoreBackup = onRestoreBackup)
            SetupStep.LOOK -> decision(R.string.setup_look_title, R.string.setup_look_caption) {
                LookChoices(uiState.wallpaperMode, onSelectMode)
            }
            SetupStep.ROTATION -> decision(R.string.setup_rotation_title, R.string.setup_rotation_caption) {
                RotationPickerContent(
                    title = null,
                    policy = uiState.rotationPolicy,
                    onPolicyChange = onSelectRotation,
                    onDone = {}
                )
            }
            SetupStep.DESTINATION -> decision(R.string.setup_destination_title, R.string.setup_destination_caption) {
                DestinationChoices(uiState.wallpaperDestination, onSelectDestination)
            }
        }
    }
}

@Composable
private fun WelcomeStep(
    onSkip: () -> Unit,
    onGetStarted: () -> Unit,
    onRestoreBackup: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SetupHeader(overline = stringResource(R.string.setup_overline_welcome), onSkip = onSkip)

        Spacer(modifier = Modifier.height(40.dp))
        StepTitle(
            title = stringResource(R.string.setup_welcome_title),
            caption = stringResource(R.string.setup_welcome_caption)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            WelcomeHero(
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .fillMaxHeight()
                    .aspectRatio(0.5f, matchHeightConstraintsFirst = true)
            )
        }

        NothingButton(text = stringResource(R.string.setup_action_get_started), onClick = onGetStarted)
        Spacer(modifier = Modifier.height(12.dp))
        NothingButton(
            text = stringResource(R.string.setup_action_restore),
            onClick = onRestoreBackup,
            variant = NothingButtonVariant.SECONDARY
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** A phone frame cycling the bundled photos under a lock-screen clock. */
@Composable
private fun WelcomeHero(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val time = remember { android.text.format.DateFormat.getTimeFormat(context).format(Date()) }
    var photo by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(PHOTO_HOLD_MILLIS.milliseconds)
            photo = (photo + 1) % WELCOME_PHOTOS.size
        }
    }

    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(NothingBlack)
            .border(2.dp, NothingWhite.copy(alpha = 0.5f), shape)
    ) {
        Crossfade(targetState = photo, animationSpec = tween(PHOTO_FADE_MILLIS), label = "welcome photo") { index ->
            Image(
                painter = painterResource(WELCOME_PHOTOS[index]),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // maybe delete this as it may be unnecessary
        Text(
            text = time,
            style = NothingType.statusValue.copy(fontWeight = FontWeight.Light),
            color = NothingWhite,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp)
        )
    }
}

@Composable
private fun DecisionStep(
    step: SetupStep,
    uiState: SetupUiState,
    title: String,
    caption: String,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val index = uiState.decisions.indexOf(step)
    val isLast = uiState.isLastDecision

    Column(modifier = Modifier.fillMaxSize()) {
        SetupHeader(
            overline = stringResource(R.string.setup_step_counter, index + 1, uiState.decisions.size),
            onSkip = onSkip
        )
        Spacer(modifier = Modifier.height(14.dp))
        StepLeds(count = uiState.decisions.size, current = index)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(40.dp))
            StepTitle(title, caption)
            Spacer(modifier = Modifier.height(if (step == SetupStep.ROTATION) 24.dp else 32.dp))
            content()
            Spacer(modifier = Modifier.height(24.dp))
        }

        Text(
            text = stringResource(if (isLast) R.string.setup_hint_last else R.string.setup_hint_later),
            style = NothingType.caption,
            color = NothingWhite.copy(alpha = 0.4f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BackSquare(onClick = onBack)
            NothingButton(
                text = stringResource(if (isLast) R.string.setup_action_add_wallpapers else R.string.setup_action_next),
                onClick = onNext,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SetupHeader(overline: String, onSkip: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = overline, style = NothingType.overline, color = NothingWhite.copy(alpha = 0.4f))
        Text(
            text = stringResource(R.string.setup_action_skip),
            style = NothingType.actionEmphasis,
            color = NothingWhite,
            modifier = Modifier
                .offset(x = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onSkip)
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun StepLeds(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            StatusLed(
                color = if (i <= current) NothingWhite else NothingWhite.copy(alpha = 0.15f),
                isPulsing = i == current,
                modifier = Modifier.size(8.dp)
            )
        }
    }
}

@Composable
private fun StepTitle(title: String, caption: String) {
    Text(text = title.uppercase(), style = NothingType.statusValue, color = NothingWhite)
    Spacer(modifier = Modifier.height(8.dp))
    Text(text = caption, style = NothingType.caption, color = NothingWhite.copy(alpha = 0.4f))
}

@Composable
private fun BackSquare(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(CardCornerRadius))
            .background(NothingWhite.copy(alpha = 0.1f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.cd_back),
            tint = NothingWhite,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** The segmented row's white-fill selection, at full size; [content] gets the colour to draw in. */
@Composable
private fun ChoiceTile(
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(contentColor: Color) -> Unit
) {
    val shape = RoundedCornerShape(CardCornerRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (isSelected) NothingWhite else NothingDarkGray)
            .border(1.dp, if (isSelected) NothingWhite else NothingWhite.copy(alpha = 0.1f), shape)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        content(if (isSelected) NothingBlack else NothingWhite)
    }
}

@Composable
private fun TileLabel(label: String, caption: String, contentColor: Color) {
    Text(text = label, style = NothingType.rowLabel, color = contentColor)
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = caption,
        style = NothingType.caption,
        color = contentColor.copy(alpha = if (contentColor == NothingBlack) 0.6f else 0.4f)
    )
}

/** Atmosphere as the switch Settings shows; the preview plays what each position looks like. */
@Composable
private fun LookChoices(mode: WallpaperMode, onSelect: (WallpaperMode) -> Unit) {
    val isOn = mode == WallpaperMode.ATMOSPHERE
    val shape = RoundedCornerShape(22.dp)

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // The welcome screen's phone frame, so it reads as the user's own screen
        Crossfade(
            targetState = isOn,
            animationSpec = tween(PHOTO_FADE_MILLIS),
            label = "atmosphere preview",
            modifier = Modifier
                .height(300.dp)
                .aspectRatio(0.5f)
                .clip(shape)
                .background(NothingBlack)
                .border(2.dp, NothingWhite.copy(alpha = 0.5f), shape)
                .clickable { onSelect(if (isOn) WallpaperMode.STATIC else WallpaperMode.ATMOSPHERE) }
        ) { on ->
            if (on) {
                LoopingVideo(resId = R.raw.setup_atmosphere, modifier = Modifier.fillMaxSize())
            } else {
                Image(
                    painter = painterResource(R.drawable.setup_photo_1),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    SettingsToggleRow(
        title = stringResource(R.string.settings_wallpaper_mode_title),
        subtitle = stringResource(if (isOn) R.string.setup_look_on_caption else R.string.setup_look_off_caption),
        checked = isOn,
        onCheckedChange = { onSelect(if (it) WallpaperMode.ATMOSPHERE else WallpaperMode.STATIC) }
    )
}

@Composable
private fun DestinationChoices(destination: WallpaperDestination, onSelect: (WallpaperDestination) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WallpaperDestination.entries.forEach { option ->
            val (label, caption) = when (option) {
                WallpaperDestination.LOCK -> R.string.settings_destination_lock to R.string.setup_destination_lock_caption
                WallpaperDestination.HOME -> R.string.settings_destination_home to R.string.setup_destination_home_caption
                WallpaperDestination.BOTH -> R.string.settings_destination_both to R.string.setup_destination_both_caption
            }
            ChoiceTile(
                isSelected = destination == option,
                onClick = { onSelect(option) },
                modifier = Modifier.fillMaxWidth()
            ) { color ->
                Column(modifier = Modifier.padding(4.dp)) {
                    TileLabel(stringResource(label), stringResource(caption), color)
                }
            }
        }
    }
}

@Preview(showSystemUi = true, name = "Setup - destination", backgroundColor = 0xFF000000)
@Composable
private fun SetupDestinationPreview() {
    WallpaperChangerTheme {
        SetupScreen(
            uiState = SetupUiState(
                step = SetupStep.LOOK,
                wallpaperMode = WallpaperMode.STATIC,
                rotationPolicy = RotationPolicy.PerLock,
                wallpaperDestination = WallpaperDestination.LOCK
            ),
            onSkip = {},
            onNext = {},
            onBack = {},
            onRestoreBackup = {},
            onSelectMode = {},
            onSelectRotation = {},
            onSelectDestination = {}
        )
    }
}
