package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import com.example.beta.automation.IntentCartQuantityMode
import org.junit.Assert.*
import org.junit.Test

class SwiggyPurchaseLimitTest {
    private val rice = ParsedItem("1 kg rice", "rice", Quantity.Weight(1000))
    private fun cap(max: String, spin: String = "spin", sku: String = "sku", source: String = "live_search_variation") =
        """{"source":"$source","spinId":"$spin","skuId":"$sku","maxUnits":$max}"""
    private fun wire(extra: String = "", spin: String = "spin", sku: String = "sku", size: String = "500 g") =
        """{"spinId":"$spin","skuId":"$sku","label":"Rice $size","variant":"$size","catalogueQuantity":{"version":1,"source":"search_variation","spinId":"$spin","skuId":"$sku","fields":{"packSize":"$size"}}$extra}"""
    private fun limit(value: String) = ""","cataloguePurchaseLimit":$value"""
    private fun batch(vararg rows: String, preferred: String? = null): Recommendations {
        val top = preferred?.let { ""","suggested":$it""" }.orEmpty()
        return SwiggyMcpClient.parseRecommendationBatch(
            """{"results":[{"query":"rice","candidates":[${rows.joinToString(",")}]$top}]}""").single()
    }
    private fun parsed(extra: String = "", size: String = "500 g") = batch(wire(extra, size = size)).candidates.single()
    private fun assessed(item: ParsedItem, candidate: RecommendationCandidate) =
        assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
    private fun built(item: ParsedItem, candidate: RecommendationCandidate) =
        SwiggyCartReviewRequest.build(listOf(assessed(item,candidate)), mapOf(0 to candidate), emptySet())
    private fun assertHeld(item: ParsedItem, candidate: RecommendationCandidate) {
        val checked = assessed(item,candidate)
        assertTrue(resolveSwiggyCartQuantity(checked,candidate) is SwiggyCartQuantityResolution.Held)
        assertTrue(swiggyUsableCandidates(checked, Recommendations(listOf(candidate))).isEmpty())
        assertNull(built(item,candidate))
    }

    @Test fun exactCartUnitsAreNeverReducedAndOptionalCapCannotCreateAnExactQuantity() {
        val uncapped = parsed()
        assertNull(uncapped.cataloguePurchaseLimit)
        assertFalse(uncapped.cataloguePurchaseLimitInvalid)
        for (offer in listOf(uncapped, parsed(limit(cap("2"))), parsed(limit(cap("999"))))) {
            val checked = assessed(rice,offer)
            assertEquals(SwiggyCartQuantityResolution.Exact(2),resolveSwiggyCartQuantity(checked,offer))
            val request = requireNotNull(built(rice,offer))
            assertEquals(2,request.requestedItems.single().quantity)
            assertTrue(request.quantityProposals.isEmpty())
        }
        assertEquals(2,parsed(limit(cap("2"))).cataloguePurchaseLimit?.maxUnits)
        assertHeld(rice,parsed(limit(cap("1"))))
        val notDivisible = parsed(size = "350 g")
        val original = resolveSwiggyCartQuantity(assessed(rice,notDivisible),notDivisible)
        assertTrue(original is SwiggyCartQuantityResolution.Held)
        val withLimit = parsed(limit(cap("999")),size = "350 g")
        assertEquals(original,resolveSwiggyCartQuantity(assessed(rice,withLimit),withLimit))
    }

    @Test fun malformedOrNestedBindingsDoNotBecomeUnrestrictedOffers() {
        val valid = cap("2")
        val shapes = listOf("null","[]","true","2","{}") +
            listOf("\"2\"","true","null","0","-1","1000","2.0","2e0").map { cap(it) } +
            listOf(cap("2","not-spin"), cap("2",sku="not-sku"), cap("2",source="history"),
                valid.replace("\"maxUnits\":2","\"maxUnits\":1,\"maxUnits\":2"),
                valid.replace("\"skuId\":\"sku\"","\"nested\":{\"skuId\":\"sku\"}"))
        val duplicateTop = wire(limit(valid) + limit(valid))
        val duplicateIdentity = wire(limit(valid)).replaceFirst("\"skuId\":\"sku\"", "\"skuId\":\"unsafe\",\"skuId\":\"sku\"")
        val nestedOnlyIdentity = wire(limit(valid)).replaceFirst("\"skuId\":\"sku\",", "")
        val nestedSpinOnlyIdentity = wire(limit(valid)).replaceFirst("\"spinId\":\"spin\",","")
        for ((index,json) in (shapes.map { wire(limit(it)) } + listOf(duplicateTop, duplicateIdentity, nestedOnlyIdentity, nestedSpinOnlyIdentity)).withIndex()) {
            // Dropping an unusable candidate is safe; retaining it is safe only if unusable.
            val decoded = runCatching { batch(json) }
            if (decoded.isFailure) {
                assertTrue(decoded.exceptionOrNull() is IllegalArgumentException)
                continue
            }
            decoded.getOrThrow().candidates.forEach { candidate ->
                assertTrue("invalid cap retained as absent at $index", candidate.cataloguePurchaseLimitInvalid)
                assertNull(candidate.cataloguePurchaseLimit)
                assertHeld(rice,candidate)
            }
        }
        val received = parsed(limit(valid))
        val independent = batch(wire(spin="next-spin", sku="next-sku")).candidates.single()
        // The ordinary quantity facts of the new candidate still bind correctly. Only the copied cap is stale.
        assertNotNull(built(rice,independent))
        assertHeld(rice,independent.copy(cataloguePurchaseLimit=received.cataloguePurchaseLimit))
        val unownedHint = parsed(""","metadata":{"cataloguePurchaseLimit":${cap("1")}}""")
        assertNull(unownedHint.cataloguePurchaseLimit)
        assertNotNull(built(rice,unownedHint))
        for (stale in listOf(received.copy(spinId="other"),received.copy(skuId="other"),
            received.copy(cataloguePurchaseLimit=SwiggyMcpClient.CataloguePurchaseLimit("spin","sku",0)),
            received.copy(cataloguePurchaseLimit=SwiggyMcpClient.CataloguePurchaseLimit("spin","sku",1000)),
            received.copy(cataloguePurchaseLimitInvalid=true))) assertHeld(rice,stale)
    }

    @Test fun preferredCappedVariationCannotDisplaceAnEligibleAlternative() {
        val preferred = wire(limit(cap("1","first","sku-a")),spin="first",sku="sku-a")
        val other = wire(limit(cap("2","second","sku-b")),spin="second",sku="sku-b")
        val recommendations = batch(preferred,other,preferred=preferred)
        val item = assessSwiggyIntentQuantity(rice,recommendations)
        val eligible = swiggyUsableCandidates(item,recommendations)
        assertEquals(listOf("sku-b"),eligible.map { it.skuId })
        val pick = swiggyDefaultSuggestion(item,eligible,recommendations.suggested)
        val request = requireNotNull(built(rice,pick)).requestedItems.single()
        assertEquals("sku-b",request.skuId)
        assertEquals(2,request.quantity)
    }

    @Test fun genericRetailProposalStillRequiresConsentAndCannotEscapeTheSameSkuLimit() {
        val item = prepareSwiggyMcpItems("rice 2 x 500 g packs 3") { null }.single()
        val original = parsed(size="2 x 500 g")
        val equal = parsed(limit(cap("3")),size="2 x 500 g")
        for (offer in listOf(original,equal)) {
            val result = resolveSwiggyCartQuantity(assessed(item,offer),offer)
            assertTrue(result is SwiggyCartQuantityResolution.RetailProposal)
            assertEquals(3,(result as SwiggyCartQuantityResolution.RetailProposal).cartUnits)
            assertEquals(3,requireNotNull(built(item,offer)).quantityProposals.single().quantity)
        }
        val capped = parsed(limit(cap("2")),size="2 x 500 g")
        assertHeld(item,capped)
        assertNull(swiggyQuantityProposalBinding(0,assessed(item,capped),capped))
    }

    @Test fun finalConsolidatedRequestCannotExceedTheTightestKnownSameSkuLimit() {
        val half = rice.copy(rawText="500 g rice",quantity=Quantity.Weight(500))
        val bound = parsed(limit(cap("2")))
        val unbounded = parsed()
        fun aggregate(items: List<ParsedItem>,offers: List<RecommendationCandidate>) =
            SwiggyCartReviewRequest.build(items.mapIndexed { i,item -> assessed(item,offers[i]) },
                offers.indices.associateWith { offers[it] },emptySet())
        assertEquals(2,requireNotNull(aggregate(listOf(half,half),listOf(bound,unbounded))).requestedItems.single().quantity)
        // Every individual request is legal. The aggregate would require three packs from that one SKU.
        for (other in listOf(unbounded,parsed(limit(cap("4"))))) {
            assertNull(aggregate(listOf(half,rice), listOf(bound,other)))
            assertNull(aggregate(listOf(rice,half), listOf(other,bound)))
        }
        val separate = batch(wire(limit(cap("1","other","other-sku")),spin="other",sku="other-sku")).candidates.single()
        assertEquals(2,requireNotNull(aggregate(listOf(rice,half),listOf(bound,separate))).requestedItems.size)
    }

    @Test fun vetoObservedOnlyOnSuggestedCopyAlsoBindsListAndViceVersa() {
        val none = wire()
        val differentSku = wire(spin="spin",sku="other-sku")
        for ((flag, malformed) in listOf(cap("1") to false, cap("\"2\"") to true)) {
            val restricted = wire(limit(flag))
            for ((listed,suggested) in listOf(none to restricted, restricted to none)) {
                val decoded = batch(listed,differentSku,preferred=suggested)
                val copies = listOf(decoded.candidates.first(),requireNotNull(decoded.suggested))
                copies.forEach { copy ->
                    assertEquals(malformed,copy.cataloguePurchaseLimitInvalid)
                    if (!malformed) assertEquals(1,copy.cataloguePurchaseLimit?.maxUnits)
                    assertHeld(rice,copy)
                }
                assertNotNull("different SKU under same product stays eligible", built(rice,decoded.candidates.last()))
            }
        }
    }

    @Test fun duplicateObservationAcrossQueriesUsesMinimumAndInvalidityIsStickyInEitherOrder() {
        fun row(candidates: String, suggested: String? = null): String {
            val preferred = suggested?.let { ""","suggested":$it""" }.orEmpty()
            return """{"query":"rice","candidates":[$candidates]$preferred}"""
        }
        val loose = wire(limit(cap("4")))
        val different = wire(spin="spin",sku="other-sku")
        for ((capWire,malformed) in listOf(cap("1") to false, cap("null") to true)) {
            val onlySuggested = row(different,wire(limit(capWire)))
            val candidateRow = row(loose)
            for (order in listOf(listOf(onlySuggested,candidateRow),listOf(candidateRow,onlySuggested))) {
                val rows = SwiggyMcpClient.parseRecommendationBatch("""{"results":[${order.joinToString(",")}]}""")
                val copies = rows.flatMap { it.candidates + listOfNotNull(it.suggested) }
                val targeted = copies.filter { it.skuId == "sku" }
                assertEquals(2,targeted.size)
                targeted.forEach {
                    assertEquals(malformed,it.cataloguePurchaseLimitInvalid)
                    if (!malformed) assertEquals(1,it.cataloguePurchaseLimit?.maxUnits)
                    assertHeld(rice,it)
                }
                assertNotNull(built(rice,copies.single { it.skuId == "other-sku" }))
            }
        }
    }

    @Test fun serverPlanCapIsCheckedOnChangedTargetNotOnlyTheIncrementAndNeverSentOnWire() {
        fun line(spin: String, sku: String, n: Int) = """{"spinId":"$spin","skuId":"$sku","quantity":$n}"""
        fun change(spin: String, from: Int, to: Int) = """{"spinId":"$spin","displayName":"item","fromQuantity":$from,"toQuantity":$to}"""
        fun plan(existing: String, proposed: String, changed: String) = SwiggyMcpClient.parseCartPlan(
            """{"existingItems":[$existing],"proposedItems":[$proposed],"changes":[$changed],"confirmationToken":"synthetic-plan","cartMutationEnabled":true}""")
        val request = requireNotNull(built(rice,parsed(limit(cap("3"))))).requestedItems.single()
        val add = request.copy(quantityMode=IntentCartQuantityMode.INCREMENT)
        val priorTwo = line("spin","sku",2)
        val four = line("spin","sku",4)
        val increment = plan(priorTwo,four,change("spin",2,4))
        assertTrue("control: arithmetic and snapshots valid",isSafeSwiggyCartPlan(increment,listOf(add.copy(cataloguePurchaseLimit=null))))
        assertFalse("increment 2 is legal in isolation; proposed absolute 4 exceeds 3",isSafeSwiggyCartPlan(increment,listOf(add)))
        val priorOne = line("spin","sku",1)
        assertTrue(isSafeSwiggyCartPlan(plan(priorOne,line("spin","sku",3),change("spin",1,3)),listOf(add)))
        val other = SwiggyMcpClient.RequestedItem("other",1,"Other","other-sku")
        val unchangedAboveCap = plan(four,four+","+line("other","other-sku",1),change("other",0,1))
        assertTrue("do not remove/block untouched existing stock above today's cap",
            isSafeSwiggyCartPlan(unchangedAboveCap,listOf(request,other)))
        for (tagged in listOf(request,add)) {
            val payload = SwiggyMcpClient.requestedItemPayload(tagged)
            assertEquals(SwiggyMcpClient.requestedItemPayload(tagged.copy(cataloguePurchaseLimit=null)),payload)
            assertEquals(setOf("spinId","skuId","quantity","displayName") +
                if (tagged.quantityMode == IntentCartQuantityMode.INCREMENT) setOf("quantityMode") else emptySet(),payload.keys)
        }
    }
}
