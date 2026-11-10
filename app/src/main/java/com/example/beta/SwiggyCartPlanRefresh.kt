package com.example.beta

/** One main-thread lane for review snapshots and plans. A completed read is not consent,
 * a safe cart plan, or permission to apply; the existing validators/gates decide. */
internal class SwiggyCartPlanRefresh {
    sealed interface Input {
        val operationId: Long
        val address: SwiggyMcpClient.SwiggyAddress

        data class Plan(
            override val operationId: Long,
            override val address: SwiggyMcpClient.SwiggyAddress,
            val request: SwiggyCartReviewRequest,
        ) : Input {
            override fun toString(): String = "SwiggyCartPlanRefresh.Input.Plan(<redacted>)"
        }

        data class Snapshot(
            override val operationId: Long,
            override val address: SwiggyMcpClient.SwiggyAddress,
        ) : Input {
            override fun toString(): String = "SwiggyCartPlanRefresh.Input.Snapshot(<redacted>)"
        }
    }

    class Read internal constructor(val revision: Long, val input: Input) {
        override fun toString(): String = "SwiggyCartPlanRefresh.Read(revision=$revision, input=<redacted>)"
    }

    data class Completion(val current: Read? = null, val next: Read? = null)

    private var revision = 0L
    private var desired: Read? = null
    private var inFlight: Read? = null
    private var completed: Read? = null

    /** Invalidates old read eligibility synchronously; coalesces queued changes. */
    fun select(input: Input): Read? {
        require(input.operationId > 0 && input.address.id.isNotBlank())
        revision = Math.addExact(revision, 1L)
        desired = Read(revision, input)
        completed = null
        return startNext()
    }

    /** Call even for an obsolete operation's result, so it can release the slot. */
    fun complete(read: Read): Completion {
        if (inFlight !== read) return Completion()
        inFlight = null
        if (desired === read) {
            completed = read
            return Completion(current = read)
        }
        return Completion(next = startNext())
    }

    fun isCurrentCompletedPlan(input: Input.Plan): Boolean =
        completed != null && completed === desired && inFlight == null &&
            completed?.input == input && input.request.requestedItems.isNotEmpty()

    /** Do not forget a real HTTP call until its callback; cancellation must not
     * let a new selection start a parallel read. No selection survives this. */
    fun invalidate() {
        desired = null
        completed = null
    }

    private fun startNext(): Read? {
        if (inFlight != null) return null
        val read = desired?.takeIf {
            when (val input = it.input) {
                is Input.Plan -> input.request.requestedItems.isNotEmpty()
                is Input.Snapshot -> true
            }
        } ?: return null
        inFlight = read
        return read
    }
}
