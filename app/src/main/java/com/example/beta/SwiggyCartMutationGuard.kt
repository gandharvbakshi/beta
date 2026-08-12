package com.example.beta

/** Prevents provider changes while a user-confirmed Swiggy cart update is unresolved. */
internal object SwiggyCartMutationGuard {
    private var mutationInFlight = false
    private var terminalNotice: String? = null
    private var listenerOwner: Any? = null
    private var listener: ((Boolean) -> Unit)? = null

    fun begin() {
        val callback = synchronized(this) {
            mutationInFlight = true
            terminalNotice = null
            listener
        }
        callback?.invoke(true)
    }

    fun end(notice: String? = null) {
        val callback = synchronized(this) {
            mutationInFlight = false
            terminalNotice = notice
            listener
        }
        callback?.invoke(false)
    }

    @Synchronized
    fun isInFlight(): Boolean = mutationInFlight

    fun register(owner: Any, callback: (Boolean) -> Unit) {
        val currentState = synchronized(this) {
            listenerOwner = owner
            listener = callback
            mutationInFlight
        }
        callback(currentState)
    }

    @Synchronized
    fun unregister(owner: Any) {
        if (listenerOwner === owner) {
            listenerOwner = null
            listener = null
        }
    }

    @Synchronized
    fun consumeTerminalNotice(): String? {
        val notice = terminalNotice
        terminalNotice = null
        return notice
    }

    @Synchronized
    internal fun resetForTests() {
        mutationInFlight = false
        terminalNotice = null
        listenerOwner = null
        listener = null
    }
}
