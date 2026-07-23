package com.example.beta

object SwiggyExecutionMode {
    enum class Mode {
        SCREEN_ASSISTED,
        MCP,
    }

    private val DEFAULT_MODE = Mode.SCREEN_ASSISTED

    fun current(): Mode = DEFAULT_MODE

    fun usesMcpExperience(): Boolean = current() == Mode.MCP
}
