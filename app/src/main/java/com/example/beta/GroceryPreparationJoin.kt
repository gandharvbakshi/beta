package com.example.beta

/** UI-thread-only join: no product search can begin until BOTH read-only preparations finish. */
internal class GroceryPreparationJoin<A : Any, B : Any>(private val ready: (A, B) -> Unit) {
    private var first: A? = null
    private var second: B? = null
    private var delivered = false

    fun first(value: A) { first = value; deliver() }
    fun second(value: B) { second = value; deliver() }

    private fun deliver() {
        val a = first ?: return
        val b = second ?: return
        if (delivered) return
        delivered = true
        ready(a, b)
    }
}
