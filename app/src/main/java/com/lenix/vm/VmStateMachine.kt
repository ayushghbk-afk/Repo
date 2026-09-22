package com.lenix.vm

/**
 * Defines the legal lifecycle transitions for a Lenix instance.
 *
 * Invalid transitions throw [IllegalStateException] so accidental
 * "set running = true" style bugs surface immediately in development.
 */
class VmStateMachine {

    private val transitionTable: Map<VmState, Set<VmState>> = mapOf(
        VmState.NOT_INSTALLED to setOf(VmState.DOWNLOADING, VmState.ERROR),
        VmState.DOWNLOADING to setOf(VmState.VERIFYING, VmState.ERROR),
        VmState.VERIFYING to setOf(VmState.EXTRACTING, VmState.ERROR),
        VmState.EXTRACTING to setOf(VmState.INSTALLING, VmState.ERROR),
        VmState.INSTALLING to setOf(VmState.READY, VmState.ERROR),
        VmState.READY to setOf(VmState.NOT_INSTALLED, VmState.STARTING, VmState.INSTALLING, VmState.ERROR),
        VmState.STARTING to setOf(VmState.RUNNING, VmState.STOPPING, VmState.ERROR),
        VmState.RUNNING to setOf(VmState.STOPPING, VmState.ERROR),
        VmState.STOPPING to setOf(VmState.READY, VmState.ERROR),
        VmState.ERROR to setOf(VmState.NOT_INSTALLED, VmState.DOWNLOADING, VmState.READY),
    )

    /**
     * Returns the resulting state after a transition, without persisting it.
     */
    fun transition(from: VmState, to: VmState): VmState {
        if (from == to) return from
        val allowed = transitionTable[from]
            ?: throw IllegalStateException("No transition table entry exists for $from")
        check(to in allowed) {
            "Illegal VmState transition: $from -> $to (allowed: $allowed)"
        }
        return to
    }

    fun isTransitionAllowed(from: VmState, to: VmState): Boolean = from == to ||
        transitionTable[from]?.contains(to) == true

    /**
     * Move an instance into [to], attempting only legal transitions.
     */
    fun apply(instance: VmInstance, to: VmState, error: VmError? = null): VmInstance {
        val next = transition(instance.state, to)
        return instance.copy(
            state = next,
            lastError = error,
            updatedAt = System.currentTimeMillis(),
        )
    }
}
