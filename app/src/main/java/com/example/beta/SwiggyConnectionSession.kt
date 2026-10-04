package com.example.beta

/** A failed status read is not evidence that the provider has signed the user out. */
internal class SwiggyConnectionSession {
    enum class Presentation { CHECKING, CONFIRMED, RETRY }
    data class Request(val generation: Long, val sequence: Long)

    var generation = 0L
        private set
    var state = SwiggyMcpClient.ConnectionState.DISCONNECTED
        private set
    var presentation = Presentation.CHECKING
        private set
    private var sequence = 0L
    private var activeRequest: Request? = null

    val canUseConnection: Boolean
        get() = presentation == Presentation.CONFIRMED && state == SwiggyMcpClient.ConnectionState.READY

    fun invalidate() {
        generation++
        activeRequest = null
        presentation = Presentation.CHECKING
    }

    fun beginStatus(): Request? {
        if (activeRequest != null) return null
        return Request(generation, ++sequence).also {
            activeRequest = it
            presentation = Presentation.CHECKING
        }
    }

    /** A stale or duplicate callback must not settle a newer request. */
    fun settleStatus(request: Request): Boolean {
        if (request != activeRequest || request.generation != generation) return false
        activeRequest = null
        return true
    }

    fun confirm(value: SwiggyMcpClient.ConnectionState) {
        state = value
        presentation = Presentation.CONFIRMED
    }

    fun fail(reconnectRequired: Boolean) {
        if (reconnectRequired) confirm(SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED)
        else presentation = Presentation.RETRY
    }
}
