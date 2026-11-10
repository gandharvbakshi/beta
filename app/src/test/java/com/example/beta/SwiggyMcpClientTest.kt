package com.example.beta

import com.example.beta.SwiggyMcpClient.RequestedItem
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.IntentCartQuantityMode
import com.example.beta.automation.IntentLocalAmount
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class SwiggyMcpClientTest {
    private fun auditedItem(
        basket: AuditedIntentBasket,
        rowIndex: Int,
        source: String,
        query: String,
        quantity: Quantity,
        role: String,
        amountText: String,
        unit: String,
        pack: IntentLocalAmount? = null,
    ): ParsedItem {
        val atom = IntentLocalAmount(source, 0, amountText.length, amountText, BigDecimal(amountText), unit)
        val parsed = ParsedItem(source, query, quantity, strictMatchPhrase = query,
            requiresIntentQuantityProof = true)
        val noun = if (source == "2 bars chocolate") "bars chocolate" else query
        val fixtureAmount = if (unit == "lb") atom.copy(unit = "kg") else atom
        val subject = requireNotNull(SourceMerchandise.compile(source, GroceryIntentReviewV2Quote(source, 0, source.length),
            source.length, noun, role, null, fixtureAmount, pack, GroceryIntentInterpretedAmounts(source), emptyList()))
        return AuditedIntentProvenance.create(basket, rowIndex, parsed, role, null, subject, atom, pack,
            emptyList(), null, bareHighPieceIdentity = null).project()
    }

    @Test
    fun recommendationBatchWireAddsAlignedCanonicalPhysicalTotalsOnlyForAuditedRows() {
        val basket = AuditedIntentBasket("1 kg rice and 2 bars chocolate", "synthetic", 2, "synthetic", emptyList())
        val rice = auditedItem(basket, 0, "1 kg rice", "rice", Quantity.Weight(1000),
            "physical_total", "1", "kg")
        val chocolate = auditedItem(basket, 1, "2 bars chocolate", "chocolate", Quantity.Count(2),
            "requested_pieces", "2", "count")
        assertEquals(listOf(1000 to "g", null), SwiggyMcpClient.auditedPhysicalTotals(listOf(rice, chocolate)))

        val packSource = "1 kg rice in 250 g packs"
        val pack = IntentLocalAmount(packSource, packSource.indexOf("250"), packSource.indexOf("250") + 3,
            "250", BigDecimal("250"), "g")
        val physicalWithPack = auditedItem(AuditedIntentBasket(packSource, "synthetic", 1, "synthetic", emptyList()),
            0, packSource, "rice", Quantity.Weight(1000), "physical_total", "1", "kg", pack)
        assertNull(SwiggyMcpClient.auditedPhysicalTotals(listOf(physicalWithPack)))

        val legacy = ParsedItem("1 kg rice", "rice", Quantity.Weight(1000), strictMatchPhrase = "rice")
        assertNull(SwiggyMcpClient.auditedPhysicalTotals(listOf(legacy)))
        assertNull(SwiggyMcpClient.auditedPhysicalTotals(emptyList()))
    }

    @Test
    fun recommendationBatchWireOmitsUnboundMalformedAndDimensionallyInconsistentTotals() {
        fun oneRow(source: String, quantity: Quantity = Quantity.Weight(1000), unit: String = "g"): ParsedItem =
            auditedItem(AuditedIntentBasket(source, "synthetic", 1, "synthetic", emptyList()), 0,
                source, "rice", quantity, "physical_total", source.substringBefore(' '), unit)

        // Its valid proof belongs to a declared two-row basket; no partial wire assertion is allowed.
        val countMismatchBasket = AuditedIntentBasket("1000 g rice and 500 g sugar", "synthetic", 2, "synthetic", emptyList())
        val countMismatch = auditedItem(countMismatchBasket, 0, "1000 g rice", "rice", Quantity.Weight(1000),
            "physical_total", "1000", "g")
        assertNull(SwiggyMcpClient.auditedPhysicalTotals(listOf(countMismatch)))

        val malformedUnit = oneRow("1 kg rice", unit = "lb") // genuine rice proof, deliberately damaged wire unit
        assertNull(SwiggyMcpClient.auditedPhysicalTotals(listOf(malformedUnit)))

        val quantityMismatch = oneRow("1000 g rice", quantity = Quantity.Weight(500))
        assertNull(SwiggyMcpClient.auditedPhysicalTotals(listOf(quantityMismatch)))

        val firstBasket = AuditedIntentBasket("1000 g rice", "one", 1, "one", emptyList())
        val secondBasket = AuditedIntentBasket("500 g sugar", "two", 1, "two", emptyList())
        val mixedBaskets = listOf(
            auditedItem(firstBasket, 0, "1000 g rice", "rice", Quantity.Weight(1000), "physical_total", "1000", "g"),
            auditedItem(secondBasket, 0, "500 g sugar", "sugar", Quantity.Weight(500), "physical_total", "500", "g"),
        )
        assertNull(SwiggyMcpClient.auditedPhysicalTotals(mixedBaskets))
    }

    @Test fun recommendationSearchWarningRequiresExactStringArrayMember() {
        val accepted = SwiggyMcpClient.parseRecommendations(
            """{"candidates":[],"warnings":[null,7,{"warning":"search_products_unavailable"},"search_products_unavailable"]}""")
        assertTrue(accepted.searchProductsUnavailable)
        assertEquals("upstream_search_unavailable", accepted.emptyCatalogueDisposition)
        listOf(
            "null", "7", "{}", "\"search_products_unavailable\"",
            "[null,7,{\"warning\":\"search_products_unavailable\"}]",
            "[\"SEARCH_PRODUCTS_UNAVAILABLE\",\" search_products_unavailable\",\"search_products_unavailable_typo\"]",
        ).forEach { warnings ->
            val parsed = SwiggyMcpClient.parseRecommendations("""{"candidates":[],"warnings":$warnings}""")
            assertFalse(warnings, parsed.searchProductsUnavailable)
            assertFalse(warnings, parsed.catalogueMatchUnverified)
            assertFalse(warnings, parsed.usualProductUnavailable)
            assertEquals("no_extractable_or_unclassified", parsed.emptyCatalogueDisposition)
        }
    }

    @Test fun overlappingEmptyCatalogueWarningsArePreservedIndependently() {
        val parsed = SwiggyMcpClient.parseRecommendations(
            """{"candidates":[],"warnings":["usual_product_unavailable","catalogue_match_unverified","search_products_unavailable"]}""")
        assertTrue(parsed.usualProductUnavailable)
        assertTrue(parsed.catalogueMatchUnverified)
        assertTrue(parsed.searchProductsUnavailable)
        assertEquals("server_match_filtered_and_upstream_search_unavailable", parsed.emptyCatalogueDisposition)
        assertEquals("server_match_filtered", parsed.copy(searchProductsUnavailable = false).emptyCatalogueDisposition)
    }

    @Test fun searchWarningIsDiagnosticOnlyAndLegacyFieldsAreUnchanged() {
        val plain = SwiggyMcpClient.parseRecommendations(
            """{"query":"synthetic","requiresConfirmation":false,"candidates":[{"spinId":"synthetic-offer","label":"Synthetic","suggested":true}]}""")
        val warned = SwiggyMcpClient.parseRecommendations(
            """{"query":"synthetic","requiresConfirmation":false,"candidates":[{"spinId":"synthetic-offer","label":"Synthetic","suggested":true}],"warnings":["search_products_unavailable","arbitrary-private-text"]}""")
        assertEquals(plain, warned.copy(searchProductsUnavailable = false))
        assertEquals("not_empty", warned.emptyCatalogueDisposition)
        assertFalse(plain.searchProductsUnavailable)
        assertEquals("no_extractable_or_unclassified", SwiggyMcpClient.parseRecommendations("""{"candidates":[]}""").emptyCatalogueDisposition)
    }

    private val snapshotExisting = """[{"spinId":"bread","skuId":"bread-sku","quantity":2}]"""
    private val snapshotProposed = """[{"spinId":"bread","skuId":"bread-sku","quantity":2},{"spinId":"milk","skuId":"milk-sku","quantity":2}]"""
    private fun snapshotPlanWire(
        existing: String = snapshotExisting,
        proposed: String = snapshotProposed,
        changes: String = """[{"spinId":"milk","displayName":"Milk","fromQuantity":0,"toQuantity":2}]""",
    ): String = """{"existingItems":$existing,"proposedItems":$proposed,"changes":$changes,
        "reviewBasis":"cart-basis-v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "confirmationToken":"synthetic-plan","cartMutationEnabled":true}"""

    @Test
    fun cartPlanSnapshotRejectsHiddenChangesAndSkuSwaps() {
        val selected = listOf(RequestedItem("milk", 2, "Milk", "milk-sku"))
        val valid = SwiggyMcpClient.parseCartPlan(snapshotPlanWire())
        assertTrue(isSafeSwiggyCartPlan(valid, selected))
        val invalidProposed = listOf(
            """[{"spinId":"milk","skuId":"milk-sku","quantity":2}]""",
            snapshotProposed.replace("bread-sku", "different-bread-sku"),
            snapshotProposed.replace("milk-sku", "different-milk-sku"),
            snapshotProposed.replace("bread-sku\",\"quantity\":2", "bread-sku\",\"quantity\":1"),
            snapshotProposed.replace("bread-sku\",\"quantity\":2", "bread-sku\",\"quantity\":3"),
            snapshotProposed.replace("milk-sku\",\"quantity\":2", "milk-sku\",\"quantity\":3"),
            snapshotExisting,
            snapshotProposed.dropLast(1) + """,{"spinId":"extra","skuId":"extra-sku","quantity":1}]""",
        )
        invalidProposed.forEachIndexed { index, proposed ->
            val plan = SwiggyMcpClient.parseCartPlan(snapshotPlanWire(proposed = proposed))
            assertFalse("snapshot mutation $index", isSafeSwiggyCartPlan(plan, selected))
        }
        assertFalse("every target must be represented, not only every increment", isSafeSwiggyCartPlan(
            valid, selected + RequestedItem("sugar", 1, "Sugar", "sugar-sku"),
        ))
    }

    @Test
    fun cartPlanSnapshotRejectsChangesThatDoNotDescribeTheWholeSnapshot() {
        val selected = listOf(RequestedItem("milk", 2, "Milk", "milk-sku"))
        for (changes in listOf(
            """[{"spinId":"milk","displayName":"Milk","fromQuantity":1,"toQuantity":2}]""",
            """[{"spinId":"milk","displayName":"Milk","fromQuantity":0,"toQuantity":1}]""",
            "[]",
        )) {
            assertFalse(isSafeSwiggyCartPlan(
                SwiggyMcpClient.parseCartPlan(snapshotPlanWire(changes = changes)), selected,
            ))
        }
    }

    @Test
    fun cartPlanSnapshotRejectsMalformedOrPartialEvidenceInsteadOfLegacyFallback() {
        val valid = snapshotPlanWire()
        val malformed = listOf(
            valid.replace(snapshotExisting, "null"),
            valid.replace(snapshotProposed, "null"),
            valid.replace("\"proposedItems\":$snapshotProposed,", ""),
            valid.replace("\"reviewBasis\":\"cart-basis-v1:", "\"reviewBasis\":\"unknown:"),
            valid.replace("\"reviewBasis\":\"cart-basis-v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"", "\"reviewBasis\":false"),
            valid.replace("\"quantity\":2", "\"quantity\":\"2\""),
            valid.replace("\"quantity\":2", "\"quantity\":true"),
            valid.replace("\"quantity\":2", "\"quantity\":2.0"),
            valid.replace("\"quantity\":2", "\"quantity\":-2"),
            valid.replace("\"quantity\":2", "\"quantity\":0"),
            valid.replace("\"quantity\":2", "\"quantity\":2147483648"),
            valid.replace("\"skuId\":\"milk-sku\"", "\"skuId\":null"),
            valid.replace("\"spinId\":\"milk\"", "\"spinId\":\" \""),
            valid.replace(snapshotExisting, """[{"spinId":"bread","quantity":2},{"spinId":"bread","quantity":2}]"""),
            valid.replace("\"quantity\":2", "\"quantity\":2,\"quantity\":3"),
            valid + "{}",
        )
        malformed.forEachIndexed { index, wire ->
            assertTrue("malformed cart snapshot $index", runCatching {
                SwiggyMcpClient.parseCartPlan(wire)
            }.isFailure)
        }
    }

    @Test
    fun cartPlanSnapshotKeepsTargetsIncrementsAndUntouchedLinesDistinct() {
        val existing = """[{"spinId":"milk","skuId":"milk-sku","quantity":2},{"spinId":"bread","quantity":25}]"""
        val proposed = """[{"spinId":"milk","skuId":"milk-sku","quantity":3},{"spinId":"bread","quantity":25}]"""
        val changes = """[{"spinId":"milk","displayName":"Milk","fromQuantity":2,"toQuantity":3}]"""
        val plan = SwiggyMcpClient.parseCartPlan(snapshotPlanWire(existing, proposed, changes))
        assertTrue(isSafeSwiggyCartPlan(plan, listOf(RequestedItem("milk", 3, "Milk", "milk-sku"))))
        assertTrue(isSafeSwiggyCartPlan(plan, listOf(
            RequestedItem("milk", 1, "Milk", "milk-sku", IntentCartQuantityMode.INCREMENT),
        )))
        assertTrue("omitting the requested SKU preserves the existing SKU", isSafeSwiggyCartPlan(
            plan, listOf(RequestedItem("milk", 3, "Milk")),
        ))
        assertTrue("the server combines duplicate TARGET requests by max, not sum", isSafeSwiggyCartPlan(
            plan, listOf(RequestedItem("milk", 2, "Milk", "milk-sku"), RequestedItem("milk", 3, "Milk", "milk-sku")),
        ))
        assertFalse(isSafeSwiggyCartPlan(plan, listOf(RequestedItem("milk", 3, "Milk", "other-sku"))))
        assertFalse(isSafeSwiggyCartPlan(plan, listOf(
            RequestedItem("milk", 3, "Milk", "milk-sku", IntentCartQuantityMode.INCREMENT),
        )))
        assertFalse(isSafeSwiggyCartPlan(plan, listOf(
            RequestedItem("milk", 3, "Milk", "milk-sku"),
            RequestedItem("milk", 1, "Milk", "milk-sku", IntentCartQuantityMode.INCREMENT),
        )))
    }

    @Test
    fun cartPlanSnapshotPreservesAlreadyPresentTargetWithoutInventingAChange() {
        val plan = SwiggyMcpClient.parseCartPlan(snapshotPlanWire())
        assertTrue(isSafeSwiggyCartPlan(plan, listOf(
            RequestedItem("milk", 2, "Milk", "milk-sku"),
            RequestedItem("bread", 1, "Bread", "bread-sku"),
        )))
        assertFalse("an existing line cannot silently switch its SKU", isSafeSwiggyCartPlan(plan, listOf(
            RequestedItem("milk", 2, "Milk", "milk-sku"),
            RequestedItem("bread", 1, "Bread", "different-bread-sku"),
        )))
    }

    @Test
    fun cartPlanSnapshotLegacyCompatibilityDoesNotInventAReviewBasis() {
        val wire = snapshotPlanWire()
        val plan = SwiggyMcpClient.parseCartPlan(wire)
        val snapshot = requireNotNull(plan.snapshot)
        assertEquals("cart-basis-v1:" + "a".repeat(64), snapshot.reviewBasis)
        assertEquals("bread-sku", snapshot.existingItems.single().skuId)
        assertEquals(2, snapshot.proposedItems.size)
        assertTrue("snapshot lists cannot be changed after parsing", runCatching {
            (snapshot.existingItems as MutableList<SwiggyMcpClient.CartPlanItem>).clear()
        }.isFailure)
        assertFalse(plan.toString().contains("synthetic-plan"))
        assertFalse(snapshot.toString().contains("bread"))
        assertFalse(snapshot.proposedItems.first().toString().contains("bread"))

        val withoutBasis = wire.replace(
            """"reviewBasis":"cart-basis-v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",""", "",
        )
        val currentServerPlan = SwiggyMcpClient.parseCartPlan(withoutBasis)
        assertTrue(currentServerPlan.snapshot != null)
        assertEquals(null, currentServerPlan.snapshot?.reviewBasis)
        assertTrue(isSafeSwiggyCartPlan(currentServerPlan, listOf(RequestedItem("milk", 2, "Milk", "milk-sku"))))
        val legacyWire = withoutBasis.replace(""""proposedItems":$snapshotProposed,""", "")
        val legacy = SwiggyMcpClient.parseCartPlan(legacyWire)
        assertEquals(null, legacy.snapshot)
        assertTrue(isSafeSwiggyCartPlan(legacy, listOf(RequestedItem("milk", 2, "Milk", "milk-sku"))))
    }

    @Test
    fun cartPlanSnapshotRetainsUnambiguousLegacyChangeAliasesOnly() {
        val selected = listOf(RequestedItem("milk", 2, "Milk", "milk-sku"))
        val wire = snapshotPlanWire()
        val existingAlias = SwiggyMcpClient.parseCartPlan(wire.replace("\"existingItems\"", "\"existing_items\""))
        assertTrue(isSafeSwiggyCartPlan(existingAlias, selected))
        assertEquals(1, existingAlias.existingItemCount)
        for (alias in listOf("changes", "cartChanges", "cart_changes", "operations")) {
            val aliased = wire.replace("\"changes\"", "\"$alias\"")
                .replace("\"fromQuantity\"", "\"quantityBefore\"")
                .replace("\"toQuantity\"", "\"quantityAfter\"")
                .replace("\"spinId\":\"milk\",\"displayName\"", "\"product_id\":\"milk\",\"displayName\"")
            assertTrue(alias, isSafeSwiggyCartPlan(SwiggyMcpClient.parseCartPlan(aliased), selected))
        }
        for (ambiguous in listOf(
            wire.replace("\"existingItems\":", "\"existing_items\":[],\"existingItems\":"),
            wire.replace("\"changes\":", "\"cartChanges\":[],\"changes\":"),
            wire.replace("\"fromQuantity\":0", "\"fromQuantity\":0,\"quantityBefore\":0"),
            wire.replace("\"toQuantity\":2", "\"toQuantity\":2,\"quantityAfter\":2"),
            wire.replace("\"spinId\":\"milk\",\"displayName\"", "\"spinId\":\"milk\",\"product_id\":\"milk\",\"displayName\""),
        )) {
            assertTrue(runCatching { SwiggyMcpClient.parseCartPlan(ambiguous) }.isFailure)
        }
    }

    @Test
    fun cartPlanSnapshotRejectsMixedEnvelopesAcrossSupportedAliases() {
        val wire = snapshotPlanWire()
        val wrapped = """{"success":true,"data":$wire}"""
        assertTrue(isSafeSwiggyCartPlan(SwiggyMcpClient.parseCartPlan(wrapped),
            listOf(RequestedItem("milk", 2, "Milk", "milk-sku"))))
        for (key in listOf("changes", "cartChanges", "cart_changes", "operations",
            "confirmationToken", "confirmation_token", "token", "existingItems", "existing_items",
            "proposedItems", "reviewBasis", "cartMutationEnabled", "cart_mutation_enabled", "mutationEnabled")) {
            assertTrue("conflicting outer field $key", runCatching {
                SwiggyMcpClient.parseCartPlan("""{"$key":null,"data":$wire}""")
            }.isFailure)
        }
    }

    @Test
    fun cartPlanSnapshotUsesValidatedDirectFieldsNotNestedAliasShadows() {
        val selected = listOf(RequestedItem("milk", 2, "Milk", "milk-sku"))
        val correctDirect = """[{"product_id":"milk","quantityBefore":0,"quantityAfter":2,
            "metadata":{"spinId":"other","fromQuantity":5,"toQuantity":9}}]"""
        assertTrue(isSafeSwiggyCartPlan(
            SwiggyMcpClient.parseCartPlan(snapshotPlanWire(changes = correctDirect)), selected,
        ))
        val incorrectDirect = listOf(
            """[{"product_id":"other","quantityBefore":0,"quantityAfter":2,"metadata":{"spinId":"milk"}}]""",
            """[{"product_id":"milk","quantityBefore":1,"quantityAfter":2,"metadata":{"fromQuantity":0}}]""",
            """[{"product_id":"milk","quantityBefore":0,"quantityAfter":3,"metadata":{"toQuantity":2}}]""",
        )
        incorrectDirect.forEach { changes ->
            assertFalse(isSafeSwiggyCartPlan(
                SwiggyMcpClient.parseCartPlan(snapshotPlanWire(changes = changes)), selected,
            ))
        }
    }

    @Test
    fun cartPlanSnapshotDecodesAllRowFieldsDirectlyIncludingAction() {
        val selected = listOf(RequestedItem("milk", 2, "Milk", "milk-sku"))
        val unsafeAction = """[{"spinId":"milk","fromQuantity":0,"toQuantity":2,
            "action":"delete","metadata":{"kind":"add"}}]"""
        assertFalse(isSafeSwiggyCartPlan(
            SwiggyMcpClient.parseCartPlan(snapshotPlanWire(changes = unsafeAction)), selected,
        ))
        for (actionKey in listOf("kind", "type", "action", "operation")) {
            val changes = """[{"spinId":"milk","fromQuantity":0,"toQuantity":2,"$actionKey":"add",
                "label":"Direct label","notes":"Direct notes",
                "metadata":{"displayName":"Nested label","description":"Nested notes"}}]"""
            val plan = SwiggyMcpClient.parseCartPlan(snapshotPlanWire(changes = changes))
            assertTrue(isSafeSwiggyCartPlan(plan, selected))
            assertEquals("Direct label", plan.changes.single().displayName)
            assertEquals("Direct notes", plan.changes.single().description)
            val labelWithDescription = SwiggyMcpClient.parseCartPlan(snapshotPlanWire(
                changes = changes.replace("\"notes\":\"Direct notes\"", "\"description\":\"Direct notes\""),
            ))
            assertEquals("Direct label", labelWithDescription.changes.single().displayName)
            assertEquals("Direct notes", labelWithDescription.changes.single().description)
        }
        for (badKind in listOf("\"action\":false", "\"kind\":\"add\",\"action\":\"add\"")) {
            val changes = """[{"spinId":"milk","fromQuantity":0,"toQuantity":2,$badKind}]"""
            assertTrue(runCatching { SwiggyMcpClient.parseCartPlan(snapshotPlanWire(changes = changes)) }.isFailure)
        }
    }

    @Test
    fun cartPlanSnapshotTokenMutationAndMessageCannotComeFromNestedMetadata() {
        val wire = snapshotPlanWire()
        val nested = """"metadata":{"confirmationToken":"nested-token","cartMutationEnabled":true,"message":"Nested message"}}"""
        val aliased = wire.replace("\"confirmationToken\"", "\"confirmation_token\"")
            .replace("\"cartMutationEnabled\":true", "\"mutationEnabled\":false")
            .dropLast(1) + ",\"status_message\":\"Direct message\"," + nested
        val plan = SwiggyMcpClient.parseCartPlan(aliased)
        assertEquals("synthetic-plan", plan.confirmationToken)
        assertFalse(plan.cartMutationEnabled)
        assertEquals("Direct message", plan.message)
        val missing = wire.replace("\"confirmationToken\":\"synthetic-plan\",", "")
            .replace("\"cartMutationEnabled\":true", "\"unrelated\":true").dropLast(1) + "," + nested
        val noDirectFields = SwiggyMcpClient.parseCartPlan(missing)
        assertEquals(null, noDirectFields.confirmationToken)
        assertFalse(noDirectFields.cartMutationEnabled)
        assertEquals(null, noDirectFields.message)
        for (invalid in listOf(
            wire.replace("\"confirmationToken\":\"synthetic-plan\"", "\"confirmationToken\":42"),
            wire.replace("\"confirmationToken\":", "\"token\":\"duplicate\",\"confirmationToken\":"),
            wire.replace("\"cartMutationEnabled\":true", "\"cartMutationEnabled\":\"true\""),
            wire.replace("\"cartMutationEnabled\":true", "\"cartMutationEnabled\":1"),
            wire.replace("\"cartMutationEnabled\":true", "\"cartMutationEnabled\":true,\"mutationEnabled\":true"),
        )) {
            assertTrue(runCatching { SwiggyMcpClient.parseCartPlan(invalid) }.isFailure)
        }
    }

    @Test
    fun requestedItemJsonOmitsTargetModeAndMarksOnlyIncrements() {
        val target = SwiggyMcpClient.requestedItemPayload(RequestedItem("milk", 2, "Milk"))
        val increment = SwiggyMcpClient.requestedItemPayload(
            RequestedItem("soap", 1, "Soap", quantityMode = IntentCartQuantityMode.INCREMENT),
        )

        assertFalse(target.containsKey("quantityMode"))
        assertEquals("increment", increment["quantityMode"])
        assertEquals(1, increment["quantity"])
    }

    @Test
    fun intentPreparationCapabilitiesAreContractSpecific() {
        val v2 = SwiggyMcpClient.intentPreparationCapabilities(GroceryIntentContract.V2)
        val v1 = SwiggyMcpClient.intentPreparationCapabilities(GroceryIntentContract.V1)
        val context = SwiggyMcpClient.intentPreparationCapabilities(GroceryIntentContract.CONTEXT)
        val interpreted = SwiggyMcpClient.intentPreparationCapabilities(GroceryIntentContract.INTERPRETED)

        assertEquals(
            mapOf(
                "supports_headless_groups" to true,
                "supports_variant_cardinality" to true,
                "supports_postfix_identity" to true,
                "supports_input_repair" to true,
                "supports_property_obligations" to true,
                "supports_ingredient_predicates" to true,
                "supports_typed_ingredient_predicates" to true,
                "supports_group_corrections" to true,
                "supports_list_purpose" to true,
                "supports_purpose_groups" to true,
                "supports_named_piece_corrections" to true,
                "supports_restatement_receipts" to true,
                "supports_quantity_exclusion_receipts" to true,
                "supports_trailing_fulfillment" to true,
                "supports_same_count_refinements" to true,
                "supports_product_operations" to true,
            ),
            v2,
        )
        assertFalse(v2.containsKey("supports_source_piece_counts"))
        assertFalse(v2.containsKey("supports_generic_container_offers"))
        assertEquals(mapOf("supports_source_piece_counts" to true, "supports_generic_container_offers" to true), v1)
        assertEquals(v1, context)
        assertEquals(emptyMap<String, Boolean>(), interpreted)
        assertFalse(v1.containsKey("supports_group_corrections"))
        assertFalse(v1.containsKey("supports_ingredient_predicates"))
        assertFalse(v1.containsKey("supports_typed_ingredient_predicates"))
        assertFalse(v1.containsKey("supports_list_purpose"))
        assertFalse(v1.containsKey("supports_purpose_groups"))
        assertFalse(v1.containsKey("supports_named_piece_corrections"))
        assertFalse(v1.containsKey("supports_restatement_receipts"))
        assertFalse(v1.containsKey("supports_quantity_exclusion_receipts"))
        assertFalse(v1.containsKey("supports_trailing_fulfillment"))
        assertFalse(v1.containsKey("supports_same_count_refinements"))
        assertFalse(v1.containsKey("supports_product_operations"))
    }


    @Test
    fun usualProductUnavailableWarningIsParsedWithoutChangingLegacyResponses() {
        val missing = SwiggyMcpClient.parseRecommendations("""{"candidates":[],"suggested":null,"requiresConfirmation":true,"warnings":["usual_product_unavailable"]}""")
        assertTrue(missing.usualProductUnavailable)
        assertTrue(missing.candidates.isEmpty())
        assertFalse(SwiggyMcpClient.parseRecommendations("""{"candidates":[]}""").usualProductUnavailable)
    }

    @Test
    fun catalogueMatchUnverifiedWarningIsParsedWithoutChangingOtherRecommendationFields() {
        val parsed = SwiggyMcpClient.parseRecommendations(
            """{"requires_confirmation":true,"query":"milk","suggested":{"spinId":"s","label":"Suggested milk"},"candidates":[{"spinId":"s","label":"Suggested milk","suggested":true},{"spinId":"c","label":"Other milk"}],"warnings":[null,7,{"warning":"catalogue_match_unverified"},"usual_product_unavailable","catalogue_match_unverified"]}""",
        )

        assertTrue(parsed.catalogueMatchUnverified)
        assertTrue(parsed.usualProductUnavailable)
        assertTrue(parsed.requiresConfirmation)
        assertEquals("milk", parsed.query)
        assertEquals(listOf("s", "c"), parsed.candidates.map { it.spinId })
        assertEquals("s", parsed.suggested?.spinId)

        listOf(
            """{"candidates":[]}""",
            """{"candidates":[],"warnings":"catalogue_match_unverified"}""",
            """{"candidates":[],"warnings":[null,7,{"warning":"catalogue_match_unverified"}]}""",
            """{"candidates":[],"warnings":["catalogue_match_unverified_typo"]}""",
        ).forEach { body ->
            assertFalse(body, SwiggyMcpClient.parseRecommendations(body).catalogueMatchUnverified)
        }
    }

    @Test
    fun onlyCartApplyUsesTheNonRetryingMutationTransport() {
        assertTrue(SwiggyMcpClient.isCartMutationPath("/swiggy/cart/apply"))
        assertFalse(SwiggyMcpClient.isCartMutationPath("/swiggy/status"))
        assertFalse(SwiggyMcpClient.isCartMutationPath("/swiggy/recommendations/batch"))
        assertFalse(SwiggyMcpClient.isCartMutationPath("/swiggy/cart/plan"))
    }

    @Test
    fun cartApplyNetworkLossWarnsThatOutcomeMayBeUnknown() {
        assertTrue(
            SwiggyMcpClient.swiggyNetworkFailureMessage("/swiggy/cart/apply")
                .contains("may have changed the cart")
        )
        assertEquals(
            "Unable to reach Swiggy backend right now.",
            SwiggyMcpClient.swiggyNetworkFailureMessage("/swiggy/recommendations/batch")
        )
    }

    @Test
    fun fastApiRateLimitUsesNestedDetailReasonWithoutReconnect() {
        val body = """
            {
              "detail": {
                "reason": "swiggy_rate_limited",
                "retryAfterSeconds": 70
              }
            }
        """.trimIndent()

        assertEquals("swiggy_rate_limited", SwiggyMcpClient.swiggyHttpErrorReason(body))
        assertEquals(
            "Swiggy needs a short pause. Please wait 2 minutes, then try again.",
            SwiggyMcpClient.swiggyUserMessageForHttpCode("/swiggy/recommendations/batch", 429, body),
        )
        assertFalse(SwiggyMcpClient.swiggyReconnectRequired(body))
        assertTrue(SwiggyMcpClient.swiggyRetryable("/swiggy/recommendations/batch", 429))
        assertFalse(SwiggyMcpClient.swiggyRetryable("/swiggy/cart/apply", 429))
    }

    @Test
    fun fastApiRateLimitWaitMinutesRoundsUpFromNestedDetail() {
        assertEquals(
            2,
            SwiggyMcpClient.swiggyRateLimitWaitMinutes(
                """{"detail":{"reason":"swiggy_rate_limited","retryAfterSeconds":70}}"""
            ),
        )
        assertEquals(
            1,
            SwiggyMcpClient.swiggyRateLimitWaitMinutes(
                """{"detail":{"reason":"swiggy_rate_limited","retryAfterSeconds":23}}"""
            ),
        )
        assertEquals(
            null,
            SwiggyMcpClient.swiggyRateLimitWaitMinutes(
                """{"detail":{"reason":"swiggy_rate_limited","retryAfterSeconds":"invalid"}}"""
            ),
        )
        assertEquals(
            null,
            SwiggyMcpClient.swiggyRateLimitWaitMinutes("""{"detail":{"reason":"other"}}"""),
        )
    }

    @Test
    fun parseStatusDetectsReconnectRequiredAndAuthorizationUrl() {
        val parsed = SwiggyMcpClient.parseStatus(
            """
            {
              "success": true,
              "data": {
                "state": "reconnect_required",
                "authorization_url": "https://auth.example/swiggy",
                "message": "Please reconnect"
              }
            }
            """.trimIndent()
        )

        assertEquals(SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED, parsed.state)
        assertEquals("https://auth.example/swiggy", parsed.authorizationUrl)
        assertEquals("Please reconnect", parsed.message)
    }

    @Test
    fun parseStatusDoesNotTreatDisconnectedAsConnected() {
        val disconnected = SwiggyMcpClient.parseStatus("""{"status":"disconnected"}""")
        val notConnected = SwiggyMcpClient.parseStatus("""{"status":"not_connected"}""")
        val connected = SwiggyMcpClient.parseStatus("""{"status":"connected"}""")

        assertEquals(SwiggyMcpClient.ConnectionState.DISCONNECTED, disconnected.state)
        assertEquals(SwiggyMcpClient.ConnectionState.DISCONNECTED, notConnected.state)
        assertEquals(SwiggyMcpClient.ConnectionState.READY, connected.state)
    }

    @Test
    fun parseAddressesNormalizesLabelsFromMixedShapes() {
        val parsed = SwiggyMcpClient.parseAddresses(
            """
            {
              "success": true,
              "data": {
                "addresses": [
                  {
                    "addressId": "home-1",
                    "line1": "  12,  Main Street  ",
                    "line2": "  Apt 4B ",
                    "city": "Bengaluru",
                    "pincode": " 560001 "
                  },
                  "Work Address"
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        assertEquals("home-1", parsed[0].id)
        assertEquals("12, Main Street, Apt 4B, Bengaluru, 560001", parsed[0].normalizedLabel)
        assertEquals("Saved address 1 — Bengaluru", parsed[0].shortLabel)
        assertEquals("Saved address 1", parsed[0].categoryLabel)
        assertEquals("12 in Main Street", parsed[0].confirmationDetail)
        assertTrue(
            SwiggyMcpClient.describeAddressSchema(
                """{"success":true,"data":{"addresses":[{"addressId":"home-1","line1":"Test"}]}}"""
            ).contains("firstAddressKeys=addressId|line1")
        )
    }

    @Test
    fun parseAddressesSupportsLiveSwiggyAddressLineShape() {
        val parsed = SwiggyMcpClient.parseAddresses(
            """
            {
              "addresses": [
                {
                  "id": "saved-1",
                  "addressLine": "  10 Test Road, Bengaluru  ",
                  "phoneNumber": "redacted",
                  "addressCategory": "HOME",
                  "addressTag": "Home"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        assertEquals("saved-1", parsed.single().id)
        assertEquals("10 Test Road, Bengaluru", parsed.single().normalizedLabel)
        assertEquals("Home — Bengaluru", parsed.single().shortLabel)
        assertEquals("Home", parsed.single().categoryLabel)
        assertEquals("10 Test Road in Bengaluru", parsed.single().confirmationDetail)
    }

    @Test
    fun parseAddressesMarksTheCurrentCartAddressFromTheBackendPayload() {
        val parsed = SwiggyMcpClient.parseAddresses(
            """
            {
              "success": true,
              "data": {
                "addresses": [
                  {
                    "id": "home-1",
                    "addressLine": "12, Main Street, Bengaluru",
                    "addressCategory": "HOME"
                  },
                  {
                    "id": "work-2",
                    "addressLine": "602, 4th block, Jains prakrithi apartments, Bengaluru",
                    "addressCategory": "WORK"
                  }
                ],
                "currentCartAddressId": "work-2"
              }
            }
            """.trimIndent()
        )

        assertEquals(listOf(false, true), parsed.map { it.hasCurrentCart })
        assertEquals("work-2", parsed.last().id)
    }

    @Test
    fun shortAddressConfirmationKeepsOnlyUsefulLeadingParts() {
        assertEquals(
            "8/18 in Lynwood Avenue",
            SwiggyMcpClient.conciseAddressConfirmationDetail(
                "8/18, Lynwood Avenue, Bengaluru, Karnataka, 560047, India",
                "Home",
            ),
        )
        assertEquals(
            "602, 4th block in Jains prakrithi apartments",
            SwiggyMcpClient.conciseAddressConfirmationDetail(
                "602, 4th block, Jains prakrithi apartments, Bengaluru, Karnataka, 560076",
                "Home",
            ),
        )
    }

    @Test
    fun parseAddressesRejectsEntriesWithoutAProviderId() {
        val parsed = SwiggyMcpClient.parseAddresses(
            """{"addresses":[{"addressLine":"10 Test Road, Bengaluru","addressCategory":"HOME"}]}"""
        )

        assertTrue(parsed.isEmpty())
    }

    @Test
    fun parseRecommendationsChoosesSuggestedCandidateAndConfirmationFlag() {
        val parsed = SwiggyMcpClient.parseRecommendations(
            """
            {
              "success": true,
              "data": {
                "requires_confirmation": true,
                "candidates": [
                  {"spinId": "a", "skuId": "sku-a", "label": "Fresh Milk", "variant": "1L"},
                  {"spinId": "b", "title": "Toned Milk", "suggested": true}
                ]
              }
            }
            """.trimIndent()
        )

        assertTrue(parsed.requiresConfirmation)
        assertEquals(2, parsed.candidates.size)
        assertEquals("a", parsed.candidates[0].spinId)
        assertEquals("1L", parsed.candidates[0].variant)
        assertEquals("sku-a", parsed.candidates[0].skuId)
        assertEquals("b", parsed.suggested?.spinId)
        assertEquals("Toned Milk", parsed.suggested?.label)
    }

    @Test
    fun parseCartPlanAndApplyResultReadsMutationAndVerificationFlags() {
        val plan = SwiggyMcpClient.parseCartPlan(
            """
            {
              "success": true,
              "data": {
                "cart_mutation_enabled": true,
                "confirmation_token": "confirm-123",
                "existingItems": [
                  {"spinId": "existing-1", "quantity": 1},
                  {"spinId": "existing-2", "quantity": 2},
                  {"spinId": "existing-3", "quantity": 1}
                ],
                "changes": [
                  {
                    "spinId": "pencil-spin",
                    "type": "add",
                    "displayName": "Pencil",
                    "fromQuantity": 0,
                    "toQuantity": 2
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val apply = SwiggyMcpClient.parseApplyResult(
            """
            {
              "verified": true,
              "message": "Applied",
              "reconnectRequired": false
            }
            """.trimIndent()
        )

        assertTrue(plan.cartMutationEnabled)
        assertEquals("confirm-123", plan.confirmationToken)
        assertEquals(3, plan.existingItemCount)
        assertEquals(1, plan.changes.size)
        assertEquals("pencil-spin", plan.changes[0].spinId)
        assertEquals("add", plan.changes[0].kind)
        assertEquals("Pencil", plan.changes[0].displayName)
        assertEquals(0, plan.changes[0].fromQuantity)
        assertEquals(2, plan.changes[0].toQuantity)
        assertTrue(apply.verified)
        assertFalse(apply.reconnectRequired)
        assertEquals("Applied", apply.message)
    }

    @Test
    fun parseRecommendationBatchKeepsInputOrderAndQueryCorrelation() {
        val results = SwiggyMcpClient.parseRecommendationBatch(
            """
            {
              "results": [
                {"query":"milk", "candidates":[{"spinId":"m","label":"Milk"}]},
                {"query":"bread", "candidates":[{"spinId":"b","label":"Bread"}]}
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("milk", "bread"), results.map { it.query })
        assertEquals(listOf("m", "b"), results.map { it.candidates.single().spinId })
    }

    @Test
    fun sellerPropertyBatchReceiptMustEchoExactAlignedRequirements() {
        val requirement = sellerRequirement("milk", "low-fat milk", "low-fat")
        val otherRequirement = sellerRequirement("oats", "organic oats", "organic")
        val expected = listOf(requirement, otherRequirement, null)
        val requirementJson = sellerRequirementJson(requirement)
        val otherRequirementJson = sellerRequirementJson(otherRequirement)
        val correct = """{"sellerPropertyVersion":2,"sellerAssessmentPolicy":"seller-assessment-v3","results":[{"sellerProperties":$requirementJson,"candidates":[]},{"sellerProperties":$otherRequirementJson,"candidates":[]},{"sellerProperties":null,"candidates":[]}]}"""
        assertEquals(3, SwiggyMcpClient.parseRecommendationBatch(correct,
            expectedSellerProperties = expected).size)
        for (bad in listOf(
            correct.replace("\"sellerPropertyVersion\":2,", ""),
            correct.replace("seller-assessment-v3", "seller-assessment-v2"),
            correct.replace("\"r0:7\"", "\"r1:8\""),
            correct.replace("\"sellerProperties\":null", "\"sellerProperties\":$requirementJson"),
            correct.replace("\"sellerProperties\":null,", ""),
            correct.replace("\"sellerProperties\":null", "\"sellerProperties\":null,\"sellerProperties\":null"),
            """{"sellerPropertyVersion":2,"sellerAssessmentPolicy":"seller-assessment-v3","results":[{"sellerProperties":$otherRequirementJson,"candidates":[]},{"sellerProperties":$requirementJson,"candidates":[]},{"sellerProperties":null,"candidates":[]}]}""",
        )) {
            assertThrows(RuntimeException::class.java) {
                SwiggyMcpClient.parseRecommendationBatch(bad, expectedSellerProperties = expected)
            }
        }
    }

    @Test
    fun sellerAssessmentMustBindRawCandidateAndExactPropertyCoverage() {
        val requirement = sellerRequirement("milk", "low-fat milk", "low-fat")
        val requirementJson = sellerRequirementJson(requirement)
        val propertyIdsJson = jsonStrings(requirement.properties)
        val validAssessment = sellerAssessment(requirementJson, propertyIdsJson)
        val parsed = SwiggyMcpClient.parseRecommendationBatch(sellerAssessmentBatch(validAssessment),
            expectedSellerProperties = listOf(requirement)).single()
        val candidate = parsed.candidates.single()
        val proof = requireNotNull(candidate.sellerAssessment)
        assertTrue(proof.matches(requirement, candidate))
        assertFalse(proof.matches(requirement, candidate.copy(label = "Other milk")))
        assertFalse(proof.matches(requirement, candidate.copy(spinId = "other-spin")))
        assertFalse(proof.matches(requirement, candidate.copy(skuId = "other-sku")))
        assertFalse(proof.matches(requirement, candidate.copy(variant = "1 L")))
        assertFalse(proof.matches(requirement, candidate.copy(subtitle = "changed")))
        assertFalse(proof.matches(requirement, candidate.copy(catalogueIngredientsInvalid = true)))
        val otherRequirement = sellerRequirement("milk", "organic milk", "organic")
        assertFalse(proof.matches(otherRequirement, candidate))

        val ingredientEvidence = """{"version":1,"source":"search_variation","spinId":"spin-1","skuId":"sku-1","fields":{"title":"Low Fat Milk"}}"""
        val ingredientAssessed = SwiggyMcpClient.parseRecommendationBatch(sellerAssessmentBatch(
            sellerAssessment(requirementJson, propertyIdsJson, ingredientEvidence),
            ingredientEvidence), expectedSellerProperties = listOf(requirement)).single().candidates.single()
        val ingredientProof = requireNotNull(ingredientAssessed.sellerAssessment)
        assertTrue(ingredientProof.matches(requirement, ingredientAssessed))
        val changedEvidence = ingredientEvidence.replace("Low Fat Milk", "Milk")
        val changedAssessed = SwiggyMcpClient.parseRecommendationBatch(sellerAssessmentBatch(
            sellerAssessment(requirementJson, propertyIdsJson, changedEvidence),
            changedEvidence), expectedSellerProperties = listOf(requirement)).single().candidates.single()
        assertFalse(ingredientProof.matches(requirement, changedAssessed))
        assertThrows(RuntimeException::class.java) {
            SwiggyMcpClient.parseRecommendationBatch(sellerAssessmentBatch(
                sellerAssessment(requirementJson, propertyIdsJson, "{}"),
                ingredientEvidence), expectedSellerProperties = listOf(requirement))
        }

        val badProofs = listOf(
            validAssessment.replace("\"snapshotFingerprint\":\"${"a".repeat(64)}\"", "\"snapshotFingerprint\":\"ABC\""),
            validAssessment.replace("\"spinId\":\"spin-1\"", "\"spinId\":\"other\""),
            validAssessment.replace("\"subject\":\"milk\"", "\"subject\":\"oats\""),
            sellerAssessment(requirementJson, """["r1:8"]"""),
            validAssessment.replace(propertyIdsJson, jsonStrings(requirement.properties + requirement.properties)),
            validAssessment.replace(sellerAssessmentIngredients, "null"),
            validAssessment.replace("\"policy\":\"seller-assessment-v3\"", "\"policy\":\"seller-assessment-v3\",\"policy\":\"seller-assessment-v3\""),
        )
        badProofs.forEach { assessment ->
            assertThrows(RuntimeException::class.java) {
                SwiggyMcpClient.parseRecommendationBatch(sellerAssessmentBatch(assessment),
                    expectedSellerProperties = listOf(requirement))
            }
        }
    }

    @Test
    fun sellerAssessmentContractFailsClosedWhenPolicyOrAnyCandidateProofIsMissing() {
        val requirement = sellerRequirement("milk", "low-fat milk", "low-fat")
        val requirementJson = sellerRequirementJson(requirement)
        val valid = sellerAssessmentBatch(sellerAssessment(requirementJson, jsonStrings(requirement.properties)))
        for (bad in listOf(
            valid + "{}",
            valid.replace("Low Fat Milk", "Low Fat \\qMilk"),
            valid.replace("\"sellerAssessmentPolicy\":\"seller-assessment-v3\",", ""),
            valid.replace("\"sellerAssessment\":", "\"untrustedAssessment\":"),
            valid.replace("\"label\":\"Low Fat Milk\"", "\"label\":\"Low Fat Milk\",\"label\":\"Low Fat Milk\""),
            valid.replace("\"spinId\":\"spin-1\"", "\"spinId\":1"),
            valid.replace("\"candidates\":[{", "\"suggested\":{\"spinId\":\"spin-1\",\"skuId\":\"sku-1\",\"label\":\"Low Fat Milk\"},\"candidates\":[{"),
        )) {
            assertThrows(RuntimeException::class.java) {
                SwiggyMcpClient.parseRecommendationBatch(bad, expectedSellerProperties = listOf(requirement))
            }
        }
    }

    @Test
    fun paidAssessmentAndIntentRequestsCannotAutomaticallyRetry() {
        listOf("/swiggy/recommendations/batch", "/swiggy/intent", "/swiggy/cart/apply",
            "/swiggy/checkout/place", "/swiggy/checkout/status").forEach {
            assertFalse(SwiggyMcpClient.permitsConnectionRetry(it))
        }
        assertTrue(SwiggyMcpClient.permitsConnectionRetry("/swiggy/addresses"))
    }

    private val sellerAssessmentIngredients = """{"version":1,"source":"search_variation","spinId":"spin-1","skuId":"sku-1","fields":{"title":"Low Fat Milk"}}"""

    @Test
    fun literalProcessingRequiresSameVariationEvenAfterPositiveSellerAssessment() {
        for ((word, opposite) in listOf("unsalted" to "salted", "unsweetened" to "sweetened",
            "unflavoured" to "flavoured")) {
            val noun = "butter"
            val source = "$word $noun"
            val requirement = sellerRequirement(noun, source, word)
            val req = sellerRequirementJson(requirement)
            val basket = AuditedIntentBasket(source, "test", 1, "{}", emptyList())
            val item = AuditedIntentProvenance.create(basket, 0,
                ParsedItem(source, noun, strictMatchPhrase = noun, requiresIntentQuantityProof = true), "unstated",
                null, requireNotNull(SourceMerchandise.compile(source, GroceryIntentReviewV2Quote(source, 0, source.length),
                    source.length, noun, "unstated", null, null, null, GroceryIntentInterpretedAmounts(source),
                    listOf(requireNotNull(SourceMerchandiseProperty.fromSource(requirement.source, requirement.requirements.single(),
                        "seller", emptySet(), requirement))))), null, null, emptyList(), requirement, null).project()
            fun offered(name: String, variant: String = "100 g", subtitle: String? = null,
                        display: String = name, brand: String? = null): SwiggyMcpClient.RecommendationCandidate {
                val ingredients = sellerAssessmentIngredients.replace("""{"title":"Low Fat Milk"}""",
                    """{"title":"$name","variantName":"$variant"}""")
                val receipt = sellerAssessment(req, jsonStrings(requirement.properties), ingredients)
                val batch = sellerAssessmentBatch(receipt, ingredients, req)
                    .replace("seller-assessment-v3", "seller-assessment-v4-literal")
                    .replace("\"candidates\":[{", "\"candidates\":[{" + (brand?.let {
                        """"catalogueBrand":{"name":"$it","spinId":"spin-1","skuId":"sku-1","source":"search_variation"},"""
                    } ?: ""))
                    .replace("\"label\":\"Low Fat Milk\"", "\"label\":\"$display\"")
                    .replace("\"variant\":null", "\"variant\":\"$variant\"")
                    .replace("\"subtitle\":null", "\"subtitle\":" + (subtitle?.let { "\"$it\"" } ?: "null"))
                return SwiggyMcpClient.parseRecommendationBatch(batch,
                    expectedSellerProperties = listOf(requirement)).single().candidates.single()
            }
            val exact = offered(source)
            for ((candidate, shouldPass) in listOf(
                exact to true, offered(noun, word) to true,
                offered("Nila $source", brand = "Nila") to true, offered("Other $source", brand = "Nila") to false,
                offered("not $source", brand = "not") to false,
                offered(noun, subtitle = word) to false, offered(noun, display = source) to false,
                offered("not $source") to false, offered("made with $source") to false,
                offered("$source storage dish") to false, offered("$source alternative") to false,
                offered("$source preferred") to false, offered("$source or $opposite $noun") to false,
                offered(source, subtitle = "$opposite $noun") to false,
                offered(source, display = "$opposite $noun") to false,
                offered(source, variant = opposite) to false, exact.copy(skuId = "wrong") to false,
            )) {
                assertEquals(candidate.label, shouldPass, isSwiggyCandidateAllowed(item, candidate))
                val checked = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(candidate)))
                assertEquals(candidate.label, shouldPass, SwiggyCartReviewRequest.build(
                    listOf(checked), mapOf(0 to candidate), emptySet(), basket) != null)
            }
        }
    }

    @Test
    fun aBoundMakerIsNotTheProductAndCannotContradictTheRequestedMaker() {
        fun matches(source: String, subject: String, title: String, variant: String, maker: String?): Boolean {
            val requirement = sellerRequirement(subject, source, "unsalted")
            val evidence = requireNotNull(CatalogueIngredientEvidence.parse(JsonParser(sellerAssessmentIngredients
                .replace("""{"title":"Low Fat Milk"}""", """{"title":"$title","variantName":"$variant"}""")).parseStrict(),
                "spin-1", "sku-1", emptySet()))
            val candidate = SwiggyMcpClient.RecommendationCandidate(spinId = "spin-1", skuId = "sku-1", label = title, variant = variant,
                catalogueIngredients = evidence, catalogueBrand = maker?.let {
                    SwiggyMcpClient.CatalogueBrandEvidence(it, "spin-1", "sku-1", "search_variation")
                })
            return evidence.supportsProcessingLabels(requirement, candidate)
        }
        assertFalse(matches("unsalted Amul", "Amul", "Amul", "Unsalted", "Amul"))
        assertFalse(matches("unsalted Amul butter", "Amul butter", "Unsalted Amul Butter", "100 g", "Britannia"))
        assertFalse(matches("unsalted Zarella butter", "Zarella butter", "Unsalted Zarella Butter", "100 g", null))
        assertTrue(matches("unsalted butter", "butter", "Unsalted Butter", "100 g", null))
        assertTrue(matches("unsalted Amul butter", "Amul butter", "Unsalted Amul Butter", "100 g", "Amul"))
        assertTrue(matches("2 packs of Nila unsalted table butter, 100 g each", "Nila Table Butter",
            "Nila Unsalted Table Butter", "100 g", "Nila"))
        assertTrue(matches("unsalted butter", "butter", "Unsalted Butter", "1 pack · 100 g", null))
        for (bad in listOf("0 g", "2 packs · 100 g", "1 packs · 100 g", "1 pack · 0 g",
            "1 pack · 2 pcs", "1 pack · 100 g · 1 pack", "100 g · 1 pack", "1 pack · 100 g approx")) {
            assertFalse(bad, matches("unsalted butter", "butter", "Unsalted Butter", bad, null))
        }
    }

    @Test
    fun mixedBatchRejectsOldHiddenEvidencePolicyEvenWhenTheLiteralRowHasNoOffers() {
        val protected = sellerRequirement("butter", "unsalted butter", "unsalted")
        val ordinary = sellerRequirement("milk", "low-fat milk", "low-fat")
        val p = sellerRequirementJson(protected)
        val o = sellerRequirementJson(ordinary)
        val safeReceipt = sellerAssessment(o, jsonStrings(ordinary.properties))
        val ordinaryRow = sellerAssessmentBatch(safeReceipt, requirement = o).substringAfter("\"results\":[").dropLast(2)
        val current = """{"sellerPropertyVersion":2,"sellerAssessmentPolicy":"seller-assessment-v4-literal","results":[{"sellerProperties":$p,"candidates":[]},$ordinaryRow]}"""
        val expected = listOf(protected, ordinary)
        assertEquals(1, SwiggyMcpClient.parseRecommendationBatch(current, expectedSellerProperties = expected)[1].candidates.size)
        val legacyLiteral = sellerAssessmentBatch(sellerAssessment(p, jsonStrings(protected.properties)), requirement = p)
        val literalRow = legacyLiteral.substringAfter("\"results\":[").dropLast(2)
            .replace("Low Fat Milk", "Unsalted Butter").replace("seller-assessment-v3", "seller-assessment-v4-literal")
            .replace("spin-1", "spin-2").replace("sku-1", "sku-2")
        val mixed = current.replace("""{"sellerProperties":$p,"candidates":[]}""", literalRow)
        SwiggyMcpClient.parseRecommendationBatch(mixed, expectedSellerProperties = expected).forEachIndexed { i, row ->
            val selected = row.candidates.single()
            assertTrue(selected.sellerAssessment!!.matches(expected[i], selected))
        }
        for ((body, rows) in listOf(
            current.replace("seller-assessment-v4-literal", "seller-assessment-v3") to expected,
            current.replace("\"policy\":\"seller-assessment-v3\"", "\"policy\":\"seller-assessment-v4-literal\"") to expected,
            legacyLiteral to listOf(protected),
            legacyLiteral.replace("\"sellerAssessmentPolicy\":\"seller-assessment-v3\"", "\"sellerAssessmentPolicy\":\"seller-assessment-v4-literal\"") to listOf(protected),
        )) assertThrows(RuntimeException::class.java) {
            SwiggyMcpClient.parseRecommendationBatch(body, expectedSellerProperties = rows)
        }
        val lostWord = sellerRequirement("butter", "unsalted organic butter", "organic")
        assertEquals("seller-assessment-v4-literal", SwiggySellerAssessment.policyFor(lostWord))
    }

    private fun sellerAssessment(requirement: String, properties: String, catalogueIngredients: String = sellerAssessmentIngredients): String =
        """{"policy":"seller-assessment-v3","snapshotFingerprint":"${"a".repeat(64)}","requirement":$requirement,"candidate":{"spinId":"spin-1","skuId":"sku-1","label":"Low Fat Milk","variant":null,"subtitle":null,"catalogueIngredients":$catalogueIngredients},"properties":$properties}"""

    private fun sellerAssessmentBatch(
        assessment: String,
        catalogueIngredients: String = sellerAssessmentIngredients,
        requirement: String = sellerRequirementJson(sellerRequirement("milk", "low-fat milk", "low-fat")),
    ): String =
        """{"sellerPropertyVersion":2,"sellerAssessmentPolicy":"seller-assessment-v3","results":[{"sellerProperties":$requirement,"candidates":[{"spinId":"spin-1","skuId":"sku-1","label":"Low Fat Milk","variant":null,"subtitle":null,"catalogueIngredients":$catalogueIngredients,"sellerAssessment":$assessment}]}]}"""

    private fun sellerRequirement(subject: String, primaryText: String, literal: String): SellerPropertyRequirement {
        val start = primaryText.indexOf(literal).also { require(it >= 0 && primaryText.indexOf(literal, it + 1) < 0) }
        return requireNotNull(SellerPropertyRequirement.fromSource(
            subject,
            GroceryIntentReviewV2Quote(primaryText, 0, primaryText.length),
            listOf(GroceryIntentReviewV2Quote(literal, start, start + literal.length)),
        ))
    }

    private fun sellerRequirementJson(requirement: SellerPropertyRequirement): String {
        val requirements = requirement.requirements.mapIndexed { index, quote ->
            "{\"id\":${quoteJson(requirement.properties[index])},\"source\":${quoteObject(quote)}}"
        }.joinToString(",")
        return """{"version":2,"subject":${quoteJson(requirement.subject)},"source":${quoteObject(requirement.source)},"requirements":[$requirements]}"""
    }

    private fun jsonStrings(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]") { quoteJson(it) }

    private fun quoteObject(quote: GroceryIntentReviewV2Quote): String =
        "{\"text\":${quoteJson(quote.text)},\"start_utf16\":${quote.startUtf16},\"end_utf16\":${quote.endUtf16}}"

    private fun quoteJson(value: String): String = "\"" + value
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    @Test
    fun malformedBatchCandidatesNeverBecomeAutomaticChoices() {
        val result = SwiggyMcpClient.parseRecommendationBatch(
            """
            {
              "results": [
                {
                  "query":"milk",
                  "candidates":["invented", {"label":"Missing id"}, {"spinId":"missing-label"}]
                }
              ]
            }
            """.trimIndent()
        ).single()

        assertTrue(result.candidates.isEmpty())
        assertEquals(null, result.suggested)
        assertTrue(result.requiresConfirmation)
    }

    @Test
    fun parseCapabilitiesRecognizesReadOnlyAndMutatingTools() {
        val parsed = SwiggyMcpClient.parseCapabilities(
            """
            {
              "success": true,
              "data": {
                "readOnlyTools": ["status", "addresses"],
                "mutatingTools": ["connect", "cartPlan"]
              }
            }
            """.trimIndent()
        )

        assertEquals(listOf("status", "addresses"), parsed.supported)
    }

    @Test
    fun recentAddressIdsFollowSwiggyOrderRecencyWithoutReadingAddressText() {
        val parsed = SwiggyMcpClient.parseRecentAddressIds(
            """
            {
              "orders": [
                {"orderId":"one", "deliveryAddress":{"id":"recent-home", "addressLine":"Private"}},
                {"orderId":"two", "deliveryAddress":{"id":"older-work"}},
                {"orderId":"three", "deliveryAddress":{"id":"recent-home"}}
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("recent-home", "older-work"), parsed)
    }
}
