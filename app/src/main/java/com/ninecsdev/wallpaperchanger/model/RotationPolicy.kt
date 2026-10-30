package com.ninecsdev.wallpaperchanger.model

import android.util.Log
import java.time.Instant
import java.time.ZoneId

private const val TAG = "RotationPolicy"

/** [PER_DAY] is deliberately a calendar rule (a new local date), not 24 hours. */
enum class RotationPolicyKind { PER_LOCK, INTERVAL, PER_DAY }

/**
 * Decides whether a rotation trigger actually rotates, and when the next time-driven one is due.
 * [intervalMinutes] only matters for [RotationPolicyKind.INTERVAL]; it is carried so the UI keeps
 * the chosen interval while the user toggles between kinds.
 */
data class RotationPolicy(
    val kind: RotationPolicyKind,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES
) {
    /** `lastChangeAt <= 0` is the "never changed / re-anchored" sentinel and always passes. */
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
        /** The domain floor; must stay above [RETRY_DELAY_MS][com.ninecsdev.wallpaperchanger.logic.RotationScheduler.RETRY_DELAY_MS]. */
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

        /** Parses [encode]'s output plus the legacy `HOURLY`; falls back to [PerLock] so a bad value cannot wedge rotation. */
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
 * A collection's own cadence: defer to the app-wide policy, or carry its own. Not a nullable
 * policy, so an override survives switching to "follow global" and back.
 */
sealed interface CollectionRotationSetting {

    /** The default for every new collection. */
    data object FollowGlobal : CollectionRotationSetting

    data class Override(val policy: RotationPolicy) : CollectionRotationSetting

    fun encode(): String = when (this) {
        FollowGlobal -> GLOBAL
        is Override -> "$OVERRIDE_PREFIX${policy.encode()}"
    }

    companion object {
        private const val GLOBAL = "GLOBAL"
        private const val OVERRIDE_PREFIX = "OVERRIDE:"

        /** Parses [encode]'s output; legacy `rotationFrequency` values become an [Override]. */
        fun decode(raw: String?): CollectionRotationSetting = when {
            raw == GLOBAL -> FollowGlobal
            raw != null && raw.startsWith(OVERRIDE_PREFIX) -> Override(RotationPolicy.decode(raw.removePrefix(OVERRIDE_PREFIX)))
            else -> Override(RotationPolicy.decode(raw))
        }
    }
}

fun CollectionRotationSetting.policyOr(fallback: RotationPolicy): RotationPolicy = when (this) {
    CollectionRotationSetting.FollowGlobal -> fallback
    is CollectionRotationSetting.Override -> policy
}
