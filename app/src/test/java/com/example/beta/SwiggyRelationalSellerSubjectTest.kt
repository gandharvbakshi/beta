package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import org.junit.Assert.*
import org.junit.Test

/** Actual final request, not merely retrieval overlap or token-level helper. */
class SwiggyRelationalSellerSubjectTest {
    private fun item(q:String)=ParsedItem(q,q,Quantity.Default,strictMatchPhrase=q)
    private fun offer(title:String,subtitle:String?=null)=RecommendationCandidate("s",title,subtitle=subtitle,skuId="v")
    private fun built(p:ParsedItem, c:RecommendationCandidate):Boolean {
        val row=Recommendations(listOf(c)); val checked=assessSwiggyIntentQuantity(p,row)
        val selected=swiggyUsableCandidates(checked,row)
        val request=SwiggyCartReviewRequest.build(listOf(checked),mapOf(0 to c),emptySet())
        assertEquals(selected.contains(c),request!=null)
        return request!=null
    }
    @Test fun contentsOrIngredientCannotBecomeThePurchasedSubjectOnAnyFinalRow(){
        for((q,title) in listOf("ground coffee" to "Cappuccino mix with ground coffee",
            "besan" to "Chilla mix made with besan","dry penne" to "Ready-made pasta salad with dry penne",
            "chicory" to "Coffee with chicory","hazelnuts" to "Cookies containing hazelnuts",
            "ground coffee" to "Ground Coffee Flavoured Candy",
            "ground coffee" to "Ground Coffee Flavoured Candy with caramel",
            "dry penne" to "Ready-Made Dry Penne Salad","rolled oats" to "Instant Rolled Oats Porridge",
            "gram flour" to "Snack mix made from gram flour")) {
            assertFalse("Ingredient-only final offer for $q",built(item(q),offer(title)))
        }
        assertFalse(built(item("ground coffee"),offer("Cappuccino Mix","Made with ground coffee")))
        assertFalse(built(item("besan"),offer("Besan","Chilla mix with besan")))
        assertFalse(built(item("ground coffee"),offer("Chilla Mix","Serving suggestion: serve with ground coffee")))
    }
    @Test fun actualProductsModifiersAndSellerUsageRemainDistinctFromACompetingSubject(){
        for((q,title) in listOf("ground coffee" to "Ground Coffee with Chicory",
            "coffee with chicory" to "Coffee with Chicory","chilla mix with besan" to "Chilla Mix with Besan",
            "strawberry yogurt" to "Yogurt with Strawberry","bread with seeds" to "Bread with Seeds",
            "cappuccino mix" to "Cappuccino mix with ground coffee","poha" to "Poha",
            "plain soup" to "Prepared plain soup","dry penne" to "Dry Penne Pasta")) {
            assertTrue("Real explicit subject held for $q",built(item(q),offer(title)))
        }
        for(note in listOf("Serve with chutney","Serving suggestion: chilla mix with besan",
            "Recipe: prepare breakfast with besan","Try making chilla with besan")){
            assertTrue(built(item("besan"),offer("Besan",note)))
        }
    }
    @Test fun namedRetailBindingDoesNotTurnIngredientIntoCartMerchandise(){
        val p=prepareSwiggyMcpItems("two jars of ground coffee"){null}.single()
        fun named(title:String)=RecommendationCandidate("s",title,skuId="v",catalogueQuantity=CatalogueQuantityEvidence(
            1,"search_variation","s","v",CatalogueQuantityFields(variantName="1 jar")))
        assertTrue(built(p,named("Ground Coffee Jar")))
        assertFalse(built(p,named("Cappuccino Mix Jar with Ground Coffee")))
    }
}
