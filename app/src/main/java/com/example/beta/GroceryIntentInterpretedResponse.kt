package com.example.beta

/** Strict response envelope for the explicitly selected interpreted contract. */
internal object GroceryIntentInterpretedResponse {
    private val responseKeys = setOf("accepted", "draft", "model", "promptVersion", "latencyMs", "usage")
    private val usageKeys = setOf("input_tokens", "output_tokens", "total_tokens")

    fun parse(body: String, originalInstruction: String): GroceryIntentInterpretedReview {
        require(body.length <= 700_000)
        val root = JsonParser(body).parseStrict().strictObject()
        require(root.keys == responseKeys)
        require(root.getValue("accepted") == JsonValue.Bool(true))
        root.getValue("model").strictString().also { require(it.isNotBlank() && it.length in 1..120 && it.none { char -> char.isISOControl() }) }
        root.getValue("promptVersion").strictString().also { require(it.isNotBlank() && it.length in 1..80 && it.none { char -> char.isISOControl() }) }
        root.getValue("latencyMs").strictFiniteNumber().also { require(it >= 0.0) }
        val usage = root.getValue("usage").strictObject()
        require(usage.keys.all { it in usageKeys })
        usage.values.forEach { it.strictFiniteNumber().also { n -> require(n in 0.0..100_000_000.0) } }
        return GroceryIntentInterpretedReview.parseValue(root.getValue("draft"), originalInstruction, body)
    }
}

private fun JsonValue.strictObject(): Map<String, JsonValue> =
    (this as? JsonValue.Obj)?.also { require(it.duplicateKeys.isEmpty()) }?.members ?: error("Expected object")

private fun JsonValue.strictString(): String = (this as? JsonValue.Str)?.value ?: error("Expected string")

private fun JsonValue.strictFiniteNumber(): Double =
    (this as? JsonValue.Num)?.raw?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("Expected finite number")

