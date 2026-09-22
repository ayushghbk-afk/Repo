package com.lenix.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VmStateMachineTest {

    private val machine = VmStateMachine()

    @Test
    fun `install path reaches ready`() {
        val instance = VmInstance.DEFAULT
        var current = instance
        current = machine.apply(current, VmState.DOWNLOADING)
        current = machine.apply(current, VmState.VERIFYING)
        current = machine.apply(current, VmState.EXTRACTING)
        current = machine.apply(current, VmState.INSTALLING)
        current = machine.apply(current, VmState.READY)
        assertEquals(VmState.READY, current.state)
    }

    @Test
    fun `start and stop are legal transitions`() {
        // Start/stop only applies to an installed instance: NOT_INSTALLED -> READY is
        // illegal on purpose (see `invalid transitions are rejected` below and
        // docs/ARCHITECTURE.md §7.4), so seed the machine with a ready one.
        val ready = VmInstance.DEFAULT.copy(state = VmState.READY)
        val running = machine.apply(ready, VmState.STARTING).let { machine.apply(it, VmState.RUNNING) }
        val stopped = machine.apply(running, VmState.STOPPING).let { machine.apply(it, VmState.READY) }
        assertEquals(VmState.READY, stopped.state)
    }

    @Test
    fun `invalid transitions are rejected`() {
        assertThrows(IllegalStateException::class.java) {
            machine.apply(VmInstance.DEFAULT, VmState.RUNNING)
        }
        assertThrows(IllegalStateException::class.java) {
            machine.apply(VmInstance.DEFAULT, VmState.READY)
        }
    }
}
