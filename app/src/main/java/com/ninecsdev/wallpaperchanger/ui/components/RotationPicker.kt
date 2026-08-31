package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.RotationPolicyKind
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGray
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.SmallCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

// List <-> custom editor motion. Kept The size spec is the longest of the three because the sheet
// re-anchors while it resolves.
private const val SLIDE_MILLIS = 350
private const val FADE_MILLIS = 250
private const val SIZE_MILLIS = 400

/**
 * The cadences offered as one tap. Everything else is reachable through the custom interval, so
 * this list is presentation only as the domain accepts any interval between
 * [RotationPolicy.MIN_INTERVAL_MINUTES] and [RotationPolicy.MAX_INTERVAL_MINUTES].
 */
private val PRESETS = listOf(
    RotationPolicy.PerLock,
    RotationPolicy.interval(15),
    RotationPolicy.interval(30),
    RotationPolicy.interval(60),
    RotationPolicy.interval(360),
    RotationPolicy.interval(720),
    RotationPolicy.PerDay
)

private enum class IntervalUnit(val minutes: Int) {
    MINUTES(1),
    HOURS(60),
    DAYS(24 * 60);

    companion object {
        /** The largest unit [totalMinutes] divides into cleanly. Only ever used to seed. */
        fun seedFor(totalMinutes: Int): IntervalUnit = when {
            totalMinutes % DAYS.minutes == 0 -> DAYS
            totalMinutes % HOURS.minutes == 0 -> HOURS
            else -> MINUTES
        }
    }
}

/** The cadence [policy] describes, as a sentence: "Every 30 minutes", "Once a day". */
@Composable
internal fun rotationSummary(policy: RotationPolicy): String = when (policy.kind) {
    RotationPolicyKind.PER_LOCK -> stringResource(R.string.rotation_summary_per_lock)
    RotationPolicyKind.PER_DAY -> stringResource(R.string.rotation_summary_per_day)
    RotationPolicyKind.INTERVAL -> {
        val unit = IntervalUnit.seedFor(policy.intervalMinutes)
        val amount = policy.intervalMinutes / unit.minutes
        pluralStringResource(
            when (unit) {
                IntervalUnit.MINUTES -> R.plurals.rotation_summary_minutes
                IntervalUnit.HOURS -> R.plurals.rotation_summary_hours
                IntervalUnit.DAYS -> R.plurals.rotation_summary_days
            },
            amount,
            amount
        )
    }
}

/** The same interval in the picker's all-caps voice: "90 MINUTES". */
@Composable
private fun intervalAmountCaps(minutes: Int): String {
    val unit = IntervalUnit.seedFor(minutes)
    val amount = minutes / unit.minutes
    return pluralStringResource(
        when (unit) {
            IntervalUnit.MINUTES -> R.plurals.rotation_amount_minutes
            IntervalUnit.HOURS -> R.plurals.rotation_amount_hours
            IntervalUnit.DAYS -> R.plurals.rotation_amount_days
        },
        amount,
        amount
    )
}

/**
 * The rotation cadence picker, shell-less so the global setting and the per-collection override
 * can each drop it into their own sheet and the two cannot offer different choices.
 *
 * Tapping a preset applies it and calls [onDone]; `CUSTOM…` swaps this
 * body in place for the interval editor, whose `SET` is the single commit point.
 */
// TODO tests: see vault note tests/Rotation Cadence Picker Tests.md
@Composable
internal fun RotationPickerContent(
    title: String,
    policy: RotationPolicy,
    onPolicyChange: (RotationPolicy) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isEditingCustom by remember { mutableStateOf(false) }
    var unit by remember { mutableStateOf(IntervalUnit.seedFor(policy.intervalMinutes)) }
    var amountText by remember { mutableStateOf((policy.intervalMinutes / unit.minutes).toString()) }

    AnimatedContent(
        targetState = isEditingCustom,
        modifier = modifier.fillMaxWidth(),
        // A drill-in, in the direction the CUSTOM… chevron promises
        transitionSpec = {
            val slide = tween<IntOffset>(SLIDE_MILLIS)
            val fade = tween<Float>(FADE_MILLIS)
            // Entering custom goes right-to-left; backing out mirrors it.
            val depth = if (targetState) 1 else -1
            val transform =
                slideInHorizontally(slide) { it * depth } + fadeIn(fade) togetherWith
                    slideOutHorizontally(slide) { -it * depth } + fadeOut(fade)
            transform using SizeTransform(clip = true) { _, _ -> tween(SIZE_MILLIS) }
        },
        label = "rotation cadence picker"
    ) { editing ->
        // Reads `editing`, never `isEditingCustom`: the outgoing frame has to keep drawing the body it is sliding away with.
        Column(modifier = Modifier.fillMaxWidth()) {
            PickerTitle(
                title = if (editing) stringResource(R.string.rotation_custom_title) else title,
                onBack = if (editing) ({ isEditingCustom = false }) else null
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (editing) {
                CustomIntervalEditor(
                    amountText = amountText,
                    onAmountChange = { amountText = it },
                    unit = unit,
                    onUnitChange = { newUnit ->
                        // Re-reads the number rather than converting it: tapping HOURS on "90" means ninety hours
                        unit = newUnit
                    },
                    onSet = {
                        amountText.toIntOrNull()?.let {
                            onPolicyChange(RotationPolicy.interval(it * unit.minutes))
                            onDone()
                        }
                    }
                )
            } else {
                CadenceList(
                    policy = policy,
                    onPresetSelected = { preset ->
                        onPolicyChange(preset)
                        onDone()
                    },
                    onCustomSelected = {
                        // Re-seed on the tap, not on recomposition: entering custom after picking a
                        // preset must show the interval in force, not whatever was typed before it.
                        unit = IntervalUnit.seedFor(policy.intervalMinutes)
                        amountText = (policy.intervalMinutes / unit.minutes).toString()
                        isEditingCustom = true
                    }
                )
            }
        }
    }
}

/** Title centred like every other sheet's; the back arrow hangs off the leading edge. */
@Composable
private fun PickerTitle(title: String, onBack: (() -> Unit)?) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        if (onBack != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                tint = NothingWhite,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(20.dp)
                    .clickable(onClick = onBack)
            )
        }
        Text(text = title, style = NothingType.titleCaps, color = NothingWhite)
    }
}

@Composable
private fun CadenceList(
    policy: RotationPolicy,
    onPresetSelected: (RotationPolicy) -> Unit,
    onCustomSelected: () -> Unit
) {
    // A non-preset interval highlights the CUSTOM… row rather than adding one of its own
    val isCustom = policy.kind == RotationPolicyKind.INTERVAL && PRESETS.none(policy::matches)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PRESETS.forEach { preset ->
            CadenceRow(
                label = stringResource(presetLabelRes(preset)),
                caption = if (preset.kind == RotationPolicyKind.PER_DAY) {
                    stringResource(R.string.rotation_cadence_per_day_caption)
                } else {
                    null
                },
                isSelected = !isCustom && policy.matches(preset),
                onClick = { onPresetSelected(preset) }
            )
        }

        CadenceRow(
            label = if (isCustom) {
                stringResource(R.string.rotation_cadence_custom_value, intervalAmountCaps(policy.intervalMinutes))
            } else {
                stringResource(R.string.rotation_cadence_custom)
            },
            caption = null,
            isSelected = isCustom,
            // The only row that leads somewhere rather than settling the choice, so it gets a chevron
            hasChevron = true,
            onClick = onCustomSelected
        )
    }
}

@Composable
private fun CadenceRow(
    label: String,
    caption: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    hasChevron: Boolean = false
) {
    val shape = RoundedCornerShape(SmallCornerRadius)
    val contentColor = if (isSelected) NothingBlack else NothingWhite
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            // Unselected rows sit on the sheet's own black
            .background(if (isSelected) NothingWhite else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = NothingType.labelStrong,
                color = contentColor,
                maxLines = 1
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = NothingType.caption,
                    color = contentColor.copy(alpha = if (isSelected) 0.6f else 0.4f),
                    maxLines = 1
                )
            }
        }

        if (hasChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = contentColor.copy(alpha = if (isSelected) 0.6f else 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun CustomIntervalEditor(
    amountText: String,
    onAmountChange: (String) -> Unit,
    unit: IntervalUnit,
    onUnitChange: (IntervalUnit) -> Unit,
    onSet: () -> Unit
) {
    val amount = amountText.toIntOrNull()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AmountTile(
            value = amountText,
            onValueChange = onAmountChange,
            modifier = Modifier.width(84.dp)
        )

        NothingSegmentedRow(
            options = IntervalUnit.entries,
            selected = unit,
            onSelect = onUnitChange,
            modifier = Modifier.weight(1f)
        ) { option, isSelected ->
            Text(
                text = stringResource(
                    when (option) {
                        IntervalUnit.MINUTES -> R.string.rotation_unit_minutes
                        IntervalUnit.HOURS -> R.string.rotation_unit_hours
                        IntervalUnit.DAYS -> R.string.rotation_unit_days
                    }
                ),
                style = NothingType.labelStrong,
                color = if (isSelected) NothingBlack else NothingWhite.copy(alpha = 0.9f),
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // States the clamped result, so a value the domain will coerce says so before SET is pressed
    Text(
        text = amount?.let { rotationSummary(RotationPolicy.interval(it * unit.minutes)) }.orEmpty(),
        style = NothingType.caption,
        color = NothingWhite.copy(alpha = 0.4f)
    )

    Spacer(modifier = Modifier.height(16.dp))

    NothingButton(
        text = stringResource(R.string.rotation_action_set),
        onClick = onSet,
        enabled = amount != null
    )
}

@Composable
private fun AmountTile(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(SmallCornerRadius)
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(NothingGray)
            .border(1.dp, NothingWhite.copy(alpha = 0.3f), shape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        // BasicTextField rather than OutlinedTextField: the latter's 56dp height and 4dp corners
        // both fight the 48dp / 8dp segmented tiles sharing this row.
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.filter(Char::isDigit).take(6)) },
            textStyle = NothingType.rowTitle.copy(color = NothingWhite, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(NothingWhite),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Whether this policy *is* [preset]. Only [RotationPolicyKind.INTERVAL] compares the interval as
 * the other two carry a meaningless [RotationPolicy.intervalMinutes] that must not split them.
 */
private fun RotationPolicy.matches(preset: RotationPolicy): Boolean = when (preset.kind) {
    RotationPolicyKind.INTERVAL -> kind == preset.kind && intervalMinutes == preset.intervalMinutes
    else -> kind == preset.kind
}

private fun presetLabelRes(policy: RotationPolicy): Int = when {
    policy.kind == RotationPolicyKind.PER_LOCK -> R.string.rotation_cadence_per_lock
    policy.kind == RotationPolicyKind.PER_DAY -> R.string.rotation_cadence_per_day
    policy.intervalMinutes == 15 -> R.string.rotation_cadence_15m
    policy.intervalMinutes == 30 -> R.string.rotation_cadence_30m
    policy.intervalMinutes == 60 -> R.string.rotation_cadence_1h
    policy.intervalMinutes == 360 -> R.string.rotation_cadence_6h
    else -> R.string.rotation_cadence_12h
}

@Preview(name = "Rotation picker – list", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun RotationPickerListPreview() {
    WallpaperChangerTheme {
        Box(Modifier.background(NothingBlack).padding(24.dp)) {
            RotationPickerContent(
                title = "WALLPAPER ROTATION",
                policy = RotationPolicy.interval(360),
                onPolicyChange = {},
                onDone = {}
            )
        }
    }
}

@Preview(name = "Rotation picker – non-preset", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun RotationPickerCustomValuePreview() {
    WallpaperChangerTheme {
        Box(Modifier.background(NothingBlack).padding(24.dp)) {
            RotationPickerContent(
                title = "ROTATION",
                policy = RotationPolicy.interval(90),
                onPolicyChange = {},
                onDone = {}
            )
        }
    }
}
