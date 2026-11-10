package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.automation.ParsedItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyProductIdentityTest {
    @Test fun aWholeMeasuredTitleMayTranslateItsAttestedNounButNotItsModifierMakerOrSellingRole() {
        fun admits(request: String, title: String, maker: String = "Field Grove", size: String = "250 g",
                   brandSku: String = "current"): Boolean {
            val wanted = "$request 250 g"
            val item = ParsedItem(wanted, wanted, strictMatchPhrase = wanted)
            val variant = "1 pack · $size"
            val offer = RecommendationCandidate(spinId = "spin", skuId = "current", label = "$title · $variant",
                variant = variant, catalogueBrand = SwiggyMcpClient.CatalogueBrandEvidence(maker, "spin", brandSku, "search_variation"),
                catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", "spin", "current",
                    CatalogueQuantityFields(variantName = variant)))
            return isSwiggyCandidateAllowed(item, offer)
        }
        for ((request, seller) in listOf("plain dahi" to "Plain Curd",
            "fresh palak" to "Fresh Spinach", "gud" to "Jaggery")) {
            assertTrue(request, admits(request, "Field Grove $seller"))
        }
        for (title in listOf("Field Grove Flavoured Curd", "Field Grove Plain Sweet Curd",
            "Field Grove Plain Curd Powder", "Field Grove Recipe Book for Plain Curd",
            "Field Grove Crisps Made With Plain Curd", "Field Grove Plain Curd Storage Jar",
            "Field Grove Plain Dahi Curd")) assertFalse(title, admits("plain dahi", title))
        assertFalse(admits("plain dahi", "Field Grove Plain Curd", maker = "Other Maker"))
        assertFalse(admits("plain dahi", "Field Grove Plain Curd", brandSku = "sibling"))
        assertFalse(admits("other maker plain dahi", "Field Grove Plain Curd"))
        assertFalse(admits("plain dahi", "Field Grove Plain Curd", size = "500 g"))
        assertFalse(admits("fresh palak", "Field Grove Dried Spinach"))
    }

    @Test fun aMeasuredGrainIsNotFlourOrEquipmentMentioningTheGrain() {
        for (grain in listOf("quinoa", "rice", "oats")) {
            val request = "$grain 250 g"
            val item = ParsedItem(request, request, strictMatchPhrase = request)
            for (title in listOf("$grain flour", "$grain storage dish", "storage dish for $grain")) {
                assertFalse(title, swiggyMatchesProductIdentity(item, "$title · 250 g", listOf(title, "250 g")))
            }
            assertTrue(swiggyMatchesProductIdentity(item, "$grain · 250 g", listOf(grain, "250 g")))
        }
        val explicit = ParsedItem("quinoa seeds 250 g", "quinoa seeds 250 g", strictMatchPhrase = "quinoa seeds 250 g")
        assertFalse(swiggyMatchesProductIdentity(explicit, "Quinoa Seeds Storage Dish · 250 g",
            listOf("Quinoa Seeds Storage Dish", "250 g")))
        assertTrue(swiggyMatchesProductIdentity(explicit, "Quinoa Seeds · 250 g", listOf("Quinoa Seeds", "250 g")))
    }
    @Test fun onlyCompleteAttestedProductNounsCanCompareAcrossNumber() {
        for ((plural, singular) in listOf("dark chocolates" to "Dark Chocolate",
            "salted crackers" to "Salted Cracker", "brown lentils" to "Brown Lentil")) {
            val requested = ParsedItem(plural, plural, strictMatchPhrase = plural)
            assertTrue(plural, swiggyMatchesProductIdentity(requested, "$singular · 100 g", listOf(singular, "100 g")))
        }
        val dark = ParsedItem("two dark chocolates", "dark chocolates", strictMatchPhrase = "dark chocolates")
        for (wrong in listOf("Milk Chocolate", "Dark Chocolate Milk", "Dark Chocolate · Biscuit",
            "Dark Chocolaty", "Chocolate Dark", "Dark Chocolate without cocoa")) {
            assertFalse(wrong, swiggyMatchesProductIdentity(dark, wrong, listOf(wrong)))
        }
        assertFalse(swiggyMatchesProductIdentity(dark, "Dark Chocolate · Milk Powder", listOf("Dark Chocolate", "Milk Powder")))
        val unapproved = ParsedItem("two glasses", "glasses", strictMatchPhrase = "glasses")
        assertFalse(swiggyMatchesProductIdentity(unapproved, "Glass", listOf("Glass")))
    }
    @Test fun pluralNounCannotAuthorizeMultipleRetailMultipacksOrDropDiet() {
        fun review(source: String, display: String, contents: String? = null): Int? {
            val requested = prepareSwiggyMcpItems(source) { null }.single()
            val offer = RecommendationCandidate("offer", "Dark Chocolate · $display", skuId = "sku", variant = display,
                catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", "offer", "sku",
                    CatalogueQuantityFields(variantName = display, quantityDescription = contents)))
            val recommendations = SwiggyMcpClient.Recommendations(listOf(offer))
            val checked = assessSwiggyIntentQuantity(requested, recommendations)
            if (offer !in swiggyUsableCandidates(checked, recommendations)) return null
            return SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to offer), emptySet())
                ?.requestedItems?.single()?.quantity
        }
        assertEquals(2, review("two dark chocolates", "100 g"))
        assertNull(review("two dark chocolates", "6 pcs"))
        assertNull(review("two dark chocolates", "100 g", "6 pcs"))
        assertTrue(review("two dark chocolates", "2 pieces") in listOf(null, 1))
        assertNull(review("two sugar free dark chocolates", "100 g"))
        val strict = ParsedItem("two dark chocolates", "dark chocolates", strictMatchPhrase = "dark chocolates")
        assertFalse(swiggyMatchesProductIdentity(strict, "Dark Chocolate · 6 pcs", listOf("Dark Chocolate", "6 pcs")))
    }
    @Test fun namedCompoundsDoNotBecomeTheirInternalGenericHeadOrSecondaryIdentity() {
        listOf("Custard Apple", "Sugar Apples", "Wood Apple", "Ice Apple", "CUSTARD-APPLES", "Wood\u2011Apple",
            "Ｃｕｓｔａｒｄ　Ａｐｐｌｅ").forEach { title ->
            assertBlocked("apple", title)
            assertBlocked("apple", title, strictMatchPhrase = "apple")
            assertBlocked("seb", title)
            assertBlocked("sebu", title)
        }
        listOf("Sweet Potato", "Sweet-Potatoes", "SWEET\u2014POTATOES").forEach { title ->
            assertBlocked("potato", title)
            assertBlocked("aloo", title)
        }
        listOf("Gala Apple", "Shimla Apples", "Red Apple", "Golden Royal Gala Apple (Sebu)", "Fresh Potato").forEach { title ->
            assertAllowed(if ("Potato" in title) "potato" else "apple", title)
        }
        for (compound in listOf("custard apple", "sugar apple", "wood apple", "ice apple", "sweet potato")) assertAllowed(compound, compound)
        assertBlocked("custard apple", "Sugar Apple") // a shared denial class is not an alias
        assertBlocked("wood apple", "Custard Apple")
        assertBlocked("apple", "Custard Apple", strictMatchPhrase = "custard apple")
        assertBlocked("custard apple", "Apple", strictMatchPhrase = "apple")
        assertBlocked("custard apple", "Sugar Apple", strictMatchPhrase = "sugar apple")
        assertFalse(isSwiggyCandidateAllowed(item("apple"), candidate("Wood Apple").copy(subtitle = "Gala Apple")))
        assertFalse(isSwiggyCandidateAllowed(item("wood apple"), candidate("Apple").copy(subtitle = "Wood Apple")))
        assertFalse(isSwiggyCandidateAllowed(item("custard apple"), candidate("Sugar Apple").copy(subtitle = "Custard Apple")))
        assertFalse(isSwiggyCandidateAllowed(item("apple"), candidate("Gala Apple").copy(variant = "Custard Apple")))
        assertFalse(swiggyNamedCompoundIdentityAllowed(listOf("wood apple"), "Fruit", listOf("Wood Apple")))
        assertTrue(swiggyNamedCompoundIdentityAllowed(listOf("apple", "apple without custard apple"), "Gala Apple"))
        assertTrue(swiggyNamedCompoundIdentityAllowed(listOf("apple", "apple, not wood apple"), "Gala Apple"))
        assertFalse(swiggyNamedCompoundIdentityAllowed(listOf("apple"), "not custard apple"))
        assertTrue(swiggyNamedCompoundIdentityAllowed(listOf("apple"), "Apple without custard apple"))
        assertTrue(swiggyNamedCompoundIdentityAllowed(listOf("apple"), "Wood without sugar Apple"))
        assertTrue(swiggyNamedCompoundIdentityAllowed(listOf("apple"), "Custard no added sugar Apple"))
        assertAllowed("apple", "No Added Sugar Apple")
        assertAllowed("apple", "Wood without sugar Apple")
        assertBlocked("apple", "Pineapple")
        assertBlocked("red apple", "Red Wood Apple")
        assertTrue(isSwiggyCandidateAllowed(item("Apple USB charger"), candidate("USB Charger").copy(subtitle = "Apple")))
        assertTrue(isSwiggyCandidateAllowed(item("apple juice"), candidate("Juice").copy(subtitle = "Apple")))
        assertFalse(isSwiggyCandidateAllowed(item("Apple USB charger"), candidate("USB Charger").copy(subtitle = "Wood Apple")))
        assertFalse(swiggyNamedCompoundIdentityAllowed(listOf("seb"), "Fruit", listOf("Apple")))
        listOf("Custard\nApple", "Custard\u0085Apple", "Custard--Apple", "Apple (custard)", "Apple - Custard", "cus\u200dtard Apple")
            .forEach { assertBlocked("apple", it) }
        assertFalse(isSwiggyCandidateAllowed(item("apple"), candidate("Apple").copy(variant = "Custard")))
        assertTrue(isSwiggyCandidateAllowed(item("apple"), candidate("Apple").copy(variant = "Gala")))
        assertTrue(swiggyNamedCompoundIdentityAllowed(listOf("apple"), "Apple No Added", listOf("Sugar")))
        assertTrue(swiggyNamedCompoundIdentityAllowed(listOf("apple"), "Apple (sugar free)"))
        listOf("rice" to "Brown Rice", "orange" to "Blood Orange", "tea" to "Green Tea", "soap" to "Soap Bar")
            .forEach { (request, title) -> assertAllowed(request, title) }
    }

    @Test fun wrongNamedCompoundCannotBuildARealV1FinalRequestDespiteBoundOnePiece() {
        val source = "one apple"
        val prepared = GroceryIntentDraft("grocery-intent-v1", true, source, listOf(GroceryIntentItem(
            "apple", java.math.BigDecimal.ONE, "count", null, "unspecified", emptyList(), emptyList(), source, false)), false)
            .prepareForReview().items.single()
        fun offer(title: String) = RecommendationCandidate("compound-spin", title, skuId = "compound-sku",
            catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", "compound-spin", "compound-sku",
                CatalogueQuantityFields(quantityDescription = "1 piece")))
        for (title in listOf("Custard Apple", "Sugar Apple", "Wood Apple", "Custard\nApple", "Apple (custard)",
                "Apple - Custard", "cus\u200dtard Apple")) {
            val chosen = offer(title)
            val checked = assessSwiggyIntentQuantity(prepared, SwiggyMcpClient.Recommendations(listOf(chosen)))
            assertFalse(isSwiggyCandidateAllowed(checked, chosen))
            assertNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to chosen), emptySet()))
        }
        val ice = offer("Ice Apple (Taati Ningu) · 200 g").copy(variant = "200 g", catalogueQuantity =
            CatalogueQuantityEvidence(1, "search_variation", "compound-spin", "compound-sku",
                CatalogueQuantityFields(quantityDescription = "200 g")))
        val iceChecked = assessSwiggyIntentQuantity(prepared, SwiggyMcpClient.Recommendations(listOf(ice)))
        assertFalse(isSwiggyCandidateAllowed(iceChecked, ice))
        assertNull(SwiggyCartReviewRequest.build(listOf(iceChecked), mapOf(0 to ice), emptySet()))
        assertAllowed("ice apple", "Ice Apple (Taati Ningu)")
        val gala = offer("Gala Apple")
        val checked = assessSwiggyIntentQuantity(prepared, SwiggyMcpClient.Recommendations(listOf(gala)))
        val request = SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to gala), emptySet())
        assertNotNull(request)
        assertEquals(1, request!!.requestedItems.single().quantity)
        assertEquals(gala.skuId, request.requestedItems.single().skuId)
    }

    @Test fun catalogueIdentityDoesNotSubstituteAWordWithDifferentVowelMarks() {
        assertAllowed("क़लम", "क़लम")
        assertBlocked("café", "cafe")
        assertBlocked("\u093f", "\u093f")
        assertBlocked("क्\u200dष", "क् ष")
        for ((literal, other) in listOf("तिल" to "तेल", "बेल" to "बैल")) {
            assertAllowed(literal, literal)
            assertBlocked(literal, other)
            assertBlocked(literal, other, strictMatchPhrase = literal)
        }
    }
    @Test fun organic_property_identity_requires_an_unqualified_positive_claim_in_both_match_fields() {
        val products = listOf("ginger", "rice", "tea")
        for (product in products) {
            val requested = "organic $product"
            val title = product.replaceFirstChar { it.uppercase() }
            val acceptedLabels = listOf(
                "Organic $title",
                "Certified Organic $title",
                "Organic $title without preservatives",
                "Non-GMO Organic $title",
                "100% Organic $title",
                "100.0% Organic $title",
                "100.00% Organic $title",
            )
            acceptedLabels.forEach { label ->
                assertAllowed(requested, label)
                assertAllowed(query = product, label = label, strictMatchPhrase = requested)
            }

            val rejectedLabels = listOf(
                title,
                "Non-organic $title",
                "Nonorganic $title",
                "Non Organic $title",
                "Not Organic $title",
                "No Organic $title",
                "Organic $title, not organic",
                "Almost Organic $title",
                "Possibly Organic $title",
                "Organic-style $title",
                "Organic $title or conventional",
                "Partly Organic $title",
                "Partially Organic $title",
                "Semi-organic $title",
                "Mostly Organic $title",
                "Organic-free $title",
                "Organic $title - zero organic content",
                "Organic $title - 0% organic",
                "Organic $title - 0.0% organic",
                "Organic $title - 0 organic content",
                "50% Organic $title",
                "Organic $title - inorganic",
            )
            rejectedLabels.forEach { label ->
                assertBlocked(requested, label)
                assertBlocked(query = product, label = label, strictMatchPhrase = requested)
            }
        }
    }

    @Test fun nutrition_claim_and_real_pack_size_are_independent() {
        val query = "Protein Bar 20 g protein per serving"
        assertAllowed(query, "Protein Bar 50g · 20g protein per serving", query)
        assertAllowed(query, "Protein Bar 50g · 20.0 grams protein per serving", query)
        assertAllowed("Protein Bar 20g protein per 100g", "Protein Bar 50g · 20g protein per 100g",
            "Protein Bar 20g protein per 100g")
        for (label in listOf("Protein Bar 50g", "Protein Bar 50g · 15g protein per serving",
            "Protein Bar 50g · 20g protein per 100g", "Protein Bar 20g protein per serving of 50g")) {
            assertBlocked(query, label, query)
        }
        val exactPack = "Protein Bar 50g · 20g protein per serving"
        assertAllowed(exactPack, exactPack, exactPack)
        assertBlocked(exactPack, "Protein Bar 100g · 20g protein per serving", exactPack)
        assertBlocked(exactPack, "Protein Bar 20g protein per serving", exactPack)
        assertAllowed("brown sugar", "Organic Brown Sugar 500g")
    }

    @Test
    fun denial_only_property_terms_do_not_satisfy_product_identity() {
        val deniedOccurrences = listOf(
            "sugar" to listOf(
                "Kombucha Zero Added Sugar Ginger Lemongrass 220 ml",
                "Snack No Sugar", "Snack Without Sugar", "Snack Zero Sugar", "Snack 0 Sugar",
                "Snack No Added Sugar", "Snack Without Added Sugar", "Snack Zero Added Sugar",
                "Snack 0 Added Sugar", "Snack Sugar Free", "Salt without added sugar",
            ),
            "salt" to listOf(
                "Snack Zero Added Salt 100 g", "Snack No Salt", "Snack Without Salt",
                "Snack Zero Salt", "Snack 0 Salt", "Snack No Added Salt", "Snack Without Added Salt",
                "Snack Zero Added Salt", "Snack 0 Added Salt", "Snack Salt Free",
            ),
            "milk" to listOf(
                "Drink Without Milk 200 ml", "Drink No Milk", "Drink Zero Milk", "Drink 0 Milk",
                "Drink Without Added Milk", "Drink Milk Free",
            ),
            "butter" to listOf(
                "Snack Butter Free", "Snack No Butter", "Snack Without Butter", "Snack Zero Butter",
                "Snack 0 Butter", "Snack Without Added Butter",
            ),
        )
        val failures = mutableListOf<String>()
        deniedOccurrences.forEach { (query, labels) ->
            labels.forEach { label ->
                listOf(null, query).forEach { strict ->
                    runCatching { assertBlocked(query, label, strictMatchPhrase = strict) }
                        .onFailure { failures += "query=$query strict=${strict != null} label=$label" }
                }
            }
        }
        data class PositiveControl(val query: String, val label: String, val strict: String? = null)
        listOf(
            PositiveControl("sugar", "Brown Sugar"),
            PositiveControl("brown sugar", "Organic Brown Sugar 500 g", "brown sugar"),
            PositiveControl("salt", "Salt without added sugar"),
            PositiveControl("milk chocolate", "Milk Chocolate No Added Sugar", "milk chocolate"),
            PositiveControl("butter", "Butter"),
        ).forEach { control ->
            runCatching { assertAllowed(control.query, control.label, strictMatchPhrase = control.strict) }
                .onFailure { failures += "positive-control=${control.query} label=${control.label}" }
        }
        assertTrue("Denial-only property wording must not prove requested identity: $failures", failures.isEmpty())
    }

    @Test
    fun explicit_property_subjects_remain_valid_identity_in_both_match_fields() {
        val accepted = listOf(
            "salt free crackers",
            "no added salt crackers",
            "milk free drink",
            "palm oil free biscuits",
        )
        val failures = mutableListOf<String>()
        accepted.forEach { phrase ->
            listOf(null, phrase).forEach { strict ->
                runCatching { assertAllowed(phrase, phrase, strictMatchPhrase = strict) }
                    .onFailure { failures += "positive query=$phrase strict=${strict != null}" }
            }
        }
        listOf(null, "cheeni").forEach { strict ->
            runCatching {
                assertBlocked("cheeni", "Kombucha Zero Added Sugar", strictMatchPhrase = strict)
            }.onFailure { failures += "alias denial strict=${strict != null}" }
        }
        runCatching { assertAllowed("salt", "Salt without added sugar") }
            .onFailure { failures += "positive salt with unrelated sugar exclusion" }
        assertTrue("Explicit property subjects and unrelated exclusions: $failures", failures.isEmpty())
    }

    @Test
    fun adversarial_negative_claims_never_supply_the_requested_product_term() {
        val denied = listOf(
            "eggs" to "Cake Without Eggs",
            "buttr" to "Snack Butter Free",
            "tomatoe" to "Sauce No Tomato",
            "salt" to "No Added Sugar & Salt",
            "sugar" to "No Refined Sugar Bar",
            "sugar" to "Free From Sugar Bar",
            "sugar" to "Kombucha Zero Added Sugar",
            "sugar" to "Kombucha / Zero Added Sugar",
            "sugar" to "Drink No Added · Sugar 220 ml",
            "sugar" to "Drink No Added — Sugar 220 ml",
        )
        val failures = mutableListOf<String>()
        denied.forEach { (query, label) ->
            listOf(null, query).forEach { strict ->
                runCatching { assertBlocked(query, label, strictMatchPhrase = strict) }
                    .onFailure { failures += "query=$query strict=${strict != null} label=$label" }
            }
        }

        listOf(
            "Drink No Added · Sugar 220 ml" to "Sugar 220 ml",
            "Snack No Added · Sugar" to "Sugar",
            "Kombucha Without · Sugar" to "Sugar",
            "Kombucha Zero Added · Sugar" to "Sugar",
        ).forEachIndexed { index, (label, variant) ->
            val candidate = RecommendationCandidate(
                spinId = "synthetic-denial-primary-positive-variant-$index",
                label = label,
                variant = variant,
            )
            listOf(null, "sugar").forEach { strict ->
                runCatching {
                    assertFalse(
                        "Positive variant must not rescue denial-only primary label (strict=${strict != null})",
                        isSwiggyCandidateAllowed(item("sugar", strict), candidate),
                    )
                }.onFailure { failures += "primary-positive-variant label=$label strict=${strict != null}" }
            }
        }

        val subtitleRescue = RecommendationCandidate(
            spinId = "synthetic-kombucha-subtitle-rescue",
            label = "Kombucha Zero Added Sugar",
            subtitle = "Fermented with cane sugar",
        )
        listOf(null, "sugar").forEach { strict ->
            runCatching {
                assertFalse(
                    "Subtitle must not rescue denial-only primary label (strict=${strict != null})",
                    isSwiggyCandidateAllowed(item("sugar", strict), subtitleRescue),
                )
            }.onFailure { failures += "subtitle-rescue strict=${strict != null}" }
        }

        val positive = listOf(
            "lactose free milk" to "Lactose Free Milk",
            "gluten free bread" to "Gluten Free Bread",
            "salt free snack" to "Salt Free Snack",
            "organic ginger" to "100.0% Organic Ginger",
        )
        positive.forEach { (query, label) ->
            listOf(null, query).forEach { strict ->
                runCatching { assertAllowed(query, label, strictMatchPhrase = strict) }
                    .onFailure { failures += "positive query=$query strict=${strict != null} label=$label" }
            }
        }
        assertTrue("Negative property claims must not satisfy product identity: $failures", failures.isEmpty())
    }

    @Test
    fun negated_composite_form_terms_do_not_supply_the_requested_form() {
        val cases = listOf(
            Triple("full cream milk", "Milk Without Full Cream", "Full Cream Milk"),
            Triple("full cream milk", "Snack Without Butter or Full Cream Milk", "Full Cream & Sugar Free Milk"),
            Triple("low fat milk", "Milk Without Sugar or Low Fat", "No Sugar & Low Fat Milk"),
            Triple("skimmed milk", "No Sugar or Skimmed Milk", "No Sugar & Skimmed Milk"),
            Triple("low fat milk", "Milk No Low Fat", "Low Fat Milk"),
            Triple("low fat milk", "No Sugar & No Low Fat Milk", "No Added Sugar & Low Fat Milk"),
            Triple("low fat milk", "Milk No Low Fat", "Low Fat & Lactose Free Milk"),
            Triple("low fat milk", "Full Cream & Low Fat Free Milk", "Skimmed & Lactose Free Milk"),
            Triple("full cream milk", "Full Cream Free Milk", "Full Cream & Sugar Free Milk"),
            Triple("ready to eat snack", "No Preservatives & No Ready To Eat Snack", "No Preservatives & Ready To Eat Snack"),
            Triple("milk", "Drink Without Milk With Less Cream", "Milk With Less Cream"),
            Triple("low fat milk", "Milk Without Double Toned", "Double Toned Milk"),
            Triple("low fat milk", "Milk Without Skimmed", "Skimmed Milk"),
            Triple("double toned milk", "Milk Without Double Toned", "Double Toned Milk"),
            Triple("ready to drink tea", "Tea Without Ready To Drink", "Ready To Drink Tea"),
            Triple("ready made tea", "Tea Without Ready Made", "Ready Made Tea"),
            Triple("ready to cook samosa", "Samosa Without Ready To Cook", "Ready To Cook Samosa"),
            Triple("ready to eat popcorn", "Popcorn Without Ready To Eat", "Ready To Eat Popcorn"),
            Triple("tetra pack milk", "Milk Without Tetra Pack", "Tetra Pack Milk"),
        )
        val failures = mutableListOf<String>()
        cases.forEach { (query, negativeLabel, positiveLabel) ->
            listOf(null, query).forEach { strict ->
                runCatching { assertBlocked(query, negativeLabel, strictMatchPhrase = strict) }
                    .onFailure { failures += "negative query=$query strict=${strict != null} label=$negativeLabel" }
                runCatching { assertAllowed(query, positiveLabel, strictMatchPhrase = strict) }
                    .onFailure { failures += "positive query=$query strict=${strict != null} label=$positiveLabel" }
            }
        }
        assertTrue("Negated composite form terms must not supply requested identity: $failures", failures.isEmpty())
    }

    @Test
    fun denial_subject_does_not_mask_other_terms_or_overreject_positive_forms() {
        val denied = listOf(
            "salt" to "Snack No Sugar",
            "salt" to "Snack No Salt",
            "salt" to "No Added Sugar & No Salt",
            "salt" to "No Sugar or Added Salt",
            "milk" to "Drink Zero Percent Milk",
            "milk" to "Drink Zero% Milk",
            "salt" to "Snack Salt Free",
            "salt" to "Salt-Free Snack",
        )
        val failures = mutableListOf<String>()
        denied.forEach { (query, label) ->
            listOf(null, query).forEach { strict ->
                runCatching { assertBlocked(query, label, strictMatchPhrase = strict) }
                    .onFailure { failures += "denied query=$query strict=${strict != null} label=$label" }
            }
        }

        data class Positive(val query: String, val label: String, val subtitle: String? = null)
        val positive = listOf(
            Positive("butter", "Amul Butter", "Free From Preservatives"),
            Positive("honey", "Honey Free From Adulteration"),
            Positive("salt", "Iodised Salt", "Free Flowing"),
            Positive("salt", "Iodised Salt Free-Flowing"),
            Positive("eggs", "Eggs - Free Range"),
            Positive("sugar free", "Sugar Free Gold 50g"),
            Positive("sugar free", "Sugar Free Natura Low Calorie Sweetner"),
            Positive("unsalted butter", "Butter 0% Salt"),
        )
        positive.forEachIndexed { index, control ->
            listOf(null, control.query).forEach { strict ->
                val candidate = RecommendationCandidate(
                    spinId = "synthetic-denial-subject-positive-$index-${strict != null}",
                    label = control.label,
                    subtitle = control.subtitle,
                )
                runCatching {
                    assertTrue(
                        "Expected positive product subject '${control.query}' in '${control.label}'",
                        isSwiggyCandidateAllowed(item(control.query, strict), candidate),
                    )
                }.onFailure { failures += "positive query=${control.query} strict=${strict != null} label=${control.label}" }
            }
        }
        assertTrue("Denial subject must neither supply nor erase product identity: $failures", failures.isEmpty())
    }

    @Test
    fun aliasSubjectsInsideDenialsDoNotBecomeIdentityEvidence() {
        val denied = listOf(
            "sesame paste" to "Cookies Without Sesame Paste",
            "tahini" to "Cookies Without Sesame Paste",
            "quinoa" to "Cookies Without Keen Waa",
            "keenwaa" to "Cookies Without Keen Waa",
        )
        val positive = listOf(
            "sesame paste" to "Tahini",
            "tahini" to "Sesame Paste",
            "quinoa" to "Keen Waa",
            "keenwaa" to "Quinoa",
            "sweetener" to "Sugar Free Gold 50g",
        )
        val failures = mutableListOf<String>()
        denied.forEach { (query, label) ->
            listOf(null, query).forEach { strict ->
                runCatching { assertBlocked(query, label, strictMatchPhrase = strict) }
                    .onFailure { failures += "denied query=$query strict=${strict != null} label=$label" }
            }
        }
        positive.forEach { (query, label) ->
            listOf(null, query).forEach { strict ->
                runCatching { assertAllowed(query, label, strictMatchPhrase = strict) }
                    .onFailure { failures += "positive query=$query strict=${strict != null} label=$label" }
            }
        }
        assertTrue("Alias denial masks must preserve explicit positive aliases: $failures", failures.isEmpty())
    }

    @Test
    fun allows_expected_exact_generic_and_phonetic_matches() {
        assertAllowed("sesame paste", "Tahini")
        assertAllowed("sesame paste", "Sesame Paste")
        assertAllowed("ginger garlic paste", "Ginger Garlic Paste")
        assertAllowed("fresh coriander leaves", "Fresh Coriander Leaves")
        assertAllowed("keenwaa", "Quinoa")
        assertAllowed("mozrella", "Mozzarella Cheese")
        assertAllowed("amul buttr", "Amul Butter")
        assertAllowed("amul buttr 500g pack", "Amul Butter 500 g Pack")
        assertAllowed("oneplus 12r", "OnePlus 12R")
        assertAllowed("CR2032 battery", "CR2032 Battery")
        assertAllowed("milk", "Milk")
        assertAllowed("Milk", "Plain Milk")
        assertAllowed("1.5 litres milk", "Milk 3 x 500 ml")
        assertAllowed("unsalted butter", "Butter 500 g (unsalted)")
    }

    @Test
    fun from_origin_identity_is_ordered_bound_and_checked_in_query_and_strict_phrase() {
        assertAllowed("Alpha tea from Beta", "Alpha tea from Beta")
        assertAllowed("  ALPHA   tea   FROM   beta  ", "Organic Alpha tea from BETA")
        assertAllowed(
            query = "tea from Beta",
            label = "Organic Alpha tea from BETA",
            strictMatchPhrase = "Alpha tea from Beta",
        )
        assertAllowed(
            query = "Alpha tea from Beta",
            label = "Premium Fairtrade Organic Alpha tea from Beta",
            strictMatchPhrase = "Premium Alpha tea from Beta",
        )
        assertAllowed(
            query = "Alpha tea from Beta",
            label = "Alpha tea from Beta",
            strictMatchPhrase = "Alpha tea",
        )
        assertAllowed(
            query = "Alpha tea",
            label = "Organic Alpha tea from Beta",
            strictMatchPhrase = "Alpha tea from Beta",
        )
        assertAllowed("Älpha tea from Béta", "äLPHA tea FROM BÉTA")

        assertAllowed("Alpha jasmine tea from Blue Valley", "Organic Alpha jasmine tea FROM blue   valley")
        assertBlocked("Alpha jasmine tea from Blue Valley", "Alpha jasmine tea from Valley Blue")
        assertBlocked("Alpha jasmine tea from Blue Blue Valley", "Alpha jasmine tea from Blue Valley")
        assertBlocked("Alpha jasmine tea from Blue Valley", "Alpha jasmine tea from Blue Blue Valley")
        assertBlocked(
            query = "Alpha jasmine tea",
            label = "Alpha jasmine tea from Valley Blue",
            strictMatchPhrase = "Alpha jasmine tea from Blue Valley",
        )
        assertBlocked(
            query = "Alpha jasmine tea from Blue Valley",
            label = "Alpha jasmine tea from Valley Blue",
            strictMatchPhrase = "Alpha jasmine tea",
        )

        listOf(
            "Beta tea from Alpha", // reversed origin
            "Alpha tea from Gamma", // wrong origin
            "Alpha tea from Alpha", // shared/changed origin
            "Alpha tea from Beta Gamma", // mixed origin
            "Beta tea from Beta", // requested source-side identity is missing
            "Alpha tea", // missing marker
            "Alpha tea from Beta from Gamma", // repeated marker
            "from Beta", // empty left side
            "Alpha tea from", // empty right side
        ).forEach { label -> assertBlocked("Alpha tea from Beta", label) }

        assertBlocked("Alpha tea from Beta from Gamma", "Alpha tea from Beta")
        assertBlocked("from Beta", "Alpha tea from Beta")
        assertBlocked("Alpha tea from", "Alpha tea from Beta")

        // A raw FROM relation cannot be rescued by a strict phrase when the
        // candidate reverses that relation. A field without FROM is not itself
        // contradictory when the other field supplies the valid relation.
        assertBlocked(
            query = "Alpha tea from Beta",
            label = "Beta tea from Alpha",
            strictMatchPhrase = "Alpha tea",
        )
        assertBlocked(
            query = "Alpha tea from Beta",
            label = "Alpha tea from Beta",
            strictMatchPhrase = "Alpha tea from Gamma",
        )
        assertBlocked(
            query = "Alpha tea from Beta",
            label = "Alpha tea from Beta",
            strictMatchPhrase = "Alpha tea from Beta from Gamma",
        )
        assertBlocked(
            query = "Alpha tea from Beta",
            label = "Alpha tea from Beta",
            strictMatchPhrase = "Alpha tea from",
        )
        assertBlocked(
            query = "Alpha tea from Beta",
            label = "Alpha tea from Beta Green",
        )
        assertBlocked(
            query = "Alpha tea from Beta",
            label = "Beta tea from Alpha",
            strictMatchPhrase = "Alpha tea from Beta",
        )
        assertBlocked(
            query = "Alpha tea from Beta",
            label = "Alpha tea from Beta from Gamma",
            strictMatchPhrase = "Alpha tea from Beta",
        )

        // No-FROM requests retain the existing ordinary identity behavior.
        assertAllowed("Alpha tea", "Organic Alpha tea")
        assertAllowed("tea fromage", "Tea Fromage")
        assertAllowed("Alpha teafrom Beta", "Alpha Teafrom Beta")
        assertAllowed("Alpha tea fromé Beta", "Alpha Tea Fromé Beta")
    }

    @Test
    fun rejects_wrong_brand_model_digit_and_unrelated_matches() {
        assertBlocked("sesame paste", "Aruna Fish Fry Paste 250g")
        assertBlocked("sesame paste", "Curry Paste")
        assertBlocked("sesame paste", "Ginger Paste")
        assertBlocked("Amul butter", "Amul Milk")
        assertBlocked("Amul", "Wrongbrand")
        assertBlocked("CR2032", "CR2025")
        assertBlocked("milk", "silk")
        assertBlocked("butter", "batter")
        assertBlocked("soup", "Dettol Soap 100 g")
        assertBlocked("bitter", "Amul Butter 500 g")
        assertBlocked("floor", "Aashirvaad Atta flour")
        assertBlocked("ginger", "Fish Finger")
        assertBlocked("Pampers Premium Care taped diapers", "diapers")
        assertBlocked("salted butter", "Butter 500 g (unsalted)")
        assertBlocked("butter 500 g (unsalted)", "Butter 500 g (salted)")
    }

    @Test
    fun honors_strict_match_phrase_and_avoid_phrases() {
        assertBlocked(
            query = "milk",
            label = "Milk",
            strictMatchPhrase = "strawberry milk",
        )
        assertBlocked(
            query = "amul butter",
            label = "Amul Butter 100 g",
            strictMatchPhrase = "Amul Butter 500 g",
        )
        assertAllowed(
            query = "amul butter",
            label = "Amul Butter 500gm",
            strictMatchPhrase = "Amul Butter 500 g",
        )
        assertBlocked(
            query = "amul butter",
            label = "Amul Butter 2 x 500 g",
            strictMatchPhrase = "Amul Butter 500 g",
        )
        assertBlocked("butter", "Butter 3 packs", strictMatchPhrase = "Butter 2 packs")
        assertBlocked("eggs", "Eggs 6 packs", strictMatchPhrase = "Eggs 6 pieces")
        assertBlocked(
            query = "milk",
            label = "Salted Milk",
            avoidPhrases = listOf("salted"),
        )
        assertAllowed(
            query = "milk",
            label = "Unsalted Milk",
            avoidPhrases = listOf("salted"),
        )
        assertAllowed(
            query = "salted butter",
            label = "Butter 500 g (salted)",
            strictMatchPhrase = "salted butter",
        )
    }

    @Test
    fun strict_piece_pack_phrase_requires_the_measured_contents_type_and_exact_count() {
        val contents = listOf(
            "lozenge" to "lozenges",
            "tablet" to "tablets",
            "capsule" to "capsules",
            "sachet" to "sachets",
            "pellet" to "pellets",
            "egg" to "eggs",
            "battery" to "batteries",
        )
        contents.forEachIndexed { index, (singular, plural) ->
            val wrongMeasuredNoun = contents[(index + 1) % contents.size].second
            val query = "BrandA $plural pack 24 $singular"
            val strict = "BrandA $plural pack 24 pieces"

            assertAllowed(query, "BrandA $plural pack 24 $singular", strictMatchPhrase = strict)
            assertAllowed(query, "BrandA $plural pack 24 $plural", strictMatchPhrase = strict)
            assertAllowed(query, "BrandA $plural pack 24 pieces", strictMatchPhrase = strict)

            // The requested type is present in the product identity, but a
            // conflicting noun owns the measured contents count.
            assertBlocked(query, "BrandA $plural pack 24 $wrongMeasuredNoun", strictMatchPhrase = strict)
            assertBlocked(
                query,
                "BrandA $plural pack 24 $plural and 24 $wrongMeasuredNoun",
                strictMatchPhrase = strict,
            )
            assertBlocked(query, "BrandA $plural pack 23 $plural", strictMatchPhrase = strict)
        }

        // Without an explicit contents type in the strict request, a generic
        // piece count does not silently upgrade to a typed tablet request.
        assertAllowed(
            query = "BrandA sweetener",
            label = "BrandA sweetener pack 24 tablets",
            strictMatchPhrase = "BrandA sweetener pack 24 pieces",
        )
    }

    @Test
    fun strict_household_pest_targets_allow_only_the_known_plural_equivalences() {
        val targets = listOf("cockroach" to "cockroaches", "mosquito" to "mosquitoes", "fly" to "flies",
            "ant" to "ants", "insect" to "insects", "spider" to "spiders", "termite" to "termites")
        for ((singular, plural) in targets) {
            assertAllowed("spray for $singular", "Spray for $plural", strictMatchPhrase = "spray for $singular")
            assertAllowed("spray for $plural", "Spray for $singular", strictMatchPhrase = "spray for $plural")
        }

        assertAllowed("BrandA cockroach spray", "BrandA Cockroaches Spray",
            strictMatchPhrase = "BrandA cockroach spray")
        assertBlocked("BrandA cockroach spray", "BrandB Cockroach Spray",
            strictMatchPhrase = "BrandA cockroach spray")
        assertBlocked("BrandA cockroach spray", "BrandA Mosquito Spray",
            strictMatchPhrase = "BrandA cockroach spray")
        assertBlocked("BrandA cockroach spray 500 ml", "BrandA Cockroach Spray 300 ml",
            strictMatchPhrase = "BrandA cockroach spray 500 ml")
        // Plural equivalence is explicit; spelling-near variants remain unsupported.
        assertBlocked("cockroach spray", "Cockroech Spray", strictMatchPhrase = "cockroach spray")
        assertBlocked("BrandA flys spray", "BrandA Fly Spray",
            strictMatchPhrase = "BrandA flys spray")
        val cyrillicO = '\u043E'
        val confusableMosquitoes = "BrandA m${cyrillicO}squitoes spray"
        assertBlocked(confusableMosquitoes, "BrandA Mosquito Spray",
            strictMatchPhrase = confusableMosquitoes)
    }

    private fun assertAllowed(
        query: String,
        label: String,
        strictMatchPhrase: String? = null,
        avoidPhrases: List<String> = emptyList(),
    ) {
        assertTrue(
            "Expected '$query' to allow '$label'",
            isSwiggyCandidateAllowed(
                item = item(query, strictMatchPhrase, avoidPhrases),
                candidate = candidate(label),
            ),
        )
    }

    private fun assertBlocked(
        query: String,
        label: String,
        strictMatchPhrase: String? = null,
        avoidPhrases: List<String> = emptyList(),
    ) {
        assertFalse(
            "Expected '$query' to reject '$label'",
            isSwiggyCandidateAllowed(
                item = item(query, strictMatchPhrase, avoidPhrases),
                candidate = candidate(label),
            ),
        )
    }

    private fun item(
        query: String,
        strictMatchPhrase: String? = null,
        avoidPhrases: List<String> = emptyList(),
    ): ParsedItem {
        return ParsedItem(
            rawText = query,
            query = query,
            strictMatchPhrase = strictMatchPhrase,
            avoidPhrases = avoidPhrases,
        )
    }

    private fun candidate(label: String): RecommendationCandidate {
        return RecommendationCandidate(
            spinId = "synthetic-" + label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'),
            label = label,
        )
    }
}
