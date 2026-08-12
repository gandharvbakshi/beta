package com.example.beta

object SwiggyExecutionMode {
    enum class Mode {
        SCREEN_ASSISTED,
        MCP,
    }

    private val DEFAULT_MODE = Mode.MCP

    @Volatile
    private var sessionMode = DEFAULT_MODE

    fun current(): Mode = sessionMode

    fun usesMcpExperience(): Boolean = current() == Mode.MCP

    fun useMcp() {
        sessionMode = Mode.MCP
    }

    fun useScreenAssisted() {
        sessionMode = Mode.SCREEN_ASSISTED
    }

    fun resetSession() {
        sessionMode = DEFAULT_MODE
    }
}
