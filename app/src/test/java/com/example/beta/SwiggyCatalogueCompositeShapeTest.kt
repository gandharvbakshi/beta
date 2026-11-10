package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyCatalogueCompositeShapeTest {
    @Test
    fun completeTypedFieldsProveCanonicalCompositeShapes() {
        val candidates = listOf(
            candidate(fields = CatalogueQuantityFields(packSize = "2 x 500 ml")),
            candidate(fields = CatalogueQuantityFields(packSize = "500 ml x 2")),
            candidate(fields = CatalogueQuantityFields(packSize = "500mlx2")),
            candidate(fields = CatalogueQuantityFields(packSize = "2×500ml")),
            candidate(fields = CatalogueQuantityFields(packSize = "2x500ml")),
            candidate(fields = CatalogueQuantityFields(packSize = "500 ml × 2")),
        )
        val shapes = candidates.map { offer ->
            val before = offer.copy()
            val proven = requireNotNull(swiggyCatalogueCompositeShape(offer))
            assertEquals(before, offer)
            assertEquals("2 x 500 ml", proven.display)
            assertEquals("SwiggyRetailOfferContents.ProvenComposite(<redacted>)", proven.toString())
            assertFalse(proven.toString().contains("500 ml"))
            proven.shape
        }
        assertTrue(shapes.all { it == shapes.first() })

        val corroborated = candidate(
            fields = CatalogueQuantityFields(
                variant = "500 ml × 2",
                packSize = "2 x 500 ml",
                quantityDescription = "2x500ml",
            ),
        )
        assertEquals(shapes.first(), requireNotNull(swiggyCatalogueCompositeShape(corroborated)).shape)

        val gramAliases = listOf("2 x 500 gm", "500 g x 2").map { shape ->
            requireNotNull(swiggyCatalogueCompositeShape(candidate(fields = CatalogueQuantityFields(packSize = shape)))).shape
        }
        assertTrue(gramAliases.all { it == gramAliases.first() })
        assertEquals("2 x 500 g", gramAliases.first().display)

        val nonNumericLabel = candidate(label = "Milk", fields = CatalogueQuantityFields(packSize = "2 x 500 ml"))
        assertEquals(shapes.first(), requireNotNull(swiggyCatalogueCompositeShape(nonNumericLabel)).shape)
    }

    @Test
    fun oneCompleteTypedFieldMustOwnBothFactorAndInnerSize() {
        assertNull(swiggyCatalogueCompositeShape(candidate(
            fields = CatalogueQuantityFields(packSize = "2 x", quantityDescription = "500 ml"),
        )))
        assertNull("Display text cannot supply typed authority", swiggyCatalogueCompositeShape(
            candidate(label = "Milk 2 x 500 ml", fields = null),
        ))
        assertNull(swiggyCatalogueCompositeShape(candidate(fields = CatalogueQuantityFields(packSize = "500 ml"))))
        assertNull(swiggyCatalogueCompositeShape(candidate(fields = CatalogueQuantityFields(packSize = "1 L"))))
    }

    @Test
    fun inconsistentOrExtraRawShapesVetoTypedEvidence() {
        val authoritative = CatalogueQuantityFields(packSize = "2 x 500 ml")
        val candidates = listOf(
            candidate(label = "Milk 3 x 500 ml", fields = authoritative), // Same inner size, wrong factor.
            candidate(label = "Milk 4 x 250 ml", fields = authoritative), // Same total, different shape.
            candidate(fields = CatalogueQuantityFields(packSize = "2 x 500 ml", variant = "4 x 250 ml")),
            candidate(fields = CatalogueQuantityFields(packSize = "2 x 500 ml", quantityDescription = "1 L")),
            candidate(fields = CatalogueQuantityFields(packSize = "2 x 500 ml", subTitle = "plus 250 ml")),
            candidate(label = "Milk Twin", fields = CatalogueQuantityFields(packSize = "2 x 500 ml", subTitle = "Pack")),
        )
        candidates.forEachIndexed { index, offer ->
            assertNull("Contradictory raw/typed shape case $index", swiggyCatalogueCompositeShape(offer))
        }

        val sameShapeCorroboration = candidate(
            fields = CatalogueQuantityFields(packSize = "2 x 500 ml", variant = "500 ml x 2"),
        )
        assertNotNull(swiggyCatalogueCompositeShape(sameShapeCorroboration))
    }

    @Test
    fun invalidProviderIdentityVersionSourceAndInvalidityFlagCannotProveShape() {
        val valid = candidate(fields = CatalogueQuantityFields(packSize = "2 x 500 ml"))
        val evidence = requireNotNull(valid.catalogueQuantity)
        val invalid = listOf(
            valid.copy(skuId = "sku-other"),
            valid.copy(catalogueQuantity = evidence.copy(spinId = "spin-other")),
            valid.copy(catalogueQuantity = evidence.copy(skuId = "sku-other")),
            valid.copy(catalogueQuantity = evidence.copy(version = 2)),
            valid.copy(catalogueQuantity = evidence.copy(source = "product_detail")),
            valid.copy(catalogueQuantityInvalid = true),
        )
        invalid.forEachIndexed { index, offer ->
            assertNull("Invalid catalogue binding case $index", swiggyCatalogueCompositeShape(offer))
        }
    }

    @Test
    fun malformedAmbiguousAndUnsupportedShapesDoNotBecomeTypedProof() {
        val invalidFields = listOf(
            "0 x 500 ml",
            "2 x 0 ml",
            "1 x 1000 ml",
            "100 x 10 ml",
            "2.5 x 500 ml",
            "1/2 x 500 ml",
            "2-3 x 500 ml",
            "+2 x 500 ml",
            "2 x -500 ml",
            "two x 500 ml",
            "2 xx 500 ml",
            "2 x 2 x 500 ml",
            "2 x 500 ml plus 1",
            "maybe 2 x 500 ml",
            "2 x 500 ml bonus",
            "2 x 500 ml and 2 x 500 ml",
        )
        invalidFields.forEachIndexed { index, field ->
            assertNull("Malformed or ambiguous shape case $index: $field", exactCompositeRetailShape(field))
            assertNull("Malformed or ambiguous typed case $index", swiggyCatalogueCompositeShape(
                candidate(fields = CatalogueQuantityFields(packSize = field)),
            ))
        }
    }

    @Test
    fun shapeProofDoesNotInferNamedContainerOrRequestedCartCount() {
        val offer = requireNotNull(swiggyCatalogueCompositeShape(candidate(
            label = "Milk Cartons",
            fields = CatalogueQuantityFields(packSize = "2 x 500 ml"),
        )))
        assertEquals(2, offer.shape.factor)
        assertEquals(GroceryPackMeasure.of(BigDecimal("500"), "ml"), offer.shape.inner)
        assertEquals("2 x 500 ml", offer.display)
        // ProvenComposite carries the typed catalogue shape only; it has no
        // named-container or source-request-count field and is not wired here
        // to a cart resolver.
        assertEquals(CatalogueQuantityFields(packSize = "2 x 500 ml"), offer.catalogue.fields)
    }

    private fun candidate(
        label: String = "Milk",
        fields: CatalogueQuantityFields? = CatalogueQuantityFields(packSize = "2 x 500 ml"),
        spinId: String = "spin-1",
        skuId: String? = "sku-1",
        evidenceSpinId: String = spinId,
        evidenceSkuId: String = skuId.orEmpty(),
        version: Int = 1,
        source: String = "search_variation",
        invalid: Boolean = false,
    ): RecommendationCandidate = RecommendationCandidate(
        spinId = spinId,
        skuId = skuId,
        label = label,
        catalogueQuantity = fields?.let {
            CatalogueQuantityEvidence(version, source, evidenceSpinId, evidenceSkuId, it)
        },
        catalogueQuantityInvalid = invalid,
    )
}
