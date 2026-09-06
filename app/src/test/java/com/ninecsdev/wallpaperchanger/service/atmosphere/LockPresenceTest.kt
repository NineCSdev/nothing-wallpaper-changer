package com.ninecsdev.wallpaperchanger.service.atmosphere

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The lock/unlock rule, exercised without a wallpaper surface.
 *
 * Scenarios are named after the real cases they came from.
 */
class LockPresenceTest {

    /** Runs a script of `(event, nowMs)` pairs and returns every decision produced, in order. */
    private fun run(vararg steps: Pair<LockEvent, Long>): List<LockDecision> {
        val presence = LockPresence()
        return steps.flatMap { (event, now) -> presence.on(event, now) }
    }

    private fun locked(animate: Boolean) = LockDecision.SetLocked(locked = true, animate = animate)
    private fun unlocked(animate: Boolean) = LockDecision.SetLocked(locked = false, animate = animate)

    @Test
    fun `boot behind the keyguard morphs on dismissal`() {
        val decisions = run(
            LockEvent.Created(keyguardLocked = true) to 0L,
            LockEvent.BecameVisible(keyguardLocked = true) to 10L,
            LockEvent.Unlocked to 20L
        )

        assertEquals(listOf(locked(animate = false), unlocked(animate = true)), decisions)
    }

    @Test
    fun `wake then unlock morphs`() {
        val decisions = run(
            LockEvent.Created(keyguardLocked = false) to 0L,
            LockEvent.ScreenOff to 100L,
            LockEvent.LockResetDue to 400L,
            LockEvent.ScreenOn(keyguardLocked = true) to 1_000L,
            LockEvent.BecameVisible(keyguardLocked = true) to 1_010L,
            LockEvent.Unlocked to 1_500L
        )

        assertEquals(
            listOf(
                unlocked(animate = false),
                LockDecision.CancelLockReset,
                LockDecision.ScheduleLockReset(400L),
                locked(animate = false),
                LockDecision.CancelLockReset,
                unlocked(animate = true)
            ),
            decisions
        )
    }

    @Test
    fun `unlock into an open app settles instead of morphing`() {
        val decisions = run(
            LockEvent.Created(keyguardLocked = true) to 0L,
            LockEvent.BecameInvisible to 100L,
            LockEvent.Unlocked to 200L
        )

        // The unlock is held, not applied: nothing follows SettleNow.
        assertEquals(listOf(locked(animate = false), LockDecision.SettleNow), decisions)
    }

    @Test
    fun `wake inside the lock delay never reaches the lock visual`() {
        val decisions = run(
            LockEvent.Created(keyguardLocked = false) to 0L,
            LockEvent.ScreenOff to 100L,
            LockEvent.ScreenOn(keyguardLocked = false) to 200L
        )

        assertEquals(
            listOf(
                unlocked(animate = false),
                LockDecision.CancelLockReset,
                LockDecision.ScheduleLockReset(400L),
                LockDecision.CancelLockReset
            ),
            decisions
        )
    }

    @Test
    fun `unlock on the command morphs and a later reading is inert`() {
        val decisions = run(
            LockEvent.Created(keyguardLocked = true) to 0L,
            LockEvent.BecameVisible(keyguardLocked = true) to 10L,
            LockEvent.Unlocked to 20L,
            // The keyguard catching up afterwards must change nothing, or the morph would restart.
            LockEvent.BecameVisible(keyguardLocked = false) to 400L
        )

        assertEquals(listOf(locked(animate = false), unlocked(animate = true)), decisions)
    }

    @Test
    fun `unlock with no command still morphs`() {
        // Same event, different call site: the USER_PRESENT backstop reaches Unlocked too.
        val decisions = run(
            LockEvent.Created(keyguardLocked = true) to 0L,
            LockEvent.BecameVisible(keyguardLocked = true) to 10L,
            LockEvent.Unlocked to 700L
        )

        assertEquals(listOf(locked(animate = false), unlocked(animate = true)), decisions)
    }

    @Test
    fun `arriving already unlocked decides nothing beyond the seed`() {
        val decisions = run(
            LockEvent.Created(keyguardLocked = false) to 0L,
            LockEvent.BecameVisible(keyguardLocked = false) to 10L
        )

        assertEquals(listOf(unlocked(animate = false)), decisions)
    }

    @Test
    fun `a preview engine seeds unlocked and then idles`() {
        val presence = LockPresence()

        // A preview is fed Created(false) and nothing else; this asserts the seed it gets.
        assertEquals(
            listOf(unlocked(animate = false)),
            presence.on(LockEvent.Created(keyguardLocked = false), 0L)
        )
    }

    @Test
    fun `destroyed cancels the timer and goes inert`() {
        val presence = LockPresence()
        presence.on(LockEvent.Created(keyguardLocked = true), 0L)

        assertEquals(
            listOf(LockDecision.CancelLockReset),
            presence.on(LockEvent.Destroyed, 100L)
        )
        assertEquals(emptyList<LockDecision>(), presence.on(LockEvent.ScreenOff, 200L))
        assertEquals(
            emptyList<LockDecision>(),
            presence.on(LockEvent.BecameVisible(keyguardLocked = false), 300L)
        )
    }

    @Test
    fun `direct unlock from a dark panel replays on the next viewer`() {
        val decisions = run(
            LockEvent.Created(keyguardLocked = true) to 0L,
            // Dismissed while nothing is on screen: held, not spent.
            LockEvent.Unlocked to 100L,
            LockEvent.BecameVisible(keyguardLocked = true) to 120L
        )

        assertEquals(listOf(locked(animate = false), unlocked(animate = true)), decisions)
    }

    @Test
    fun `a held unlock past its window settles without morphing`() {
        val decisions = run(
            LockEvent.Created(keyguardLocked = true) to 0L,
            LockEvent.Unlocked to 100L,
            LockEvent.BecameVisible(keyguardLocked = false) to 700L
        )

        assertEquals(listOf(locked(animate = false), unlocked(animate = false)), decisions)
    }

    @Test
    fun `a held unlock belongs to the first viewer only`() {
        val decisions = run(
            LockEvent.Created(keyguardLocked = true) to 0L,
            LockEvent.Unlocked to 100L,
            LockEvent.BecameVisible(keyguardLocked = true) to 120L,
            LockEvent.BecameInvisible to 200L,
            LockEvent.BecameVisible(keyguardLocked = false) to 220L
        )

        assertEquals(
            listOf(
                locked(animate = false),
                unlocked(animate = true),
                LockDecision.SettleNow
                // Nothing for the second wake: already unlocked, and the hold is long gone.
            ),
            decisions
        )
    }

    @Test
    fun `a fast screen toggle reconciles to the keyguard`() {
        val decisions = run(
            LockEvent.Created(keyguardLocked = false) to 0L,
            LockEvent.ScreenOff to 100L,
            // Back on before the lock reset fires, and before visibility has had time to drop.
            LockEvent.ScreenOn(keyguardLocked = true) to 200L
        )

        assertEquals(
            listOf(
                unlocked(animate = false),
                LockDecision.CancelLockReset,
                LockDecision.ScheduleLockReset(400L),
                LockDecision.CancelLockReset,
                locked(animate = false)
            ),
            decisions
        )
    }

    @Test
    fun `a live hold survives the screen coming on`() {
        val decisions = run(
            LockEvent.Created(keyguardLocked = true) to 0L,
            LockEvent.Unlocked to 100L,
            // The keyguard already reads clear here. Spending the hold now would cost the morph.
            LockEvent.ScreenOn(keyguardLocked = false) to 120L,
            LockEvent.BecameVisible(keyguardLocked = false) to 140L
        )

        assertEquals(
            listOf(
                locked(animate = false),
                LockDecision.CancelLockReset,
                unlocked(animate = true)
            ),
            decisions
        )
    }

    @Test
    fun `an unlock is held where a reading is applied`() {
        // The distinction the two event kinds exist for. Same starting state, same panel state,
        // opposite handling: a reading lands immediately, an edge waits for a viewer.
        val reading = run(
            LockEvent.Created(keyguardLocked = true) to 0L,
            LockEvent.ScreenOn(keyguardLocked = false) to 100L
        )
        assertEquals(
            listOf(locked(animate = false), LockDecision.CancelLockReset, unlocked(animate = false)),
            reading
        )

        val edge = run(
            LockEvent.Created(keyguardLocked = true) to 0L,
            LockEvent.Unlocked to 100L
        )
        assertEquals(listOf(locked(animate = false)), edge)
    }
}
