package com.ninecsdev.wallpaperchanger.service.atmosphere

/**
 * Delay between screen-off and returning to the lock visual. The reset rewinds the frame counter
 * and re-rolls the blob layout, and the panel is still fading at screen-off; waiting hides it.
 */
private const val LOCK_DELAY_MS = 300L

/** How long after the keyguard starts to disappear a newly visible engine still counts as that unlock's reveal. */
private const val UNLOCK_REVEAL_WINDOW_MS = 500L

/**
 * Something the engine observed: either an edge ([Unlocked], a transition that can be held until
 * somebody is watching) or a level (`keyguardLocked` payloads, readings applied immediately).
 * Readings never travel alone, so one cannot be mistaken for a transition.
 */
sealed interface LockEvent {
    /** [keyguardLocked] seeds the visual; false for a preview engine. */
    data class Created(val keyguardLocked: Boolean) : LockEvent

    /**
     * The only event a screen toggle faster than the ~190 ms visibility takes to drop cannot skip,
     * so the lock state is reconciled here rather than only on edges.
     */
    data class ScreenOn(val keyguardLocked: Boolean) : LockEvent

    data object ScreenOff : LockEvent

    /** The keyguard began to disappear. */
    data object Unlocked : LockEvent

    /** [keyguardLocked] is read at the instant the engine is shown. */
    data class BecameVisible(val keyguardLocked: Boolean) : LockEvent

    data object BecameInvisible : LockEvent

    /** The [LOCK_DELAY_MS] timer fired. */
    data object LockResetDue : LockEvent

    data object Destroyed : LockEvent
}

/** Something the engine should do about an event. Executed in list order, immediately, on the main looper. */
sealed interface LockDecision {
    /** [locked] shows the plain photo, otherwise the effect; [animate] only matters on an unlock edge. */
    data class SetLocked(val locked: Boolean, val animate: Boolean) : LockDecision

    /** Lands on the settled frame. Unlike `SetLocked(_, animate = false)`, it also ends a morph in flight. */
    data object SettleNow : LockDecision

    /** [atMs] is absolute, on the same clock as the `nowMs` passed to [LockPresence.on]. */
    data class ScheduleLockReset(val atMs: Long) : LockDecision

    data object CancelLockReset : LockDecision
}

/**
 * Decides whether an unlock morphs or settles silently. The caller must get three things right:
 *
 * 1. [visible] here is authoritative; never pass the engine's own visibility alongside an event.
 *    The dismissal is signaled a beat before the engine is made visible, and two copies of the
 *    flag would disagree in that gap.
 * 2. A preview engine feeds [LockEvent.Created] with `false` and nothing else.
 * 3. [LockEvent.BecameInvisible]'s decisions must run before the superclass's visibility handling
 *    so [LockDecision.SettleNow] lands first; [LockEvent.BecameVisible]'s run after.
 */
class LockPresence {

    /** Seeded to match the renderer, which starts on the lock visual. */
    private var locked = true

    private var visible = false

    /** When the keyguard last began to disappear with nobody watching; 0 for none held. Always consumed. */
    private var heldUnlockAtMs = 0L

    private var destroyed = false

    fun on(event: LockEvent, nowMs: Long): List<LockDecision> {
        if (destroyed) return emptyList()

        return when (event) {
            is LockEvent.Created -> {
                locked = event.keyguardLocked
                // Always emitted, unlike every other SetLocked: the renderer may already agree.
                listOf(LockDecision.SetLocked(locked, animate = false))
            }

            is LockEvent.ScreenOn ->
                // A live hold outranks the reading, or a keyguard already reading clear would spend
                // the hold's edge without a morph.
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
                    // Outranks the reading: the keyguard reports itself locked throughout its own dismissal.
                    held != 0L && nowMs - held <= UNLOCK_REVEAL_WINDOW_MS -> setLocked(false, animate = true)

                    event.keyguardLocked -> setLocked(true, animate = false)

                    // Held too long ago to be this reveal: right state, no morph.
                    held != 0L -> setLocked(false, animate = false)

                    else -> applyOrHoldUnlock(nowMs)
                }
            }

            LockEvent.BecameInvisible -> {
                visible = false
                listOf(LockDecision.SettleNow)
            }

            LockEvent.LockResetDue -> setLocked(true, animate = false)

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

    /** Change-gated, and load-bearing: a decision list repeating the current state would stop describing what happens. */
    private fun setLocked(locked: Boolean, animate: Boolean): List<LockDecision> {
        if (this.locked == locked) return emptyList()
        this.locked = locked
        return listOf(LockDecision.SetLocked(locked, animate))
    }
}
