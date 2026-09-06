package com.ninecsdev.wallpaperchanger.logic

import com.ninecsdev.wallpaperchanger.model.LifecycleVerdict
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the two pure rules behind the service's lifecycle: the battery-saver decision and the
 * resolution of the authoritative [ServiceState].
 *
 * The intent table around them is not here (it needs a built [ServiceLifecycle] which registers a
 * broadcast receiver and starts flows on construction)
 */
class ServiceLifecycleRulesTest {

    private val policies = BatterySaverPolicy.entries

    private val everyRawState = listOf(
        ServiceState.Running,
        ServiceState.Loading,
        ServiceState.Stopping,
        ServiceState.Stopped,
        ServiceState.Paused,
        ServiceState.DisabledPowerSave,
        ServiceState.DisabledNoCollection,
    )

    private val bools = listOf(false, true)

    // ---- batterySaverAction: the whole table, all six cells ----

    @Test
    fun `leaving power save clears the block whatever the policy`() {
        // The asymmetry the old service encoded as "only resume if policy == PAUSE" - right answer,
        // wrong reason, since a STOP-policy service was already dead and had nothing to resume.
        for (policy in policies) {
            assertEquals(
                "not in power save under $policy",
                LifecycleVerdict.RunActive,
                batterySaverAction(isPowerSave = false, policy = policy)
            )
        }
    }

    @Test
    fun `in power save each policy gives its own verdict`() {
        assertEquals(LifecycleVerdict.RunActive, batterySaverAction(true, BatterySaverPolicy.IGNORE))
        assertEquals(LifecycleVerdict.RunPaused, batterySaverAction(true, BatterySaverPolicy.PAUSE))
        assertEquals(LifecycleVerdict.Abort, batterySaverAction(true, BatterySaverPolicy.STOP))
    }

    @Test
    fun `the rule is total over its inputs`() {
        // Six cells, and every one of them named above. A policy added later lands here first.
        val cells = bools.flatMap { save -> policies.map { save to it } }
        assertEquals(6, cells.size)
        for ((isPowerSave, policy) in cells) {
            batterySaverAction(isPowerSave, policy)
        }
    }

    // ---- resolve ----

    @Test
    fun `no active collection outranks every other signal`() {
        // Including a raw Running with the service genuinely alive: without a collection there is
        // nothing to rotate, so the UI must say so rather than report a service that cannot work.
        for (raw in everyRawState) {
            for (persisted in bools) {
                for (alive in bools) {
                    for (blocking in bools) {
                        assertEquals(
                            "raw=$raw persisted=$persisted alive=$alive blocking=$blocking",
                            ServiceState.DisabledNoCollection,
                            resolve(raw, persisted, alive, blocking, hasActiveCollection = false)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `loading and stopping pass through untouched`() {
        // Transitions the resolver must not second-guess. This is why the notification reads the raw
        // lifecycle intent and not this function.
        for (raw in listOf(ServiceState.Loading, ServiceState.Stopping)) {
            for (persisted in bools) {
                for (alive in bools) {
                    for (blocking in bools) {
                        assertSame(
                            "raw=$raw persisted=$persisted alive=$alive blocking=$blocking",
                            raw,
                            resolve(raw, persisted, alive, blocking, hasActiveCollection = true)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `either liveness signal alone is enough to keep a running or paused state`() {
        // The two disagree in the ordinary course of a start and a stop: the tracker goes live before
        // the flag is persisted, and the flag survives a crash the tracker did not see. Requiring
        // both would blink the UI to Stopped in the gap.
        for (raw in listOf(ServiceState.Running, ServiceState.Paused)) {
            assertSame("tracker alone", raw, resolve(raw, persistedRunning = false, isAlive = true, powerSaveBlocking = false, hasActiveCollection = true))
            assertSame("flag alone", raw, resolve(raw, persistedRunning = true, isAlive = false, powerSaveBlocking = false, hasActiveCollection = true))
            assertSame("both", raw, resolve(raw, persistedRunning = true, isAlive = true, powerSaveBlocking = false, hasActiveCollection = true))
        }
    }

    @Test
    fun `a paused state is not normalised into running`() {
        // Paused and Running are both "marked active"; collapsing them would tell the user rotation
        // is happening while power save has it stopped.
        assertSame(
            ServiceState.Paused,
            resolve(ServiceState.Paused, persistedRunning = true, isAlive = true, powerSaveBlocking = true, hasActiveCollection = true)
        )
    }

    @Test
    fun `a running state with no liveness at all means the service died without saying so`() {
        for (raw in listOf(ServiceState.Running, ServiceState.Paused)) {
            assertEquals(
                "$raw, nothing alive",
                ServiceState.Stopped,
                resolve(raw, persistedRunning = false, isAlive = false, powerSaveBlocking = false, hasActiveCollection = true)
            )
            assertEquals(
                "$raw, nothing alive and power save blocking",
                ServiceState.DisabledPowerSave,
                resolve(raw, persistedRunning = false, isAlive = false, powerSaveBlocking = true, hasActiveCollection = true)
            )
        }
    }

    @Test
    fun `a stopped state reports why it is stopped`() {
        // Stopped and DisabledPowerSave are the same fact with different causes, and the difference is
        // the whole reason the tile can offer a start on one and not the other.
        for (persisted in bools) {
            for (alive in bools) {
                assertEquals(
                    "persisted=$persisted alive=$alive",
                    ServiceState.Stopped,
                    resolve(ServiceState.Stopped, persisted, alive, powerSaveBlocking = false, hasActiveCollection = true)
                )
                assertEquals(
                    "persisted=$persisted alive=$alive, blocking",
                    ServiceState.DisabledPowerSave,
                    resolve(ServiceState.Stopped, persisted, alive, powerSaveBlocking = true, hasActiveCollection = true)
                )
            }
        }
    }

    @Test
    fun `resolve answers for every state without throwing`() {
        // DisabledPowerSave and DisabledNoCollection are resolved-only states and never arrive as raw
        // input. They are in the `when` to keep it total; this asserts they stay handled rather than
        // that they are supported.
        for (raw in everyRawState) {
            for (persisted in bools) {
                for (alive in bools) {
                    for (blocking in bools) {
                        resolve(raw, persisted, alive, blocking, hasActiveCollection = true)
                    }
                }
            }
        }
    }
}
