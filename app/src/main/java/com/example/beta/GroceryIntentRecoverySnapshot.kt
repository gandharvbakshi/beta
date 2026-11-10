package com.example.beta

/** Non-actionable whole-instruction fallback when validation cannot prove a safe partial display. */
internal data class GroceryIntentRecoverySnapshot private constructor(
    private val instruction: String,
    private val opening: GroceryIntentCoverageSpan? = null,
) {
    private val openingText: String?
        get() = opening?.let { instruction.substring(it.startUtf16, it.endUtf16) }

    internal fun toDisplay(): GroceryIntentRecoveryDisplay = GroceryIntentRecoveryDisplay(
        originalInstruction = instruction,
        body = "Beta couldn't be sure about your list, so nothing has been added. Change the words below, then check again.",
        heldRows = listOf(GroceryIntentRecoveryTextRow(
            title = "You said: “$instruction”",
            detail = openingText?.let { "Beta isn't sure if “$it” is part of your shopping list." }
                ?: "Beta couldn't confirm what to buy from these words.",
            spoken = openingText?.trimEnd(' ', '\t', '.', ',', '\r', '\n')?.let {
                "Not confirmed. You said: $it. Beta isn't sure if this is part of your shopping list."
            }
                ?: "Not confirmed. You said: $instruction. Beta couldn't confirm what to buy from these words.",
        )),
        spokenPrompt = "I couldn't be sure about your list. Nothing has been added. Change the words on screen, then tap Check my list again.",
    )

    companion object {
        internal fun generic(instruction: String): GroceryIntentRecoverySnapshot =
            GroceryIntentRecoverySnapshot(instruction)

        /**
         * Explains an unresolved opening, NOT a harmless introduction or a proposed deletion.
         * The caller supplies final coverage only after every original row passed normal checks.
         * Meaningful names, brands or health words still block the list and remain in the editor.
         * Quote the complete opening clause, including any already-neutral words inside it;
         * this is not a claim that each word individually failed validation.
         */
        internal fun withUnresolvedOpening(
            instruction: String,
            firstPrimaryStart: Int,
            finalOwnedSpans: List<GroceryIntentCoverageSpan>,
        ): GroceryIntentRecoverySnapshot {
            val fallback = generic(instruction)
            val openingMatch = OPENING.find(instruction) ?: return fallback
            val end = openingMatch.range.last + 1
            if (end > 120 || end > firstPrimaryStart) return fallback
            val unresolved = uncoveredGroceryIntentRangesBySpan(instruction, finalOwnedSpans)
            if (unresolved.isEmpty() || unresolved.any { it.startUtf16 < 0 || it.endUtf16 > end }) return fallback
            return GroceryIntentRecoverySnapshot(instruction, GroceryIntentCoverageSpan(0, end))
        }

        // A closed display shape, not a personal-name whitelist and never semantic neutrality.
        private const val WORD = "[\\p{L}\\p{M}]+(?:['’-][\\p{L}\\p{M}]+)*"
        private const val NAME = "$WORD(?:[ \\t]+$WORD){0,4}"
        private val OPENING = Regex(
            "\\A(?:$NAME[ \\t]+here|(?:I[ \\t]+am|This[ \\t]+is|My[ \\t]+name[ \\t]+is)[ \\t]+$NAME)" +
                "(?:,[ \\t]*[0-9]{1,3})?[.,\\r\\n]",
            RegexOption.IGNORE_CASE,
        )
    }
}
