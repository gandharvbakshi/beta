package com.example.beta

import com.example.beta.automation.IntentQuantityCandidate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.beta.automation.IntentQuantityCatalogueCheck
import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.IntentQuantityProvenance
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity

class SwiggyQuantityProposalGateTest {
    private val gate = SwiggyQuantityProposalGate()

    @Test fun applicationConsumesTheAcknowledgementExactlyOnce() {
        gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal))
        gate.acknowledge(OPERATION, ADDRESS, PLAN, listOf(proposal), true)
        assertTrue(gate.consumeForApply(OPERATION, ADDRESS, PLAN, listOf(proposal)))
        assertFalse(gate.consumeForApply(OPERATION, ADDRESS, PLAN, listOf(proposal)))
        assertFalse(gate.acknowledge(OPERATION, ADDRESS, PLAN, listOf(proposal), true))
    }

    private val sourceItem = ParsedItem(
        rawText = "2 packets of milk",
        query = "milk",
        quantity = Quantity.Count(2),
        avoidPhrases = listOf("soy milk"),
        intentQuantityProvenance = quantityProvenance(setOf("code-17")),
        intentQuantityCatalogueCheck = IntentQuantityCatalogueCheck(
            provenance = quantityProvenance(setOf("catalogue-code-4")),
            query = "milk",
            candidates = listOf(IntentQuantityCandidate("spin-1", "sku-1", "Milk 500 g", null, null)),
            issue = null,
        ),
    )

    private val candidate = SwiggyMcpClient.RecommendationCandidate(
        spinId = "spin-1",
        label = "Milk 500 g",
        skuId = "sku-1",
    )

    private val evidence = evidence()

    private val proposal = SwiggyQuantityProposalBinding(
        rowIndex = 2,
        source = "2 packets of milk",
        quantity = 2,
        spinId = "spin-1",
        skuId = "sku-1",
        evidence = evidence,
    )

    @Test
    fun uncheckedProposalCannotApplyAndCheckedMatchingProposalCan() {
        gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal))
        assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(proposal)))

        assertTrue(gate.acknowledge(OPERATION, ADDRESS, PLAN, listOf(proposal), checked = true))
        assertTrue(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(proposal)))

        assertTrue(gate.acknowledge(OPERATION, ADDRESS, PLAN, listOf(proposal), checked = false))
        assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(proposal)))
    }

    @Test
    fun everyPrepareResetsAcknowledgementEvenForTheSameSnapshot() {
        gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal))
        assertTrue(gate.acknowledge(OPERATION, ADDRESS, PLAN, listOf(proposal), checked = true))
        assertTrue(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(proposal)))

        gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal))

        assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(proposal)))
    }

    @Test
    fun clearInvalidatesPreparedStateAndAcknowledgement() {
        gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal))
        gate.acknowledge(OPERATION, ADDRESS, PLAN, listOf(proposal), checked = true)
        gate.clear()

        assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(proposal)))
    }

    @Test
    fun staleAcknowledgementsClearStateAndCannotAuthorizeRestoredSnapshot() {
        gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal))

        assertFalse(gate.acknowledge(OPERATION + 1, ADDRESS, PLAN, listOf(proposal), checked = true))
        assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(proposal)))

        gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal))
        assertFalse(gate.acknowledge(OPERATION, ADDRESS, PLAN, listOf(proposal.copy(quantity = 3)), checked = true))
        assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(proposal)))
    }

    @Test
    fun eachBindingFieldAndProposalOrderChangeInvalidatesConsent() {
        val changes = listOf(
            { listOf(proposal.copy(rowIndex = proposal.rowIndex + 1)) },
            { listOf(proposal.copy(source = "2 bottles of milk")) },
            { listOf(proposal.copy(quantity = 3)) },
            { listOf(proposal.copy(spinId = "spin-2")) },
            { listOf(proposal.copy(skuId = "sku-2")) },
            { listOf(proposal.copy(evidence = evidence(sourceItem = sourceItem.copy(query = "different query")))) },
            { listOf(proposal.copy(evidence = evidence(candidate = candidate.copy(label = "Different candidate")))) },
            { listOf(proposal.copy(evidence = evidence(resolverVersion = "resolver-v2"))) },
            { listOf(proposal.copy(evidence = evidence(packSize = "1 kg"))) },
            { listOf(proposal, proposal.copy(rowIndex = 3)) },
        )
        val base = listOf(proposal, proposal.copy(rowIndex = 3, source = "1 banana", spinId = "spin-2"))
        val baseChanged = listOf(base[1], base[0])

        changes.forEach { change ->
            gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal))
            gate.acknowledge(OPERATION, ADDRESS, PLAN, listOf(proposal), checked = true)
            assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, change()))
            assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(proposal)))
        }

        gate.prepare(OPERATION, ADDRESS, PLAN, base)
        gate.acknowledge(OPERATION, ADDRESS, PLAN, base, checked = true)
        assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, baseChanged))
        assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, base))
    }

    @Test
    fun operationAddressAndPlanChangesInvalidateConsent() {
        gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal))
        gate.acknowledge(OPERATION, ADDRESS, PLAN, listOf(proposal), checked = true)
        assertFalse(gate.canApply(OPERATION + 1, ADDRESS, PLAN, listOf(proposal)))

        gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal))
        gate.acknowledge(OPERATION, ADDRESS, PLAN, listOf(proposal), checked = true)
        assertFalse(gate.canApply(OPERATION, "address-2", PLAN, listOf(proposal)))

        gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal))
        gate.acknowledge(OPERATION, ADDRESS, PLAN, listOf(proposal), checked = true)
        assertFalse(gate.canApply(OPERATION, ADDRESS, "plan-2", listOf(proposal)))
    }

    @Test
    fun emptyProposalSetNeedsNoAcknowledgement() {
        gate.prepare(OPERATION, ADDRESS, PLAN, emptyList())
        assertTrue(gate.canApply(OPERATION, ADDRESS, PLAN, emptyList()))
    }

    @Test
    fun prepareRejectsInvalidCountsBlankBindingsAndDuplicateRows() {
        listOf(0, 21).forEach { count ->
            assertPrepareRejected { gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal.copy(quantity = count))) }
        }
        assertPrepareRejected { gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal, proposal.copy(source = "other"))) }
        assertPrepareRejected { gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal.copy(rowIndex = -1))) }
        assertPrepareRejected { gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal.copy(source = " "))) }
        assertPrepareRejected { gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal.copy(spinId = " "))) }
        assertPrepareRejected { gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal.copy(skuId = " "))) }
        assertPrepareRejected { gate.prepare(OPERATION, " ", PLAN, listOf(proposal)) }
        assertPrepareRejected { gate.prepare(OPERATION, ADDRESS, " ", listOf(proposal)) }
        assertPrepareRejected { gate.prepare(0L, ADDRESS, PLAN, listOf(proposal)) }
        assertEvidenceRejected { evidence(resolverVersion = " ") }
        assertEvidenceRejected { evidence(packSize = " ") }
    }

    @Test
    fun prepareDefensivelyCopiesCallerProposalList() {
        val mutable = mutableListOf(proposal)
        gate.prepare(OPERATION, ADDRESS, PLAN, mutable)
        gate.acknowledge(OPERATION, ADDRESS, PLAN, listOf(proposal), checked = true)
        mutable[0] = proposal.copy(quantity = 4)

        assertTrue(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(proposal)))
        assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, mutable))
    }

    @Test
    fun evidenceFactoryDetachesMutableCollectionsRecursivelyAndRedactsDiagnostics() {
        val avoidPhrases = mutableListOf("private avoid phrase")
        val provenanceLabels = mutableSetOf("source-code-1")
        val catalogueProvenanceLabels = mutableSetOf("catalogue-code-2")
        val catalogueCandidates = mutableListOf(
            IntentQuantityCandidate("spin-1", "sku-1", "Milk 500 g", null, null),
        )
        val mutableSource = ParsedItem(
            rawText = "PRIVATE grocery instruction",
            query = "private query",
            quantity = Quantity.Count(2),
            avoidPhrases = avoidPhrases,
            intentQuantityProvenance = quantityProvenance(provenanceLabels),
            intentQuantityCatalogueCheck = IntentQuantityCatalogueCheck(
                provenance = quantityProvenance(catalogueProvenanceLabels),
                query = "private catalogue query",
                candidates = catalogueCandidates,
                issue = null,
            ),
        )
        val captured = evidence(sourceItem = mutableSource)
        val binding = proposal.copy(evidence = captured)
        gate.prepare(OPERATION, ADDRESS, PLAN, listOf(binding))
        assertTrue(gate.acknowledge(OPERATION, ADDRESS, PLAN, listOf(binding), checked = true))

        avoidPhrases += "mutated phrase"
        provenanceLabels += "mutated source label"
        catalogueProvenanceLabels += "mutated catalogue label"
        catalogueCandidates += IntentQuantityCandidate("spin-2", "sku-2", "Other", null, null)

        assertEquals(listOf("private avoid phrase"), captured.sourceItem.avoidPhrases)
        assertEquals(setOf("source-code-1"), captured.sourceItem.intentQuantityProvenance?.opaqueNumericLabels)
        assertEquals(
            setOf("catalogue-code-2"),
            captured.sourceItem.intentQuantityCatalogueCheck?.provenance?.opaqueNumericLabels,
        )
        assertEquals(1, captured.sourceItem.intentQuantityCatalogueCheck?.candidates?.size)
        assertTrue(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(binding)))

        assertUnsupportedCollectionMutation {
            (captured.sourceItem.avoidPhrases as MutableList<String>).add("direct mutation")
        }
        assertUnsupportedCollectionMutation {
            (captured.sourceItem.intentQuantityProvenance!!.opaqueNumericLabels as MutableSet<String>)
                .add("direct mutation")
        }
        assertUnsupportedCollectionMutation {
            (captured.sourceItem.intentQuantityCatalogueCheck!!.provenance!!.opaqueNumericLabels as MutableSet<String>)
                .add("direct mutation")
        }
        assertUnsupportedCollectionMutation {
            (captured.sourceItem.intentQuantityCatalogueCheck!!.candidates as MutableList<IntentQuantityCandidate>)
                .clear()
        }

        assertFalse(captured.toString().contains("PRIVATE grocery instruction"))
        assertFalse(captured.toString().contains("private query"))
        assertFalse(captured.toString().contains("sku-1"))
        assertFalse(binding.toString().contains("private query"))
        assertFalse(binding.toString().contains("sku-1"))
    }

    private fun assertPrepareRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected invalid proposal binding to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation rejection.
        }
    }

    private fun assertEvidenceRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected invalid proposal evidence to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation rejection.
        }
    }

    private fun assertUnsupportedCollectionMutation(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected proposal evidence collection to be immutable")
        } catch (_: UnsupportedOperationException) {
            // Expected immutable snapshot.
        }
    }

    private fun evidence(
        sourceItem: ParsedItem = this.sourceItem,
        candidate: SwiggyMcpClient.RecommendationCandidate = this.candidate,
        resolverVersion: String = "resolver-v1",
        packSize: String = "500 g",
    ) = SwiggyQuantityProposalEvidence.snapshot(sourceItem, candidate, resolverVersion, packSize)

    private fun quantityProvenance(labels: Set<String>) = IntentQuantityProvenance(
        origin = IntentQuantityOrigin.LOCAL,
        primaryText = "2 packets of milk",
        rawText = "2 packets of milk",
        resolvedQuantity = Quantity.Count(2),
        local = null,
        query = "milk",
        strictMatchPhrase = null,
        retailPackCount = true,
        logicalPieceCount = false,
        opaqueNumericLabels = labels,
    )

    private companion object {
        const val OPERATION = 42L
        const val ADDRESS = "address-1"
        const val PLAN = "plan-token-1"
    }
}
