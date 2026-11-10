package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwiggyCatalogueSourcePiecesTest {
    @Test
    fun exactSkuBoundPieceFieldsAndCompleteContainerFactsAreAccepted() {
        assertEquals(6, swiggyCatalogueSourcePieceCount(candidate(
            fields = CatalogueQuantityFields(variantName = "6 pcs"),
            title = "Milk 6 pcs",
        )))
        assertEquals(6, swiggyCatalogueSourcePieceCount(candidate(
            fields = CatalogueQuantityFields(variantName = "6 pieces"),
        )))
        assertEquals(6, swiggyCatalogueSourcePieceCount(candidate(
            fields = CatalogueQuantityFields(variantName = "6 pcs", quantityDescription = "6 pieces", packSize = "6 pcs"),
            title = "Milk 6 pcs",
        )))
        assertEquals(6, swiggyCatalogueSourcePieceCount(candidate(
            fields = CatalogueQuantityFields(variantName = "Box of 6 pieces"),
            title = "Milk",
        )))
        assertEquals(6, swiggyCatalogueSourcePieceCount(candidate(
            fields = CatalogueQuantityFields(variantName = "6 pieces per box"),
            title = "Milk Box",
        )))
    }

    @Test
    fun titleOnlySplitAndUnboundEvidenceNeverEstablishPieceCount() {
        assertNull(swiggyCatalogueSourcePieceCount(candidate(fields = null, title = "Milk 6 pcs")))
        assertNull(swiggyCatalogueSourcePieceCount(candidate(
            fields = CatalogueQuantityFields(variantName = "Box of", quantityDescription = "6"),
        )))
        assertNull(swiggyCatalogueSourcePieceCount(candidate(
            fields = CatalogueQuantityFields(variantName = "6 pcs"), sku = null, evidenceSku = "sku-1",
        )))
        assertNull(swiggyCatalogueSourcePieceCount(candidate(
            fields = CatalogueQuantityFields(variantName = "6 pcs"), evidenceSku = "different-sku",
        )))
        assertNull(swiggyCatalogueSourcePieceCount(candidate(
            fields = CatalogueQuantityFields(variantName = "6 pcs"), evidenceSpin = "different-spin",
        )))
    }

    @Test
    fun qualifiedConflictingMalformedAndUnknownNumbersVetoPieceCounts() {
        listOf(
            "about 6 pcs",
            "approximately 6 pcs",
            "~6 pcs",
            "5-6 pcs",
            "+6 pcs",
            "-6 pcs",
            "6.5 pcs",
            "six pcs",
            "6 pcs free",
            "6 pcs and bonus pack",
            "Buy 1 get 1 6 pcs",
        ).forEach { text ->
            assertNull("qualified or malformed evidence: $text", swiggyCatalogueSourcePieceCount(candidate(
                fields = CatalogueQuantityFields(variantName = text),
            )))
        }

        assertNull(swiggyCatalogueSourcePieceCount(candidate(
            fields = CatalogueQuantityFields(variantName = "6 pcs", quantityDescription = "8 pcs"),
        )))
        assertNull(swiggyCatalogueSourcePieceCount(candidate(
            fields = CatalogueQuantityFields(variantName = "6 pcs"),
            title = "Milk 8 pcs",
        )))
        assertNull(swiggyCatalogueSourcePieceCount(candidate(
            fields = CatalogueQuantityFields(variantName = "6 pcs"),
            title = "Milk 6 pcs Model 42",
        )))
        assertNull(swiggyCatalogueSourcePieceCount(candidate(
            fields = CatalogueQuantityFields(variantName = "6 pcs"),
            subtitle = "5-6 pcs",
        )))
        listOf(
            "Milk twelve pieces",
            "Milk two boxes",
            "A2 Milk 6 pcs",
            "Milk 6 pcs 6 pcs",
            "Milk without 6 pcs",
            "Milk 6 pcs maybe",
            "Milk if available",
            "Milk 6 pcs/kg",
            "Milk 6 pcs ÷ kg",
        ).forEach { rawField ->
            assertNull("raw field must not add an unowned numeric or qualified claim: $rawField",
                swiggyCatalogueSourcePieceCount(candidate(
                    fields = CatalogueQuantityFields(variantName = "6 pcs"),
                    title = rawField,
                )))
        }
    }

    @Test
    fun containerContentsCannotCorroborateAnUnownedDenominator() {
        listOf("Box of 6 pieces/kg", "Box of 6 pieces ÷ kg", "Box of 6 pieces = kg").forEach { raw ->
            assertNull(swiggyCatalogueSourcePieceCount(candidate(
                fields = CatalogueQuantityFields(variantName = "Box of 6 pieces"), subtitle = raw,
            )))
        }
    }

    private fun candidate(
        fields: CatalogueQuantityFields? = CatalogueQuantityFields(variantName = "6 pcs"),
        title: String = "Milk",
        subtitle: String? = null,
        sku: String? = "sku-1",
        evidenceSku: String? = sku,
        evidenceSpin: String = "spin-1",
    ): RecommendationCandidate {
        val evidence = fields?.let {
            CatalogueQuantityEvidence(1, "search_variation", evidenceSpin, requireNotNull(evidenceSku), it)
        }
        return RecommendationCandidate(
            spinId = "spin-1",
            label = title,
            subtitle = subtitle,
            skuId = sku,
            catalogueQuantity = evidence,
        )
    }
}
