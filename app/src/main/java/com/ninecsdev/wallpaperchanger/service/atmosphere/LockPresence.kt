package com.ninecsdev.wallpaperchanger.service.atmosphere

/**
 * Delay between screen-off and returning to the lock visual.
 *
 * The reset rewinds the frame counter and re-rolls the blob layout, and the panel is still fading
 * when screen-off arrives. Waiting for the fade to finish hides it. Unlock latency is unaffected
 */
private const val LOCK_DELAY_MS = 300L

/** How long after the keyguard starts to disappear a newly visible engine still counts as *that* unlock's reveal */
private const val UNLOCK_REVEAL_WINDOW_MS = 500L

/**
 * Something the engine observed. Two kinds, and the difference is the whole point:
 *
 * - **Edges** ([Unlocked]) are transitions. They can be *held* until somebody is watching.
 * - **Levels** (`keyguardLocked` payloads) are readings of how things stand right now. Applied immediately.
 *
 * Readings never travel alone so there is no way to feed a reading and have it mistaken for a transition.
 */
sealed interface LockEvent {
    /** The engine was created. [keyguardLocked] seeds the visual; false for a preview engine. */
    data class Created(val keyguardLocked: Boolean) : LockEvent

    /**
     * The panel came on. The only event a screen toggle faster than the ~190 ms visibility takes to
     * drop cannot skip, which is why the lock state is reconciled here rather than only on edges.
     */
    data class ScreenOn(val keyguardLocked: Boolean) : LockEvent

    /** The panel went off. */
    data object ScreenOff : LockEvent

    /** The keyguard began to disappear. */
    data object Unlocked : LockEvent

    /** The engine is being shown. [keyguardLocked] is read at this instant. */
    data class BecameVisible(val keyguardLocked: Boolean) : LockEvent

    /** The engine is being hidden. */
    data object BecameInvisible : LockEvent

    /** The [LOCK_DELAY_MS] timer fired. */
    data object LockResetDue : LockEvent

    /** The engine is being torn down. */
    data object Destroyed : LockEvent
}

/** Something the engine should do about it. Executed in list order, immediately, on the main looper. */
sealed interface LockDecision {
    /**
     * @param locked true to show the plain photo, false for the effect.
     * @param animate whether a false edge plays the morph.
     */
    data class SetLocked(val locked: Boolean, val animate: Boolean) : LockDecision

    /**
     * Land on the settled frame with no transition. Distinct from `SetLocked(_, animate = false)`:
     * they differ in what happens to a morph already in flight.
     */
    data object SettleNow : LockDecision

    /** @param atMs absolute, on the same clock as the `nowMs` passed to [LockPresence.on]. */
    data class ScheduleLockReset(val atMs: Long) : LockDecision

    data object CancelLockReset : LockDecision
}

/**
 * Decides whether an unlock morphs or settles silently.
 *
 * **Three things the caller must get right, none of which the types enforce:**
 *
 * 1. **[visible] here is authoritative.** Never pass the engine's own visibility alongside an event.
 *    The dismissal is signalled a beat *before* the engine is made visible, and that gap is exactly
 *    where two copies of this flag would disagree.
 * 2. **A preview engine feeds [LockEvent.Created] with `false` and nothing else.** This class has no
 *    notion of preview; a preview's entire lock behavior is that seed.
 * 3. **[LockEvent.BecameInvisible]'s decisions must run before the superclass's visibility
 *    handling**  so [LockDecision.SettleNow] has to land first. [LockEvent.BecameVisible]'s run after.
 */
class LockPresence {

    /** Seeded to match the renderer, which starts on the lock visual. */
    private var locked = true

    /** Starts false, matching an engine that has not been shown yet. */
    private var visible = false

    /** When the keyguard last began to disappear with nobody watching, or 0 for none held. Always consumed. */
    private var heldUnlockAtMs = 0L

    private var destroyed = false

    fun on(event: LockEvent, nowMs: Long): List<LockDecision> {
        if (destroyed) return emptyList()

        return when (event) {
            is LockEvent.Created -> {
                locked = event.keyguardLocked
                // Always emitted, unlike every other SetLocked: the seed has to reach a renderer that may already agree
                listOf(LockDecision.SetLocked(locked, animate = false))
            }

            is LockEvent.ScreenOn ->
                // A live hold outranks the reading. Without this the keyguard, if it already reads
                // clear by the time the panel comes on, would spend the hold's edge without a morph.
                if (heldUnlockAtMs != 0L) listOf(LockDecision.CancelLockReset)
                else listOf(LockDecision.CancelLockReset) + setLocked(event.keyguardLocked, animate = false)

            LockEvent.ScreenOff -> {
                // A held unlock cannot belong to a panel that has gone dark since.
                heldUnlockAtMs = 0L
                listOf(
                    LockDecision.CancelLockReset,
                    LockDecision.ScheduleLockReset(nowMs + LOCK_DELAY_MS)
                )
            }

            LockEvent.Unlocked -> applyOrHoldUnlock(nowMs)

            is LockEvent.BecameVisible -> {
                visible = true
                val held = heldUnlockAtMs
                heldUnlockAtMs = 0L

                when {
                    // The reveal this engine is being made visible *for*. It outranks the reading
                    // because the keyguard reports itself locked throughout its own dismissal
                    held != 0L && nowMs - held <= UNLOCK_REVEAL_WINDOW_MS -> setLocked(false, animate = true)

                    // A wake onto the lock screen shows the photo it will be dismissed from
                    event.keyguardLocked -> setLocked(true, animate = false)

                    // Held too long ago to be this reveal: right state, no morph
                    held != 0L -> setLocked(false, animate = false)

                    // A wake with no keyguard, or a return to a home screen that never locked
                    else -> applyOrHoldUnlock(nowMs)
                }
            }

            LockEvent.BecameInvisible -> {
                visible = false
                listOf(LockDecision.SettleNow)
            }

            LockEvent.LockResetDue ->
                // animate is only consulted on a false edge, so passing false here says what this
                // is rather than relying on it being ignored.
                setLocked(true, animate = false)

            LockEvent.Destroyed -> {
                destroyed = true
                listOf(LockDecision.CancelLockReset)
            }
        }
    }

    /** An unlock reaches the screen if someone is watching, and waits its turn if nobody is. */
    private fun applyOrHoldUnlock(nowMs: Long): List<LockDecision> =
        if (visible) {
            setLocked(false, animate = true)
        } else {
            heldUnlockAtMs = nowMs
            emptyList()
        }

    /**
     * Change-gated, and load-bearing rather than an optimization: a decision list that repeated
     * the current state would stop describing what actually happens, and the tests would be
     * asserting on call cadence instead of behavior.
     */
    private fun setLocked(locked: Boolean, animate: Boolean): List<LockDecision> {
        if (this.locked == locked) return emptyList()
        this.locked = locked
        return listOf(LockDecision.SetLocked(locked, animate))
    }
}
