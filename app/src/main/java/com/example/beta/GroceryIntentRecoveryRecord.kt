package com.example.beta

import java.util.Collections
import java.util.UUID

internal enum class GroceryIntentEditKind { PHRASE, WHOLE_MESSAGE }

/** Durable user-authored request lineage; it carries no interpretation, plan, or consent. */
internal class GroceryIntentRecoveryRecord private constructor(
    val requestId: String,
    val originalInstruction: String,
    val instruction: String,
    val revision: Int,
    val mutationGeneration: Long,
    changedRanges: List<ChangedRange>,
    val dirty: PendingEdit?,
) {
    val changedRanges: List<ChangedRange> = Collections.unmodifiableList(ArrayList(changedRanges))

    internal data class ChangedRange(val startUtf16: Int, val endUtf16: Int)

    internal class PendingEdit(val quote: GroceryIntentReviewV2Quote, val text: String,
        val kind: GroceryIntentEditKind = GroceryIntentEditKind.PHRASE) {
        override fun equals(other: Any?): Boolean = other is PendingEdit && quote == other.quote && text == other.text && kind == other.kind
        override fun hashCode(): Int = 31 * (31 * quote.hashCode() + text.hashCode()) + kind.hashCode()
        override fun toString(): String = "PendingEdit(<redacted>)"
    }

    fun withDirty(quote: GroceryIntentReviewV2Quote, text: String,
        kind: GroceryIntentEditKind = GroceryIntentEditKind.PHRASE): GroceryIntentRecoveryRecord? {
        val base = runCatching { GroceryIntentRequestRevision.begin(instruction) }.getOrNull() ?: return null
        if (!base.containsSource(quote) || !validText(text)) return null
        val pending = PendingEdit(quote, text, kind)
        if (dirty == pending) return this
        if (mutationGeneration == Long.MAX_VALUE) return null
        return create(requestId, originalInstruction, instruction, revision, mutationGeneration + 1,
            changedRanges, pending)
    }

    fun withoutDirty(): GroceryIntentRecoveryRecord? {
        if (dirty == null) return this
        if (mutationGeneration == Long.MAX_VALUE) return null
        return create(requestId, originalInstruction, instruction, revision, mutationGeneration + 1,
            changedRanges, null)
    }

    fun commitDirty(): GroceryIntentRecoveryRecord? {
        val edit = dirty ?: return null
        if (revision == Int.MAX_VALUE || mutationGeneration == Long.MAX_VALUE || edit.text.isBlank()) return null
        val base = runCatching { GroceryIntentRequestRevision.begin(instruction) }.getOrNull() ?: return null
        val changed = base.replace(base, edit.quote, edit.text) ?: return null
        val nextRanges = transformRanges(changedRanges, edit.quote, edit.text, changed.instruction) ?: return null
        return create(requestId, originalInstruction, changed.instruction, revision + 1,
            mutationGeneration + 1, nextRanges, null)
    }

    /** Only these transitions can be committed by the encrypted store's full-record CAS. */
    internal fun acceptsSuccessor(updated: GroceryIntentRecoveryRecord): Boolean {
        if (requestId != updated.requestId || originalInstruction != updated.originalInstruction) return false
        if (mutationGeneration == Long.MAX_VALUE || updated.mutationGeneration <= mutationGeneration) return false
        if (instruction == updated.instruction && revision == updated.revision && changedRanges == updated.changedRanges) {
            return updated.mutationGeneration == mutationGeneration + 1 && updated.dirty != dirty
        }
        if (revision == Int.MAX_VALUE || updated.revision != revision + 1) return false
        return updated.mutationGeneration == mutationGeneration + 1 && commitDirty() == updated
    }

    override fun equals(other: Any?): Boolean = other is GroceryIntentRecoveryRecord &&
        requestId == other.requestId && originalInstruction == other.originalInstruction &&
        instruction == other.instruction && revision == other.revision &&
        mutationGeneration == other.mutationGeneration &&
        changedRanges == other.changedRanges && dirty == other.dirty

    override fun hashCode(): Int {
        var result = requestId.hashCode()
        result = 31 * result + originalInstruction.hashCode()
        result = 31 * result + instruction.hashCode()
        result = 31 * result + revision
        result = 31 * result + mutationGeneration.hashCode()
        result = 31 * result + changedRanges.hashCode()
        result = 31 * result + (dirty?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "GroceryIntentRecoveryRecord(revision=$revision, <redacted>)"

    companion object {
        fun begin(text: String): GroceryIntentRecoveryRecord {
            require(validText(text)) { "Invalid request text" }
            return GroceryIntentRecoveryRecord(UUID.randomUUID().toString(), text, text, 0, 0L, emptyList(), null)
        }

        /** Returns null for malformed or unsafe persisted fields. */
        fun restore(
            requestId: String,
            originalInstruction: String,
            instruction: String,
            revision: Int,
            mutationGeneration: Long,
            changedRanges: List<ChangedRange>,
            dirty: PendingEdit?,
        ): GroceryIntentRecoveryRecord? = create(
            requestId, originalInstruction, instruction, revision, mutationGeneration, changedRanges, dirty,
        )

        private fun create(
            requestId: String,
            originalInstruction: String,
            instruction: String,
            revision: Int,
            mutationGeneration: Long,
            changedRanges: List<ChangedRange>,
            dirty: PendingEdit?,
        ): GroceryIntentRecoveryRecord? {
            if (!canonicalUuid(requestId) || revision < 0 || mutationGeneration < 0L ||
                !validText(originalInstruction) || !validText(instruction)) return null
            if (changedRanges.size > MAX_CHANGED_RANGES) return null
            val currentBase = runCatching { GroceryIntentRequestRevision.begin(instruction) }.getOrNull() ?: return null
            var previousEnd = -1
            for (range in changedRanges) {
                if (range.startUtf16 < 0 || range.endUtf16 <= range.startUtf16 || range.endUtf16 > instruction.length ||
                    range.startUtf16 <= previousEnd || !currentBase.containsSource(
                        GroceryIntentReviewV2Quote(instruction.substring(range.startUtf16, range.endUtf16),
                            range.startUtf16, range.endUtf16))) return null // Sorted, coalesced, and grapheme-bound.
                previousEnd = range.endUtf16
            }
            if (dirty != null && (!currentBase.containsSource(dirty.quote) || !validText(dirty.text))) return null
            if (dirty?.kind == GroceryIntentEditKind.WHOLE_MESSAGE &&
                (dirty.quote.startUtf16 != 0 || dirty.quote.endUtf16 != instruction.length || dirty.quote.text != instruction)) return null
            return GroceryIntentRecoveryRecord(requestId, originalInstruction, instruction, revision,
                mutationGeneration, changedRanges, dirty)
        }

        private fun canonicalUuid(value: String): Boolean =
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

        private fun validText(text: String): Boolean =
            runCatching { GroceryIntentRequestRevision.begin(text) }.isSuccess

        private fun transformRanges(
            existing: List<ChangedRange>,
            quote: GroceryIntentReviewV2Quote,
            replacement: String,
            instruction: String,
        ): List<ChangedRange>? {
            val delta = replacement.length - quote.text.length
            val start = quote.startUtf16
            val end = quote.endUtf16
            fun mapStart(position: Int): Int = when {
                position <= start -> position
                position >= end -> position + delta
                else -> start
            }
            fun mapEnd(position: Int): Int = when {
                position <= start -> position
                position >= end -> position + delta
                else -> start + replacement.length
            }

            val transformed = existing.mapNotNull { range ->
                val newStart = mapStart(range.startUtf16)
                val newEnd = mapEnd(range.endUtf16)
                if (newEnd > newStart) ChangedRange(newStart, newEnd) else null
            } + ChangedRange(start, start + replacement.length)
            val result = mutableListOf<ChangedRange>()
            transformed.sortedBy { it.startUtf16 }.forEach { next ->
                val prior = result.lastOrNull()
                if (prior != null && next.startUtf16 <= prior.endUtf16) {
                    result[result.lastIndex] = ChangedRange(prior.startUtf16, maxOf(prior.endUtf16, next.endUtf16))
                } else {
                    result += next
                }
            }
            val expanded = mutableListOf<ChangedRange>()
            val base = runCatching { GroceryIntentRequestRevision.begin(instruction) }.getOrNull() ?: return null
            result.forEach { range -> expanded += expandRangeToSafeBoundary(base, range) ?: return null }
            return mergeRanges(expanded)
        }

        private fun expandRangeToSafeBoundary(base: GroceryIntentRequestRevision, range: ChangedRange): ChangedRange? {
            val instruction = base.instruction
            var start = range.startUtf16
            var end = range.endUtf16
            while (!base.isSourceBoundary(start)) {
                if (start <= 0) return null
                start -= Character.charCount(instruction.codePointBefore(start))
            }
            while (!base.isSourceBoundary(end)) {
                if (end >= instruction.length) return null
                end += Character.charCount(instruction.codePointAt(end))
            }
            return ChangedRange(start, end)
        }

        private fun mergeRanges(ranges: List<ChangedRange>): List<ChangedRange> {
            val merged = mutableListOf<ChangedRange>()
            ranges.sortedBy { it.startUtf16 }.forEach { next ->
                val prior = merged.lastOrNull()
                if (prior != null && next.startUtf16 <= prior.endUtf16) {
                    merged[merged.lastIndex] = ChangedRange(prior.startUtf16, maxOf(prior.endUtf16, next.endUtf16))
                } else {
                    merged += next
                }
            }
            return merged
        }

        private const val MAX_CHANGED_RANGES = 6000
    }
}
