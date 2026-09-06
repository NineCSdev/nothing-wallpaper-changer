package com.ninecsdev.wallpaperchanger.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pins the rotation gate, the due-time arithmetic and the encoding.
 *
 * The point of a single value object is that the gate and the timer cannot disagree, so both are
 * driven from the same inputs here.
 */
class RotationPolicyTest {

    /** A zone with a DST rule, so the calendar cases are about calendars and not about arithmetic. */
    private val dstZone: ZoneId = ZoneId.of("America/New_York")

    /** A zone without one, for the cases where the offset is noise. */
    private val fixedZone: ZoneId = ZoneId.of("UTC")

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), zone).toInstant().toEpochMilli()

    private val hourly get() = RotationPolicy.interval(60)

    // ---- The gate ----

    @Test
    fun `a re-anchored collection is due under every kind`() {
        // Checked above the `when`, so a kind added later cannot forget it. Zeroing this stamp is
        // how a collection switch re-anchors the gate.
        val kinds = listOf(RotationPolicy.PerLock, RotationPolicy.PerDay, RotationPolicy.interval(90))
        for (policy in kinds) {
            for (sentinel in listOf(0L, -1L)) {
                assertTrue(
                    "$policy should be due at the $sentinel sentinel",
                    policy.shouldRotateAt(sentinel, nowMillis = 1_000_000L, zoneId = fixedZone)
                )
            }
        }
    }

    @Test
    fun `per-lock is always due`() {
        val stamped = at(fixedZone, 2026, 5, 1, 12, 0)
        assertTrue(RotationPolicy.PerLock.shouldRotateAt(stamped, stamped + 1, fixedZone))
    }

    @Test
    fun `an interval is due at exactly the interval, not before`() {
        val policy = RotationPolicy.interval(90)
        val stamped = at(fixedZone, 2026, 5, 1, 12, 0)
        val minute = 60_000L
        assertFalse("89 minutes is not 90", policy.shouldRotateAt(stamped, stamped + 89 * minute, fixedZone))
        assertTrue("90 minutes is due", policy.shouldRotateAt(stamped, stamped + 90 * minute, fixedZone))
    }

    @Test
    fun `daily compares calendar dates and not elapsed time`() {
        // Two hours after a 22:00 stamp is a new date and rotates; twenty-two hours after a 00:30
        // stamp is the same date and does not. Elapsed-time arithmetic gets both backwards.
        val lateEvening = at(fixedZone, 2026, 5, 1, 22, 0)
        assertFalse(
            "23:59 the same day",
            RotationPolicy.PerDay.shouldRotateAt(lateEvening, at(fixedZone, 2026, 5, 1, 23, 59), fixedZone)
        )
        assertTrue(
            "00:01 the next day",
            RotationPolicy.PerDay.shouldRotateAt(lateEvening, at(fixedZone, 2026, 5, 2, 0, 1), fixedZone)
        )

        val earlyMorning = at(fixedZone, 2026, 5, 1, 0, 30)
        assertFalse(
            "23:00 is still the stamp's own date",
            RotationPolicy.PerDay.shouldRotateAt(earlyMorning, at(fixedZone, 2026, 5, 1, 23, 0), fixedZone)
        )
    }

    // ---- nextDueAt ----

    @Test
    fun `only per-lock has no next due time`() {
        val stamped = at(fixedZone, 2026, 5, 1, 12, 0)
        assertNull(RotationPolicy.PerLock.nextDueAt(stamped, stamped, fixedZone))
        for (policy in listOf(RotationPolicy.PerDay, RotationPolicy.interval(90), hourly)) {
            assertTrue("$policy must be schedulable", policy.nextDueAt(stamped, stamped, fixedZone) != null)
        }
    }

    @Test
    fun `an interval is due one interval after the stamp`() {
        val stamped = at(fixedZone, 2026, 5, 1, 12, 0)
        assertEquals(stamped + 90 * 60_000L, RotationPolicy.interval(90).nextDueAt(stamped, stamped, fixedZone))
    }

    @Test
    fun `a re-anchored collection is due immediately`() {
        val now = at(fixedZone, 2026, 5, 1, 12, 0)
        assertEquals(now, RotationPolicy.interval(90).nextDueAt(0L, now, fixedZone))
        assertEquals(now, RotationPolicy.PerDay.nextDueAt(0L, now, fixedZone))
    }

    @Test
    fun `daily is due at the next local midnight`() {
        val stamped = at(fixedZone, 2026, 5, 1, 22, 0)
        assertEquals(
            at(fixedZone, 2026, 5, 2, 0, 0),
            RotationPolicy.PerDay.nextDueAt(stamped, stamped, fixedZone)
        )
    }

    @Test
    fun `a daily gap across a DST boundary is not twenty-four hours`() {
        // The case a `lastChangeAt + 86400000` implementation passes every other test and fails.
        // 2026-03-08 and 2026-11-01 are the US transition dates; midnight to midnight across each
        // is a 23- and a 25-hour day.
        val day = 24 * 60 * 60 * 1000L
        val hour = 60 * 60 * 1000L

        val springStamp = at(dstZone, 2026, 3, 8, 0, 0)
        val springDue = RotationPolicy.PerDay.nextDueAt(springStamp, springStamp, dstZone)
        assertEquals(at(dstZone, 2026, 3, 9, 0, 0), springDue)
        assertEquals("spring forward shortens the day", day - hour, springDue!! - springStamp)

        val autumnStamp = at(dstZone, 2026, 11, 1, 0, 0)
        val autumnDue = RotationPolicy.PerDay.nextDueAt(autumnStamp, autumnStamp, dstZone)
        assertEquals(at(dstZone, 2026, 11, 2, 0, 0), autumnDue)
        assertEquals("autumn back lengthens it", day + hour, autumnDue!! - autumnStamp)
    }

    @Test
    fun `gate and nextDueAt agree`() {
        // The invariant the single-object design exists to guarantee: at the instant the timer fires,
        // the gate it wakes must let the rotation through. Two implementations, one answer.
        val stamps = listOf(
            at(fixedZone, 2026, 5, 1, 22, 0),
            at(dstZone, 2026, 3, 8, 0, 0),
            at(dstZone, 2026, 11, 1, 0, 0),
        )
        val policies = listOf(RotationPolicy.PerDay, RotationPolicy.interval(90), hourly, RotationPolicy.interval(5))
        for (zone in listOf(fixedZone, dstZone)) {
            for (stamp in stamps) {
                for (policy in policies) {
                    val due = policy.nextDueAt(stamp, stamp, zone) ?: continue
                    assertTrue(
                        "$policy in $zone was not due at its own nextDueAt",
                        policy.shouldRotateAt(stamp, due, zone)
                    )
                    assertFalse(
                        "$policy in $zone was already due a minute early",
                        policy.shouldRotateAt(stamp, due - 60_000L, zone)
                    )
                }
            }
        }
    }

    // ---- The interval domain ----

    @Test
    fun `an interval is clamped in both directions`() {
        assertEquals(RotationPolicy.MIN_INTERVAL_MINUTES, RotationPolicy.interval(1).intervalMinutes)
        assertEquals(RotationPolicy.MIN_INTERVAL_MINUTES, RotationPolicy.interval(-30).intervalMinutes)
        assertEquals(RotationPolicy.MAX_INTERVAL_MINUTES, RotationPolicy.interval(Int.MAX_VALUE).intervalMinutes)
        // The UI's own floor is 15 minutes and is deliberately not asserted here: pinning it in the
        // value object is what would make a shorter custom entry unbuildable later.
        assertTrue(RotationPolicy.MIN_INTERVAL_MINUTES < 15)
    }

    // ---- Encoding ----

    @Test
    fun `every kind survives a round trip`() {
        for (policy in listOf(RotationPolicy.PerLock, RotationPolicy.PerDay, RotationPolicy.interval(90), hourly)) {
            assertEquals(policy, RotationPolicy.decode(policy.encode()))
        }
    }

    @Test
    fun `an out-of-range interval normalises through the round trip`() {
        // Constructed directly rather than through `interval`, so the clamp has to happen on the way
        // out or on the way back in. It happens on both.
        val raw = RotationPolicy(RotationPolicyKind.INTERVAL, 1)
        assertEquals(RotationPolicy.interval(RotationPolicy.MIN_INTERVAL_MINUTES), RotationPolicy.decode(raw.encode()))
    }

    @Test
    fun `the legacy hourly value decodes to a sixty-minute interval`() {
        // The one accepted input that is not also an `encode()` output.
        assertEquals(RotationPolicy.interval(60), RotationPolicy.decode("HOURLY"))
    }

    @Test
    fun `an unreadable policy falls back to per-lock rather than wedging rotation`() {
        // Totality is the property. The warning the fallback logs is a debugging aid, not the contract.
        for (raw in listOf(null, "", "nonsense", "INTERVAL", "PER_WEEK", "per_lock")) {
            assertEquals("decode($raw)", RotationPolicy.PerLock, RotationPolicy.decode(raw))
        }
    }

    @Test
    fun `an interval with an unreadable number falls back to the default interval`() {
        // Not to per-lock: the kind was legible, only the number was not. Folding this into the
        // else branch would silently change a collection's cadence rather than its interval.
        val expected = RotationPolicy.interval(RotationPolicy.DEFAULT_INTERVAL_MINUTES)
        assertEquals(expected, RotationPolicy.decode("INTERVAL:abc"))
        assertEquals(expected, RotationPolicy.decode("INTERVAL:"))
    }

    // ---- CollectionRotationSetting ----

    @Test
    fun `follow-global takes the global policy and an override ignores it`() {
        // The only statement of the follow-global-or-override rule in the app. A second copy
        // appearing anywhere is what this case is here to catch; there was one, deleted 2026-08-31.
        val global = RotationPolicy.interval(360)
        assertEquals(global, CollectionRotationSetting.FollowGlobal.policyOr(global))
        assertEquals(
            RotationPolicy.PerDay,
            CollectionRotationSetting.Override(RotationPolicy.PerDay).policyOr(global)
        )
    }

    @Test
    fun `GLOBAL is the only input that means follow-global`() {
        assertEquals(CollectionRotationSetting.FollowGlobal, CollectionRotationSetting.decode("GLOBAL"))
        for (raw in listOf(null, "", "global", "nonsense", "OVERRIDE:GLOBAL", "PER_LOCK")) {
            assertNotEquals(
                "decode($raw) must not attach a collection to a cadence the user never chose",
                CollectionRotationSetting.FollowGlobal,
                CollectionRotationSetting.decode(raw)
            )
        }
    }

    @Test
    fun `every legacy collection value becomes an override`() {
        // Pre-global-policy collections each carried their own cadence, so migrating them to
        // "follow global" would silently retime every one of them.
        assertEquals(
            CollectionRotationSetting.Override(RotationPolicy.PerLock),
            CollectionRotationSetting.decode("PER_LOCK")
        )
        assertEquals(
            CollectionRotationSetting.Override(RotationPolicy.interval(60)),
            CollectionRotationSetting.decode("HOURLY")
        )
        assertEquals(
            CollectionRotationSetting.Override(RotationPolicy.PerDay),
            CollectionRotationSetting.decode("PER_DAY")
        )
    }

    @Test
    fun `an unreadable collection value becomes a per-lock override, not follow-global`() {
        assertEquals(
            CollectionRotationSetting.Override(RotationPolicy.PerLock),
            CollectionRotationSetting.decode("nonsense")
        )
    }

    @Test
    fun `a collection setting survives a round trip`() {
        val settings = listOf(
            CollectionRotationSetting.FollowGlobal,
            CollectionRotationSetting.Override(RotationPolicy.interval(90)),
            CollectionRotationSetting.Override(RotationPolicy.PerDay),
            CollectionRotationSetting.Override(RotationPolicy.PerLock),
        )
        for (setting in settings) {
            assertEquals(setting, CollectionRotationSetting.decode(setting.encode()))
        }
        assertEquals("OVERRIDE:INTERVAL:90", CollectionRotationSetting.Override(RotationPolicy.interval(90)).encode())
    }
}
