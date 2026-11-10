package com.example.beta

import java.security.MessageDigest

/** Highlight original wording for an explicit edit, never a usable item, label or cart authority. */
internal object GroceryIntentEditHints {
    private val plainInt = Regex("0|[1-9][0-9]{0,3}")

    private fun validated(instruction: String, quotes: List<GroceryIntentReviewV2Quote>): List<GroceryIntentReviewV2Quote> {
        if (instruction.length !in 1..6000 || quotes.size !in 1..3) return emptyList()
        val revision = runCatching { GroceryIntentRequestRevision.begin(instruction) }.getOrNull() ?: return emptyList()
        val ordered = quotes.sortedBy { it.startUtf16 }
        if (ordered.zipWithNext().any { (a, b) -> a.endUtf16 > b.startUtf16 } || ordered.any {
            it.text == instruction || it.text.isBlank() || it.text.length > 320 || !revision.containsSource(it) ||
                bindGroceryIntentProductSource(instruction, it.text) != it ||
                !groceryIntentSourceClauseContextProven(instruction, it.text, it.text.length)
        }) return emptyList()
        return ordered
    }

    fun fromLocal(instruction: String, rows: List<GroceryIntentItem>, affected: List<String>): List<GroceryIntentReviewV2Quote> {
        if (rows.size !in 1..50 || affected.size !in 1..3 || affected.distinct().size != affected.size) return emptyList()
        val bound = rows.map { bindGroceryIntentProductSource(instruction, it.sourceText, maxLength = 6000) ?: return emptyList() }
            .sortedBy { it.startUtf16 }
        if (bound.zipWithNext().any { (a, b) -> a.endUtf16 > b.startUtf16 }) return emptyList()
        return validated(instruction, affected.map { phrase -> bound.singleOrNull { it.text == phrase } ?: return emptyList() })
    }

    fun fromRanges(instruction: String, ranges: List<IntRange>): List<GroceryIntentReviewV2Quote> {
        if (ranges.size !in 1..3 || instruction.length !in 1..6000) return emptyList()
        return validated(instruction, ranges.map { r ->
            if (r.isEmpty() || r.first < 0 || r.last >= instruction.length || r.last - r.first >= 320) return emptyList()
            GroceryIntentReviewV2Quote(instruction.substring(r), r.first, r.last + 1)
        })
    }

    fun fromRejection(instruction: String, body: String): List<GroceryIntentReviewV2Quote> {
        if (instruction.length !in 1..6000 || body.length !in 1..1024) return emptyList()
        val root = runCatching { JsonParser(body).parseStrict() }.getOrNull()
        fun fields(x: JsonValue?, vararg keys: String) = (x as? JsonValue.Obj)
            ?.takeIf { it.duplicateKeys.isEmpty() && it.members.keys == keys.toSet() }?.members
        val detail = fields(fields(root, "detail")?.get("detail"), "accepted", "reason", "editHint") ?: return emptyList()
        if (detail["accepted"] != JsonValue.Bool(false) || detail["reason"] != JsonValue.Str("intent_needs_review")) return emptyList()
        val hint = fields(detail["editHint"], "version", "instructionSha256", "ranges") ?: return emptyList()
        if ((hint["version"] as? JsonValue.Num)?.raw != "1") return emptyList()
        val digest = MessageDigest.getInstance("SHA-256").digest(instruction.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        if (hint["instructionSha256"] != JsonValue.Str(digest)) return emptyList()
        val values = (hint["ranges"] as? JsonValue.Arr)?.items ?: return emptyList()
        if (values.size !in 1..3) return emptyList()
        return fromRanges(instruction, values.map { value ->
            val range = fields(value,"startUtf16","endUtf16") ?: return emptyList()
            fun int(k: String) = (range[k] as? JsonValue.Num)?.raw?.takeIf(plainInt::matches)?.toIntOrNull()
            (int("startUtf16") ?: return emptyList()) until (int("endUtf16") ?: return emptyList())
        })
    }
}

/** Carries only offsets from an actual locally rejected draft; no customer text in exception/log/callback data. */
internal class GroceryLocalEditHold(val ranges: List<IntRange>) : IllegalArgumentException("Grocery wording needs review")
