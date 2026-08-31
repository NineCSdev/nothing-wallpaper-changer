package com.ninecsdev.wallpaperchanger.model

import android.util.Log
import java.time.Instant
import java.time.ZoneId

private const val TAG = "RotationPolicy"

/** How the "may this rotation happen?" question is asked. [PER_DAY] is deliberately a **calendar** rule */
enum class RotationPolicyKind { PER_LOCK, INTERVAL, PER_DAY }

/**
 * The rule deciding whether a rotation trigger actually rotates, and when the next time-driven one
 * is due.
 *
 * [intervalMinutes] is only meaningful for [RotationPolicyKind.INTERVAL], carried so the UI can keep
 * the user's chosen interval visible while they toggle between kinds.
 */
// TODO tests: see vault note tests/Global Rotation Policy Tests.md
data class RotationPolicy(
    val kind: RotationPolicyKind,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES
) {
    /**`lastChangeAt <= 0` is the "never changed / re-anchored" sentinel and always passes. */
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

    /** When this policy next becomes due, or null when it has no time dimension. */
    fun nextDueAt(
        lastChangeAt: Long,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long? = when (kind) {
        RotationPolicyKind.PER_LOCK -> null
        RotationPolicyKind.INTERVAL -> if (lastChangeAt <= 0L) nowMillis else lastChangeAt + clampedIntervalMillis()
        RotationPolicyKind.PER_DAY ->
            if (lastChangeAt <= 0L) {
                nowMillis
            } else {
                // Start of the day after the last change
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
        RotationPolicyKind.INTERVAL -> "INTERVAL:${intervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)}"
    }

    companion object {
        /** The domain floor. Used as the minimum for the custom interval, must be higher than [RETRY_DELAY_MS][com.ninecsdev.wallpaperchanger.logic.RotationScheduler.RETRY_DELAY_MS] */
        const val MIN_INTERVAL_MINUTES = 5

        /** 30 days for input hygiene */
        const val MAX_INTERVAL_MINUTES = 30 * 24 * 60

        const val DEFAULT_INTERVAL_MINUTES = 60

        val PerLock = RotationPolicy(RotationPolicyKind.PER_LOCK)
        val PerDay = RotationPolicy(RotationPolicyKind.PER_DAY)

        fun interval(minutes: Int) = RotationPolicy(
            RotationPolicyKind.INTERVAL,
            minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        )

        /**
         * Parses [encode]'s output plus the legacy `HOURLY` (60-minute interval).
         *
         * Falls back to [PerLock] in case it can't decode to not wedge rotation.
         */
        fun decode(raw: String?): RotationPolicy = when {
            raw == "PER_LOCK" -> PerLock
            raw == "PER_DAY" -> PerDay
            raw == "HOURLY" -> interval(60)
            raw != null && raw.startsWith("INTERVAL:") -> interval(raw.removePrefix("INTERVAL:").toIntOrNull() ?: DEFAULT_INTERVAL_MINUTES)

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
 * A genuine either/or rather than a nullable policy, so "follow global" survives editing an override and switching back
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
         * **Legacy values become an [Override].**
         */
        fun decode(raw: String?): CollectionRotationSetting = when {
            raw == GLOBAL -> FollowGlobal
            raw != null && raw.startsWith(OVERRIDE_PREFIX) -> Override(RotationPolicy.decode(raw.removePrefix(OVERRIDE_PREFIX)))
            else -> Override(RotationPolicy.decode(raw))
        }
    }
}

/** The cadence a setting resolves to against a given global policy */
fun CollectionRotationSetting.policyOr(fallback: RotationPolicy): RotationPolicy = when (this) {
    CollectionRotationSetting.FollowGlobal -> fallback
    is CollectionRotationSetting.Override -> policy
}
