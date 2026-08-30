package com.ninecsdev.wallpaperchanger.model

import android.util.Log
import java.time.Instant
import java.time.ZoneId

private const val TAG = "RotationPolicy"

/**
 * How the "may this rotation happen?" question is asked.
 *
 * [PER_DAY] is a **calendar** rule, deliberately not `INTERVAL(1440)`: a wallpaper changed at 22:00
 * should change again after midnight, not at 22:00 the next evening.
 */
enum class RotationPolicyKind { PER_LOCK, INTERVAL, PER_DAY }

/**
 * The rule deciding whether a rotation trigger actually rotates, and when the next time-driven one
 * is due.
 *
 * A value object with no Android or persistence dependency, answering both questions from one place
 * so the gate ([shouldRotateAt]) and the timer ([nextDueAt]) can never disagree about what "every 90
 * minutes" means.
 *
 * [intervalMinutes] is only meaningful for [RotationPolicyKind.INTERVAL], and is carried rather than
 * modelled as a subtype so the UI can keep the user's chosen interval visible while they toggle
 * between kinds.
 */
data class RotationPolicy(
    val kind: RotationPolicyKind,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES
) {
    /**
     * `lastChangeAt <= 0` is the "never changed / re-anchored" sentinel and always passes — it is
     * what a collection switch writes so an explicit user action takes effect on the next lock
     * regardless of the previous collection's timer.
     */
    fun shouldRotateAt(
        lastChangeAt: Long,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        if (lastChangeAt <= 0L) return true
        return when (kind) {
            RotationPolicyKind.PER_LOCK -> true
            RotationPolicyKind.INTERVAL -> nowMillis - lastChangeAt >= clampedIntervalMillis()
            RotationPolicyKind.PER_DAY -> {
                val last = Instant.ofEpochMilli(lastChangeAt).atZone(zoneId).toLocalDate()
                val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
                now.isAfter(last)
            }
        }
    }

    /**
     * When this policy next becomes due, or null when it has no time dimension and therefore nothing
     * to schedule against. A returned instant may already be in the past, meaning "due now".
     *
     * Clock and timezone changes are absorbed by the caller re-arming, not by anything here.
     */
    fun nextDueAt(
        lastChangeAt: Long,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long? = when (kind) {
        RotationPolicyKind.PER_LOCK -> null
        RotationPolicyKind.INTERVAL ->
            if (lastChangeAt <= 0L) nowMillis else lastChangeAt + clampedIntervalMillis()
        RotationPolicyKind.PER_DAY ->
            if (lastChangeAt <= 0L) {
                nowMillis
            } else {
                // Start of the day after the last change — the instant shouldRotateAt's calendar
                // comparison flips.
                Instant.ofEpochMilli(lastChangeAt)
                    .atZone(zoneId)
                    .toLocalDate()
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            }
    }

    private fun clampedIntervalMillis(): Long =
        intervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES) * 60_000L

    fun encode(): String = when (kind) {
        RotationPolicyKind.PER_LOCK -> "PER_LOCK"
        RotationPolicyKind.PER_DAY -> "PER_DAY"
        RotationPolicyKind.INTERVAL ->
            "INTERVAL:${intervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)}"
    }

    companion object {
        /**
         * The domain floor. The UI offers presets from a higher figure and reaches this only through
         * its custom field — that higher figure is presentation and must not move in here, or the
         * custom entry becomes unbuildable.
         */
        const val MIN_INTERVAL_MINUTES = 5

        /** 30 days. Input hygiene: an unbounded field invites overflow-shaped entries. */
        const val MAX_INTERVAL_MINUTES = 30 * 24 * 60

        const val DEFAULT_INTERVAL_MINUTES = 60

        val PerLock = RotationPolicy(RotationPolicyKind.PER_LOCK)
        val PerDay = RotationPolicy(RotationPolicyKind.PER_DAY)

        fun interval(minutes: Int) = RotationPolicy(
            RotationPolicyKind.INTERVAL,
            minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        )

        /**
         * Parses [encode]'s output plus the legacy `HOURLY`, which was exactly a 60-minute interval.
         *
         * Total by design — a corrupted preference must not wedge rotation — and loud, because an
         * encoder typo would otherwise degrade every collection with no exception and no trace.
         */
        fun decode(raw: String?): RotationPolicy = when {
            raw == "PER_LOCK" -> PerLock
            raw == "PER_DAY" -> PerDay
            raw == "HOURLY" -> interval(60)
            raw != null && raw.startsWith("INTERVAL:") ->
                interval(raw.removePrefix("INTERVAL:").toIntOrNull() ?: DEFAULT_INTERVAL_MINUTES)

            else -> {
                Log.w(TAG, "Unparsed rotation policy '$raw'; falling back to per-lock.")
                PerLock
            }
        }
    }
}

/**
 * What a collection says about its own cadence: defer to the app-wide policy, or carry its own.
 *
 * A genuine either/or rather than a nullable policy, so "follow global" survives the user editing an
 * override and switching back, and so one column round-trips both states.
 */
sealed interface CollectionRotationSetting {

    /** Use the global policy. The default for every new collection. */
    data object FollowGlobal : CollectionRotationSetting

    /** This collection rotates on its own [policy], ignoring the global one. */
    data class Override(val policy: RotationPolicy) : CollectionRotationSetting

    fun encode(): String = when (this) {
        FollowGlobal -> GLOBAL
        is Override -> "$OVERRIDE_PREFIX${policy.encode()}"
    }

    companion object {
        private const val GLOBAL = "GLOBAL"
        private const val OVERRIDE_PREFIX = "OVERRIDE:"

        /**
         * Parses [encode]'s output and the legacy `rotationFrequency` values.
         *
         * **Every legacy value becomes an [Override], per-lock included.** The column cannot
         * distinguish "left at the default" from "deliberately chosen, and it happens to equal the
         * default", so converting per-lock rows to [FollowGlobal] would silently put collections the
         * user had explicitly pinned onto whatever global cadence they later set. The cost is that
         * the global setting is inert on existing collections until an override is cleared by hand.
         */
        fun decode(raw: String?): CollectionRotationSetting = when {
            raw == GLOBAL -> FollowGlobal
            raw != null && raw.startsWith(OVERRIDE_PREFIX) ->
                Override(RotationPolicy.decode(raw.removePrefix(OVERRIDE_PREFIX)))

            else -> Override(RotationPolicy.decode(raw))
        }
    }
}

/** The policy an editor should show, whichever state the setting is in. */
fun CollectionRotationSetting.policyOr(fallback: RotationPolicy): RotationPolicy = when (this) {
    CollectionRotationSetting.FollowGlobal -> fallback
    is CollectionRotationSetting.Override -> policy
}
