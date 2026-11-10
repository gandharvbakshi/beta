package com.example.beta

import com.example.beta.SwiggyMcpClient.CatalogueBrandEvidence
import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import org.junit.Assert.*
import org.junit.Test

internal fun sellerTitleFixture(spin: String, sku: String, title: String, variant: String? = null, extraTitle: String? = null, detail: String? = null): CatalogueIngredientEvidence {
    val fields = mapOf("title" to title, "variantName" to variant, "name" to extraTitle, "subTitle" to detail).mapNotNull { (k,v) -> v?.let { k to JsonValue.Str(it) } }.toMap()
    return requireNotNull(CatalogueIngredientEvidence.parse(JsonValue.Obj(mapOf("version" to JsonValue.Num("1"),
        "source" to JsonValue.Str("search_variation"), "spinId" to JsonValue.Str(spin), "skuId" to JsonValue.Str(sku),
        "fields" to JsonValue.Obj(fields))), spin, sku, emptySet()))
}

class SwiggySourceMerchandiseSellerTest {
    private fun preparation(name: String = "green apples", count: String? = "2", container: String? = null): GroceryIntentPreparation {
        val source = listOfNotNull(count, container?.let { "${it}s of" }, name).joinToString(" ")
        val quantity = if (count == null) "null" else "2"
        val unit = if (count == null) "unspecified" else "count"
        val role = if (count == null) "unstated" else if (container == null) "requested_pieces" else "retail_units"
        val form = container?.let { "\"$it\"" } ?: "null"
        val countText = count?.let { "\"$it\"" } ?: "null"
        val atom = count?.let { """{"text":"$it","start_utf16":0,"end_utf16":${it.length}}""" } ?: "null"
        val claim = """"name":"$name","quantity":$quantity,"quantity_unit":"$unit","pack_value":null,"pack_unit":"unspecified","required":[],"excluded":[],"source_text":"$source","needs_clarification":false"""
        val quote = """{"text":"$source","start_utf16":0,"end_utf16":${source.length}}"""
        val body = """{"contract_version":"grocery-intent-interpreted-v2","requires_review":true,"instruction":"$source","source_item_count":1,
            "claims":[{$claim}],"requirements":[[]],"rows":[{"row_index":0,"item":{$claim,"packaging":null,"supporting_text":[],"quantity_text":$countText,"pack_text":null},
            "evidence":{"primary":$quote,"supporting":[]},"quantity_atom":$atom,"pack_atom":null,"unassigned_numeric":[],"requirements":[]}],
            "blocked_rows":[],"context":[],"audit":{"version":"value-audit-v3","request_fingerprint":"${"a".repeat(64)}",
            "rows":[{"row_index":0,"issues":[],"quantity_role":"$role","package_size_role":"unstated","packaging":$form,"requirements":[]}],
            "source":[{"source":$quote,"role":"item","row_indices":[0]}]}}"""
        return GroceryIntentInterpretedReview.parse(body, source).prepareForReview()
    }
    private fun offer(title: String, maker: String? = null, variant: String = "1 piece", size: String = "1 piece") = RecommendationCandidate(
        spinId="spin", skuId="sku", label="$title · $variant", variant=variant,
        catalogueQuantity=CatalogueQuantityEvidence(1,"search_variation","spin","sku",CatalogueQuantityFields(variantName=variant,quantityDescription=size)),
        catalogueIngredients=sellerTitleFixture("spin","sku",title,variant),
        catalogueBrand=maker?.let { CatalogueBrandEvidence(it,"spin","sku","search_variation") })

    @Test fun completeSellerMerchandiseIsRequiredAtDefaultAndDirectReview() {
        val prep = preparation()
        val sourceItem = prep.items.single()
        val branded = offer("Orchard Green Apples", "Orchard")
        val secondaryOnly = CatalogueIngredientEvidence.parse(JsonParser(
            """{"version":1,"source":"search_variation","spinId":"spin","skuId":"sku","fields":{"variantName":"Green Apples"}}"""
        ).parseStrict(), "spin", "sku", emptySet())
        val bad = listOf(offer("Green Apples Printed Lunch Box"), offer("Green Apples Scented Soap"), offer("Apples"),
            offer("Orchard Green Apples"), offer("Green Apples", maker="Green"), offer("Green Apples", maker="Green Apples"),
            offer("Apples Printed Lunch Box", variant="Green"), offer("Apples", variant="Red"), offer("Green", variant="Apples"),
            offer("Green Apples", variant="Scented Soap"), offer("Green Apples", variant="1 tablet"),
            branded.copy(catalogueBrand=branded.catalogueBrand!!.copy(skuId="other")),
            branded.copy(label="Green Apples Printed Lunch Box · 1 piece"),
            branded.copy(catalogueIngredients=sellerTitleFixture("spin","other","Orchard Green Apples")),
            branded.copy(catalogueIngredients=sellerTitleFixture("spin","sku","Orchard Green Apples","1 piece","Green Apples Wipes")),
            branded.copy(subtitle="Green Apples Printed Lunch Boxes"), branded.copy(subtitle="Storage: keep refrigerated; scented soap"),
            branded.copy(catalogueIngredients=sellerTitleFixture("spin","sku","Orchard Green Apples",detail="Scented Soap")),
            branded.copy(catalogueQuantity=branded.catalogueQuantity!!.copy(fields=CatalogueQuantityFields(
                variantName="1 piece",quantityDescription="Green Apples Printed Lunch Boxes"))),
            branded.copy(catalogueIngredients=requireNotNull(secondaryOnly)), branded.copy(catalogueIngredients=null))
        for (offer in bad + listOf(branded, branded.copy(subtitle="Storage: keep refrigerated"), branded.copy(subtitle="Brand: Orchard"),
            offer("Green Apples"), offer("Apples", variant="Green"))) {
            val expected = offer !in bad
            val recommendations=Recommendations(listOf(offer),suggested=offer)
            val item=assessSwiggyIntentQuantity(sourceItem,recommendations)
            val usable=swiggyUsableCandidates(item,recommendations)
            assertEquals("default ${offer.label}", expected, usable.isNotEmpty())
            if (expected) assertEquals(offer,swiggyDefaultSuggestion(item,usable,recommendations.suggested))
            val request=SwiggyCartReviewRequest.build(listOf(item),mapOf(0 to offer),emptySet(),prep.auditedBasket)
            assertEquals("direct final ${offer.label}", expected, request != null)
            if (expected) assertEquals(2, request!!.requestedItems.single().quantity)
        }
    }

    @Test fun sellerFormCannotSilentlyBecomeTheShoppersRequestedForm() {
        for ((prep, offer, expected) in listOf(
            Triple(preparation("dark chocolate",null),offer("Dark Chocolate",variant="1 pack",size="1 pack"),1),
            Triple(preparation("dark chocolate","two","bar"),offer("Dark Chocolate",variant="1 bar · 100 g",size="100 g"),2),
            Triple(preparation("dark chocolate","two","bar"),offer("Dark Chocolate",variant="1 bar",size="1 bar"),null),
            Triple(preparation("green apples",null),offer("Green Apples",variant="1 bar",size="1 bar"),null),
            Triple(preparation("dark chocolate",null),offer("Dark Chocolate",variant="1 bar",size="1 bar"),null),
        )) {
            val rec = Recommendations(listOf(offer),suggested=offer)
            val item = assessSwiggyIntentQuantity(prep.items.single(),rec)
            assertEquals(offer.label,expected != null,swiggyUsableCandidates(item,rec).isNotEmpty())
            val review = SwiggyCartReviewRequest.build(listOf(item),mapOf(0 to offer),emptySet(),prep.auditedBasket)
            assertEquals(offer.label,expected,review?.requestedItems?.single()?.quantity)
        }
    }
}
