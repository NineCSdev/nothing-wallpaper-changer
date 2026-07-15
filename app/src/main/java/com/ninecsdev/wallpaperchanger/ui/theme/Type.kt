package com.ninecsdev.wallpaperchanger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Baseline Material 3 type scale. The Nothing text styles below derive their font size,
 * line height and font family from these slots and only override weight / letter spacing.
 * Where a call site never set `letterSpacing`, the token leaves it untouched.
 */
private val Base = Typography()

/**
 * Named text styles for the Nothing "all-caps, wide-tracking" look.
 *
 * Extracted 2026-07-14 from the ~84 inline `fontWeight`/`letterSpacing` overrides that were
 * hand-applied per `Text()`. Seeded as a preserve-exact set (one style per distinct combo),
 * then collapsed to this scale: value-identical duplicates merged, and a few near-duplicates
 * folded into the nearest role (so a handful of sites shifted by a weight step or 0.5sp of
 * tracking — intentional). The scale is now: weights Medium / Bold / Black; tracking 0.5 / 1
 * / 2 sp; sizes from the M3 slot each token is built on.
 */
object NothingType {

    // Titles (labelLarge · 14sp)

    /**
     * Big caps title: top-bar titles, primary buttons, empty-state titles, selection count,
     * preview, and dialog / section headers (transfer, confirmation, edit-collection).
     */
    val titleCaps: TextStyle = Base.labelLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 2.sp)

    /** Centered overlay message. */
    val overlayMessage: TextStyle = Base.labelLarge.copy(letterSpacing = 2.sp)

    /** Count badge / primary confirm-button label (no extra tracking). */
    val badgeCaps: TextStyle = Base.labelLarge.copy(fontWeight = FontWeight.Black)

    /** Bold outlined-button label (dialog Cancel, "manage images"). */
    // letterSpacing = 1.sp so Spanish doesn't overflow
    val dialogButton: TextStyle = Base.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

    // Banner (labelMedium · 12sp)

    /** Inline error banner, e.g. edit-screen save failure. */
    val errorBanner: TextStyle = Base.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp)

    // Labels & captions (labelSmall · 11sp)

    /**
     * Dim wide overline: value labels ("APP STATUS"), section labels, processing / snackbar
     * text, and edit-screen slider labels (ZOOM / X / Y).
     */
    val overline: TextStyle = Base.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp)

    /**
     * Dim supporting text at the labelSmall slot default (Medium · 0.5sp) — equals the bare
     * `labelSmall` slot. Row subtitles, language display names, edit-screen slider values.
     */
    val caption: TextStyle = Base.labelSmall.copy(letterSpacing = 0.5.sp)

    /**
     * Emphasised small label, tracking at slot default (Bold · 0.5sp). Status "READY",
     * segmented options, set-active button, the inline create-collection error.
     */
    val labelStrong: TextStyle = Base.labelSmall.copy(fontWeight = FontWeight.Bold)

    /** Strong small text-button label ("CHANGE" on the default + active-collection cards). Value-identical to a selected [metaLabel] menu item. */
    val actionEmphasis: TextStyle = Base.labelSmall.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp)

    /** Bold small row label (sort field, rotation title, selected language). */
    val rowLabel: TextStyle = Base.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

    /**
     * Dim metadata / dropdown-item base at Medium · 1sp (storage row, version, empty hint,
     * menu items, collection grid-item names).
     */
    val metaLabel: TextStyle = Base.labelSmall.copy(letterSpacing = 1.sp)

    // Row titles (bodySmall · 12sp)

    /** Settings / card row title, active-collection name. */
    val rowTitle: TextStyle = Base.bodySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

    /** Quality slider percentage. */
    val sliderPercent: TextStyle = Base.bodySmall.copy(fontWeight = FontWeight.Bold)

    // Misc

    /** "+N" more-images count on the active-collection card (bodyMedium · 14sp). */
    val countBadge: TextStyle = Base.bodyMedium.copy(fontWeight = FontWeight.Black)

    /** Service status word ("ACTIVE") — headlineSmall · 24sp. */
    val statusValue: TextStyle = Base.headlineSmall.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp)

    /** Create-collection modal title (bodyLarge · 16sp). */
    val createDialogTitle: TextStyle = Base.bodyLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
}