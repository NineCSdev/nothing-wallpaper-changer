package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.RotationPolicyKind
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGray
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/** Interval presets, in minutes. This floor is presentation; the domain floor is lower. */
private val PRESET_MINUTES = listOf(15, 30, 60, 360, 720)

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

/**
 * The rotation cadence editor, shared by the global setting and the per-collection override so the
 * two cannot offer different choices.
 *
 * Its inner state (custom-vs-preset, unit, typed amount) is **seeded once from [policy] and then
 * owned locally**, never re-derived. Callers editing more than one subject should wrap this in `key(...)`
 * so switching subject re-seeds.
 */
@Composable
internal fun RotationPolicyEditor(
    policy: RotationPolicy,
    onPolicyChange: (RotationPolicy) -> Unit,
    modifier: Modifier = Modifier
) {
    var isCustom by remember {
        mutableStateOf(
            policy.kind == RotationPolicyKind.INTERVAL && policy.intervalMinutes !in PRESET_MINUTES
        )
    }
    var unit by remember { mutableStateOf(IntervalUnit.seedFor(policy.intervalMinutes)) }
    var amountText by remember { mutableStateOf((policy.intervalMinutes / unit.minutes).toString()) }

    Column(modifier = modifier) {
        NothingSegmentedRow(
            options = RotationPolicyKind.entries,
            selected = policy.kind,
            onSelect = { kind ->
                onPolicyChange(
                    when (kind) {
                        RotationPolicyKind.PER_LOCK -> RotationPolicy.PerLock
                        RotationPolicyKind.PER_DAY -> RotationPolicy.PerDay
                        // Carries the interval across a round trip through the other two kinds.
                        RotationPolicyKind.INTERVAL -> RotationPolicy.interval(policy.intervalMinutes)
                    }
                )
            }
        ) { kind, isSelected ->
            SegmentLabel(
                text = stringResource(
                    when (kind) {
                        RotationPolicyKind.PER_LOCK -> R.string.rotation_kind_per_lock
                        RotationPolicyKind.INTERVAL -> R.string.rotation_kind_interval
                        RotationPolicyKind.PER_DAY -> R.string.rotation_kind_daily
                    }
                ),
                isSelected = isSelected
            )
        }

        if (policy.kind == RotationPolicyKind.INTERVAL) {
            Spacer(modifier = Modifier.height(8.dp))

            // null is the custom option, so the presets and the escape hatch share one row.
            NothingSegmentedRow(
                options = PRESET_MINUTES + listOf(null),
                selected = if (isCustom) null else policy.intervalMinutes,
                onSelect = { preset ->
                    isCustom = preset == null
                    if (preset != null) {
                        onPolicyChange(RotationPolicy.interval(preset))
                    } else {
                        // Re-seed on the tap, not on recomposition: entering custom after picking a
                        // preset must show the interval in force, not whatever was typed before it.
                        unit = IntervalUnit.seedFor(policy.intervalMinutes)
                        amountText = (policy.intervalMinutes / unit.minutes).toString()
                    }
                }
            ) { preset, isSelected ->
                SegmentLabel(
                    text = stringResource(
                        preset?.let(::presetLabelRes) ?: R.string.rotation_preset_custom
                    ),
                    isSelected = isSelected
                )
            }
        }

        if (policy.kind == RotationPolicyKind.INTERVAL && isCustom) {
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = amountText,
                onValueChange = { newValue ->
                    val filtered = newValue.filter { it.isDigit() }.take(6)
                    amountText = filtered
                    filtered.toIntOrNull()?.let {
                        onPolicyChange(RotationPolicy.interval(it * unit.minutes))
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = NothingWhite,
                    unfocusedTextColor = NothingWhite,
                    cursorColor = NothingWhite,
                    focusedBorderColor = NothingWhite,
                    unfocusedBorderColor = NothingWhite.copy(alpha = 0.3f),
                    focusedContainerColor = NothingGray,
                    unfocusedContainerColor = NothingGray
                ),
                modifier = Modifier.width(120.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            NothingSegmentedRow(
                options = IntervalUnit.entries,
                selected = unit,
                onSelect = { newUnit ->
                    // Re-reads the number rather than converting it: tapping HOURS on "90" means
                    // ninety hours, which is what the field visibly says.
                    unit = newUnit
                    amountText.toIntOrNull()?.let {
                        onPolicyChange(RotationPolicy.interval(it * newUnit.minutes))
                    }
                }
            ) { option, isSelected ->
                SegmentLabel(
                    text = stringResource(
                        when (option) {
                            IntervalUnit.MINUTES -> R.string.rotation_unit_minutes
                            IntervalUnit.HOURS -> R.string.rotation_unit_hours
                            IntervalUnit.DAYS -> R.string.rotation_unit_days
                        }
                    ),
                    isSelected = isSelected
                )
            }
        }
    }
}

private fun presetLabelRes(minutes: Int): Int = when (minutes) {
    15 -> R.string.rotation_preset_15m
    30 -> R.string.rotation_preset_30m
    60 -> R.string.rotation_preset_1h
    360 -> R.string.rotation_preset_6h
    else -> R.string.rotation_preset_12h
}

@Composable
private fun SegmentLabel(text: String, isSelected: Boolean) {
    Text(
        text = text,
        style = NothingType.labelStrong,
        color = if (isSelected) NothingBlack else NothingWhite.copy(alpha = 0.9f),
        maxLines = 1,
        textAlign = TextAlign.Center
    )
}
