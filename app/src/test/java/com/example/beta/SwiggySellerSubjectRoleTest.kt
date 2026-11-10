package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.ParsedItem
import org.junit.Assert.*
import org.junit.Test

/** The number and type of vessels can be correct even though the SKU sells no requested contents. */
class SwiggySellerSubjectRoleTest {
    private data class Goods(val source: String,val subject: String,val form: String)
    private val oil = Goods("2 bottles of mustard oil","Mustard Oil","bottle")
    private val milk = Goods("2 cartons of oat milk","Oat Milk","carton")
    private fun offer(g: Goods,title: String,subtitle: String?=null,sellerSubtitle: String?=null,id: String="variant") =
        RecommendationCandidate(id,title,subtitle=subtitle,skuId="$id-sku",catalogueQuantity=CatalogueQuantityEvidence(
            1,"search_variation",id,"$id-sku",CatalogueQuantityFields(variantName="1 ${g.form}",subTitle=sellerSubtitle)))
    private fun parsed(g: Goods) = prepareSwiggyMcpItems(g.source) { null }.single()
    private fun checked(item: ParsedItem,offer: RecommendationCandidate) =
        assessSwiggyIntentQuantity(item,Recommendations(listOf(offer)))
    private fun shouldBuild(g: Goods,offer: RecommendationCandidate) {
        val item=checked(parsed(g),offer)
        assertEquals(listOf(offer),swiggyUsableCandidates(item,Recommendations(listOf(offer))))
        assertEquals(SwiggyCartQuantityResolution.Exact(2),resolveSwiggyCartQuantity(item,offer))
        val request=requireNotNull(SwiggyCartReviewRequest.build(listOf(item),mapOf(0 to offer),emptySet()))
        assertTrue(request.quantityProposals.isEmpty())
        assertEquals(offer.skuId,request.requestedItems.single().skuId)
        assertEquals(2,request.requestedItems.single().quantity)
    }

    @Test fun filledGoodsMustRejectTheVesselAndItsAccessoriesAtEveryActionBoundary() {
        val escaped = mutableListOf<String>()
        for (g in listOf(oil,milk)) {
            val trueOffer=offer(g,"${g.subject} 1 ${g.form}",id="filled")
            shouldBuild(g,trueOffer)
            val examples=listOf(
                offer(g,"Empty ${g.form} for ${g.subject}"),
                offer(g,"Refillable ${g.form} for ${g.subject}"),
                offer(g,"${g.subject} ${g.form} holder"),
                offer(g,"${g.subject} ${g.form}","Empty ${g.form} for ${g.subject}"),
                offer(g,"${g.subject} ${g.form}",sellerSubtitle="Empty ${g.form} for ${g.subject}"),
            )
            for ((i,wrong) in examples.withIndex()) {
                val both=Recommendations(listOf(wrong,trueOffer),suggested=wrong)
                val item=assessSwiggyIntentQuantity(parsed(g),both)
                val eligible=swiggyUsableCandidates(item,both)
                if (isSwiggyCandidateAllowed(item,wrong)) escaped += "${g.form}/$i identity"
                if (eligible != listOf(trueOffer)) escaped += "${g.form}/$i discovery"
                if (resolveSwiggyCartQuantity(item,wrong) !is SwiggyCartQuantityResolution.Held) escaped += "${g.form}/$i quantity"
                if (swiggyQuantityProposalBinding(0,item,wrong)!=null) escaped += "${g.form}/$i proposal"
                if (SwiggyCartReviewRequest.build(listOf(item),mapOf(0 to wrong),emptySet())!=null)
                    escaped += "${g.form}/$i final request"
            }
        }
        assertTrue("Wrong seller subject escaped: $escaped",escaped.isEmpty())
    }

    @Test fun handlingDirectionsAndExplicitVesselRequestsAreNotEmptyContainerFalsePositives() {
        for (g in listOf(oil,milk)) {
            val instructions=offer(g,"${g.subject} 1 ${g.form}",subtitle="Empty the contents into a bowl before use.")
            assertFalse("Handling prose does not establish merchandise role",sellerVesselConflicts(parsed(g),instructions))
        }
        for (attribute in listOf("empty","refillable")) {
            // This customer requested the vessel itself. A separate contents/order proof
            // might be required; verify specifically that identity isn't rejected.
            val item=ParsedItem("one $attribute bottle","$attribute bottle",strictMatchPhrase="$attribute bottle")
            assertTrue("The vessel itself remains a legitimate product",isSwiggyCandidateAllowed(item,
                RecommendationCandidate("vessel","$attribute bottle",skuId="vessel-sku")))
        }
        val pantry=ParsedItem("rice","rice")
        assertTrue(isSwiggyCandidateAllowed(pantry,RecommendationCandidate("food","Rice",skuId="food-sku",
            subtitle="Empty the contents into a bowl before use.")))
    }

    @Test fun explicitVesselDoesNotAuthorizeItsAccessoryOrAnUnownedParenthetical() {
        val bottle=RecommendationCandidate("b","Empty glass bottle",skuId="b-sku")
        val holder=RecommendationCandidate("h","Empty bottle holder",skuId="h-sku")
        fun wanted(noun: String)=ParsedItem("one $noun",noun,strictMatchPhrase=noun)
        assertFalse(sellerVesselConflicts(wanted("empty bottle"),bottle))
        assertTrue("Purchasing the vessel isn't purchasing its holder",sellerVesselConflicts(wanted("empty bottle"),holder))
        assertFalse(sellerVesselConflicts(wanted("empty bottle holder"),holder))
        val mixed="2 bottles of mustard oil (empty bottle)"
        for (item in listOf(parsed(oil).copy(rawText=mixed),wanted("empty bottle").copy(rawText=mixed))) {
            assertTrue("An aside cannot turn the whole original food request into a merchandise request",sellerVesselConflicts(item,bottle))
        }
    }

    @Test fun duplicateWireObservationsCannotEraseEvidenceOfVesselMerchandise() {
        fun candidate(label: String,sku: String)="""{"spinId":"oil","skuId":"$sku","label":"$label","catalogueQuantity":{"version":1,"source":"search_variation","spinId":"oil","skuId":"$sku","fields":{"variantName":"1 bottle"}}}"""
        fun row(primary: String,suggested: String?=null): String {
            val extra=suggested?.let { ""","suggested":$it""" }.orEmpty()
            return """{"query":"mustard oil","candidates":[$primary]$extra}"""
        }
        val named=candidate("Mustard oil 1 bottle","same")
        val empty=candidate("Empty bottle for mustard oil","same")
        val filled=candidate("Mustard oil 1 bottle","separate")
        val withVeto=row(filled,empty)
        for (rows in listOf(listOf(row(named),withVeto),listOf(withVeto,row(named)))) {
            val parsedRows=SwiggyMcpClient.parseRecommendationBatch("""{"results":[${rows.joinToString(",")}]}""")
            val copies=parsedRows.flatMap { it.candidates+listOfNotNull(it.suggested) }
            val observed=copies.filter { it.skuId=="same" }
            assertEquals(2,observed.size)
            for (c in observed) {
                val item=checked(parsed(oil),c)
                assertTrue(sellerVesselConflicts(item,c))
                assertTrue(swiggyUsableCandidates(item,Recommendations(listOf(c))).isEmpty())
                assertNull(SwiggyCartReviewRequest.build(listOf(item),mapOf(0 to c),emptySet()))
            }
            shouldBuild(oil,copies.single { it.skuId=="separate" })
        }
    }

    @Test fun contentsRequestsDoNotBecomeSupportHardwareAndExplicitHardwareKeepsItsOwnRole() {
        val cases = listOf(
            "sandalwood soap bar" to "Sandalwood Soap Bar Holder",
            "sandalwood soap bars" to "Sandalwood Soap Bar Holder",
            "paper kitchen towel roll" to "Paper Kitchen Towel Roll Stand",
            "paper kitchen towel rolls" to "Paper Kitchen Towel Roll Stand",
            "steel dish scrubber" to "Steel Dish Scrubber Holder",
            "steel dish scrubbers" to "Steel Dish Scrubber Holder",
        )
        for ((request, title) in cases) {
            val item = ParsedItem(request, request, strictMatchPhrase = request)
            val hardware = RecommendationCandidate("hardware", title, skuId = "hardware-sku")
            assertTrue("Contents request '$request' must reject '$title'",
                sellerVesselConflicts(item, hardware))

            val clean = RecommendationCandidate("same", request, skuId = "same-sku")
            val duplicateHardware = RecommendationCandidate("same", title, skuId = "same-sku")
            for (observations in listOf(listOf(clean, duplicateHardware), listOf(duplicateHardware, clean))) {
                val offers = observations.joinToString(",") { c ->
                    """{"spinId":"same","skuId":"same-sku","label":"${c.label}"}"""
                }
                val recommendations = SwiggyMcpClient.parseRecommendationBatch(
                    """{"results":[{"query":"$request","candidates":[$offers]}]}""").single()
                assertTrue("Conflicting same-SKU hardware evidence must stay sticky for '$request'",
                    swiggyUsableCandidates(item, recommendations).isEmpty())
            }

            val negativeMention = ParsedItem("$request, not the holder or stand", request,
                strictMatchPhrase = request)
            assertTrue("Negative mention must not erase support-hardware identity for '$request'",
                sellerVesselConflicts(negativeMention, hardware))
        }

        val soapHolder = RecommendationCandidate("soap-holder", "Sandalwood Soap Bar Holder", skuId = "soap-holder")
        assertFalse("An explicit soap-bar-holder request keeps its hardware identity",
            sellerVesselConflicts(ParsedItem("soap bar holder", "soap bar holder",
                strictMatchPhrase = "soap bar holder"), soapHolder))
        val towelStand = RecommendationCandidate("towel-stand", "Paper Kitchen Towel Roll Stand", skuId = "towel-stand")
        assertFalse("An explicit stand-for-towel request keeps its hardware identity",
            sellerVesselConflicts(ParsedItem("stand for paper kitchen towel roll", "stand for paper kitchen towel roll",
                strictMatchPhrase = "stand for paper kitchen towel roll"), towelStand))
        val explicitStandRequest = ParsedItem("stand for paper kitchen towel roll",
            "stand for paper kitchen towel roll", strictMatchPhrase = "stand for paper kitchen towel roll")
        val standOffer = RecommendationCandidate("stand", "Paper Kitchen Towel Roll Stand · 1 stand", variant = "1 stand",
            skuId = "stand-sku")
        assertTrue("Explicit stand-for wording admits the matching title and variant",
            isSwiggyCandidateAllowed(explicitStandRequest, standOffer))
        val holderOffer = RecommendationCandidate("holder", "Paper Kitchen Towel Roll Holder · 1 holder", variant = "1 holder",
            skuId = "holder-sku")
        assertFalse("The explicit stand request does not admit a holder",
            isSwiggyCandidateAllowed(explicitStandRequest, holderOffer))

        val soapWithHolder = RecommendationCandidate("soap", "Soap Bar with Holder", skuId = "soap-with-holder")
        assertFalse("'with Holder' does not alone reclassify the soap product as a holder",
            sellerVesselConflicts(ParsedItem("soap bar", "soap bar", strictMatchPhrase = "soap bar"), soapWithHolder))
    }
}
