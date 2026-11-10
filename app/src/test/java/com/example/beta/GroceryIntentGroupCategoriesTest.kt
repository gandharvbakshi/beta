package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GroceryIntentGroupCategoriesTest {
    @Test
    fun organizational_produce_headings_prove_only_closed_whole_produce_membership() {
        val vegetables = "Vegetables:"
        assertEquals(
            GroceryIntentGroupCategoryHeader("", setOf("vegetables"), organizational = true),
            groceryIntentGroupCategoryHeader(vegetables),
        )
        assertNull(groceryIntentGroupCategoryIssue(vegetables, "2 tamatar (500 g)", "tomato"))
        assertNull(groceryIntentGroupCategoryIssue(vegetables, "1 aloo", "potato"))
        assertNull(groceryIntentGroupCategoryIssue(vegetables, "2 coriander leaves (500 g each)", "coriander leaf"))
        assertEquals(emptyList<String>(), groceryIntentGroupCategoryAliases(vegetables, "1 tomato"))

        assertEquals(
            "group_category_membership_unproven",
            groceryIntentGroupCategoryIssue(vegetables, "1 tomato puree", "tomato puree"),
        )
        assertEquals(
            "group_category_membership_unproven",
            groceryIntentGroupCategoryIssue("Fruits:", "1 apple juice", "apple juice"),
        )
        assertEquals(
            "group_category_membership_unproven",
            groceryIntentGroupCategoryIssue("Fruits:", "1 tamatar", "tomato"),
        )
        assertEquals(
            "group_category_membership_unproven",
            groceryIntentGroupCategoryIssue(vegetables, "1 tomato", "apple"),
        )
        assertEquals(
            "group_category_identity_lost",
            groceryIntentGroupCategoryIssue(vegetables, "1 organic tamatar", "tomato"),
        )
        assertNull(groceryIntentGroupCategoryHeader("Fruits and vegetables:"))
        assertNull(groceryIntentGroupCategoryHeader("Vegetables: and fruits:"))
    }

    @Test fun mixedProduceHeadingsCannotLoseTheirCategoryThroughSingleCategoryMapping() {
        for (header in listOf("fruits and snacks:", "BrandA fruits and snacks:",
            "fruits and vegetables items, take the quantity written beside each item:")) {
            assertNull(header, groceryIntentGroupCategoryHeader(header, localQuantities = true))
        }
    }

    @Test
    fun declared_dal_and_flour_categories_accept_matching_primary_and_proposed_items() {
        val header = "BrandA dals and flour"

        val parsed = groceryIntentGroupCategoryHeader(header)
        assertNotNull(parsed)
        assertEquals("BrandA", parsed?.identity)
        assertEquals(setOf("pulses", "flour"), parsed?.categories)
        assertNull(groceryIntentGroupCategoryIssue(header, "1 chana dal", "BrandA chana dal"))
        assertNull(groceryIntentGroupCategoryIssue(header, "1 besan", "BrandA besan"))
    }

    @Test
    fun item_must_match_a_declared_category_in_both_primary_and_proposed_identity() {
        val header = "BrandA dals and flour"

        assertNotNull(groceryIntentGroupCategoryIssue(header, "1 chana dal", "BrandA flour"))
        assertNotNull(groceryIntentGroupCategoryIssue(header, "1 rice", "BrandA dal"))
        assertNotNull(groceryIntentGroupCategoryIssue(header, "1 chana dal", "BrandA moong dal"))
        assertNotNull(groceryIntentGroupCategoryIssue("BrandA flours", "1 organic besan", "BrandA besan"))
    }

    @Test
    fun unrecognized_heading_does_not_apply_category_exemptions() {
        val header = "BrandA groceries"

        assertNull(groceryIntentGroupCategoryHeader(header))
        assertNull(groceryIntentGroupCategoryIssue(header, "1 rice", "BrandA dal"))
    }

    @Test
    fun category_suffix_preserves_all_preceding_identity_text() {
        val parsed = groceryIntentGroupCategoryHeader("BrandA organic dals")

        assertNotNull(parsed)
        assertEquals("BrandA organic", parsed?.identity)
        assertEquals(setOf("pulses"), parsed?.categories)
    }

    @Test
    fun negated_category_phrase_does_not_create_a_category_exemption() {
        val header = "BrandA no-snacks"

        assertNull(groceryIntentGroupCategoryHeader(header))
        assertNull(groceryIntentGroupCategoryIssue(header, "1 rice", "BrandA dal"))
    }

    @Test
    fun direct_snack_categories_are_allowed_but_raw_moong_dal_is_not_a_snack() {
        val header = "BrandA snacks"

        assertNull(groceryIntentGroupCategoryIssue(header, "1 biscuits", "BrandA biscuits"))
        assertNull(groceryIntentGroupCategoryIssue(header, "1 bhujia", "BrandA bhujia"))
        assertNotNull(groceryIntentGroupCategoryIssue(header, "1 moong dal", "BrandA moong dal"))
    }

    @Test
    fun compound_ingredient_and_processing_descriptors_do_not_lend_their_category() {
        assertNotNull(groceryIntentGroupCategoryIssue("BrandA pulses", "1 rice", "BrandA dal flour"))
        assertNotNull(groceryIntentGroupCategoryIssue("BrandA snacks", "1 rice", "BrandA biscuit mix"))
        assertNotNull(groceryIntentGroupCategoryIssue("BrandA flours", "1 rice", "BrandA besan coated peanuts"))
    }

    @Test
    fun category_header_requires_literal_terminal_suffix_and_supported_joiners() {
        assertNotNull(groceryIntentGroupCategoryHeader("BrandA pulses and flour"))
        assertNotNull(groceryIntentGroupCategoryHeader("BrandA dals aur snacks"))
        assertNotNull(groceryIntentGroupCategoryHeader("BrandA flour & biscuits"))

        assertFalse(groceryIntentGroupCategoryHeader("BrandA pulses, flour") != null)
        assertFalse(groceryIntentGroupCategoryHeader("BrandA pulses or flour") != null)
        assertFalse(groceryIntentGroupCategoryHeader("BrandA pulses / flour") != null)
        assertFalse(groceryIntentGroupCategoryHeader("BrandA flour collection") != null)
    }

    @Test
    fun every_declared_category_must_be_covered_by_a_proven_member() {
        val header = "BrandA dals and flour"
        val dalOnly = listOf("1 chana dal" to "BrandA chana dal")
        val bothCategories = listOf(
            "1 chana dal" to "BrandA chana dal",
            "1 besan" to "BrandA besan",
        )

        assertNotNull(groceryIntentGroupCategoryCoverageIssue(header, dalOnly))
        assertNull(groceryIntentGroupCategoryCoverageIssue(header, bothCategories))
    }

    @Test
    fun aliases_are_grounded_only_by_the_primary_terminal_category_noun() {
        val header = "BrandA dals and flours"

        assertEquals(listOf("flour"), groceryIntentGroupCategoryAliases(header, "1 besan"))
        assertTrue(groceryIntentGroupCategoryAliases(header, "1 besan").none { it == "atta" })
        assertTrue(groceryIntentGroupCategoryAliases(header, "1 chana dal").none { it == "flour" })
        assertEquals(
            listOf("dal", "lentil", "pulse"),
            groceryIntentGroupCategoryAliases(header, "1 chana dal"),
        )
    }

    @Test
    fun category_heading_grounding_uses_only_the_universal_prefix() {
        assertEquals(
            "BrandA",
            groceryIntentGroupCategoryGroundingHeader("BrandA dals and flours", "1 besan"),
        )
        assertEquals(
            "BrandA groceries",
            groceryIntentGroupCategoryGroundingHeader("BrandA groceries", "1 besan"),
        )
    }

    @Test
    fun shared_manual_fixture_matches_kotlin_category_proof_apis() {
        val relativePath = "scripts/fixtures/intent_group_categories_oct05.json"
        val fixtureFile = listOf(File("../$relativePath"), File(relativePath))
            .firstOrNull(File::isFile)
            ?: error("Cannot find shared category fixture at $relativePath")
        val fixture = objectAt(JsonParser(fixtureFile.readText(Charsets.UTF_8)).parseStrict())
        assertEquals("beta.intent_group_categories.parity.v1", stringAt(fixture["schema"]))

        val cases = arrayAt(fixture["cases"])
        assertTrue("Fixture must retain at least 35 manual cases", cases.size >= 35)
        cases.forEach { caseValue ->
            val row = objectAt(caseValue)
            val caseId = stringAt(row["id"])
            val header = stringAt(row["header"])
            val primary = stringAt(row["primary"])
            val proposed = stringAt(row["proposed"])
            val expectedIdentity = nullableStringAt(row["expectedidentity"])
            val expectedCategories = stringListAt(row["expectedcategories"])
            val expectedIssue = nullableStringAt(row["expectedissue"])
            val expectedAliases = stringListAt(row["expectedaliases"])
            val requirements = row["requirements"]?.let(::stringListAt).orEmpty()
            val proof = groceryIntentGroupCategoryHeader(header)

            assertEquals("$caseId identity", expectedIdentity, proof?.identity)
            assertEquals("$caseId categories", expectedCategories.toSet(), proof?.categories.orEmpty())
            assertEquals(
                "$caseId issue",
                expectedIssue,
                groceryIntentGroupCategoryIssue(header, primary, proposed, requirements),
            )
            assertEquals("$caseId aliases", expectedAliases, groceryIntentGroupCategoryAliases(header, primary))
            assertEquals(
                "$caseId grounding header",
                expectedIdentity ?: header,
                groceryIntentGroupCategoryGroundingHeader(header, primary),
            )
        }

        arrayAt(fixture["coverage_cases"]).forEach { caseValue ->
            val row = objectAt(caseValue)
            val members = arrayAt(row["members"]).map { pairValue ->
                val pair = arrayAt(pairValue)
                stringAt(pair[0]) to stringAt(pair[1])
            }
            assertEquals(
                "${stringAt(row["id"])} coverage issue",
                nullableStringAt(row["expectedissue"]),
                groceryIntentGroupCategoryCoverageIssue(stringAt(row["header"]), members),
            )
        }
    }

    @Test
    fun requirements_preserve_original_identity_but_cannot_supply_a_category_witness() {
        assertNull(
            groceryIntentGroupCategoryIssue(
                "BrandA flours",
                "1 organic besan",
                "BrandA besan",
                listOf("organic"),
            ),
        )
        assertNotNull(
            groceryIntentGroupCategoryIssue(
                "BrandA flours",
                "1 sugar",
                "BrandA sugar",
                listOf("besan"),
            ),
        )
    }

    @Test
    fun fruits_are_a_category_only_after_verified_local_quantity_discourse() {
        val header = "fruits wale items likh raha hoon, har naam ke saamne jitne likhe hain utne hi lena:"
        assertNull(groceryIntentGroupCategoryHeader("fruits"))
        assertNull(groceryIntentGroupCategoryHeader("BrandA fruits"))
        assertEquals(
            GroceryIntentGroupCategoryHeader("", setOf("fruits")),
            groceryIntentGroupCategoryHeader(header, localQuantities = true),
        )
        assertNull(groceryIntentGroupCategoryHeader(header, localQuantities = false))
        assertNull(groceryIntentGroupCategoryCoverageIssue(
            header,
            listOf("1 apple" to "apple", "2 kela" to "kela"),
            localQuantities = true,
        ))
        assertNotNull(groceryIntentGroupCategoryIssue(header, "1 onion", "onion", localQuantities = true))
        assertNotNull(groceryIntentGroupCategoryIssue(header, "1 apple juice", "apple juice", localQuantities = true))
        assertNotNull(groceryIntentGroupCategoryIssue(header, "1 kela", "banana", localQuantities = true))
        assertEquals(emptyList<String>(), groceryIntentGroupCategoryAliases(header, "1 apple", localQuantities = true))
    }

    @Test
    fun dairy_category_requires_a_complete_verified_local_quantity_heading() {
        val header = "BrandA dairy items, take the quantity written beside each item:"

        assertNull(groceryIntentGroupCategoryHeader("Mother Dairy"))
        assertNull(groceryIntentGroupCategoryHeader("BrandA dairy"))
        assertNull(groceryIntentGroupCategoryHeader(header))
        assertNull(groceryIntentGroupCategoryHeader("Mother Dairy", localQuantities = true))
        assertEquals(
            GroceryIntentGroupCategoryHeader("BrandA", setOf("dairy")),
            groceryIntentGroupCategoryHeader(header, localQuantities = true),
        )
        assertEquals(emptyList<String>(), groceryIntentGroupCategoryAliases(header, "1 milk pouch", localQuantities = true))
    }

    @Test
    fun dairy_membership_projects_only_a_closed_terminal_container_and_preserves_it() {
        val header = "BrandA dairy items, take the quantity written beside each item:"
        val valid = listOf(
            Triple("2 BrandA Taaza milk pouches (500 ml each)", "BrandA Taaza milk", "pouch"),
            Triple("1 BrandA low fat milk carton (1 l)", "BrandA low fat milk", "carton"),
            Triple("1 BrandA unsalted butter pack (100 g)", "BrandA unsalted butter", "pack"),
            Triple("1 BrandA plain dahi cup (400 g)", "BrandA plain dahi", "cup"),
            Triple("1 BrandA lactose free milk carton (1 l)", "BrandA lactose free milk", "carton"),
            Triple("1 BrandA cheese slices pack (200 g)", "BrandA cheese slices", "pack"),
        )
        valid.forEach { (primary, proposed, container) ->
            assertNull(groceryIntentGroupCategoryIssue(header, primary, proposed, listOf(container), localQuantities = true))
            assertNotNull(groceryIntentGroupCategoryIssue(header, primary, proposed, localQuantities = true))
        }
    }

    @Test
    fun dairy_category_does_not_infer_ingredients_alternatives_or_other_product_forms() {
        val header = "BrandA dairy items, take the quantity written beside each item:"
        listOf(
            "oat milk", "almond milk", "soy milk", "coconut milk", "peanut butter", "cocoa butter",
            "vegan cheese", "non dairy milk", "plant milk", "milk biscuits", "butter popcorn",
            "cheese flavour crackers", "paneer mix", "cheese snack bar", "bread slices", "slices",
            "milk pack bottle", "milk powder", "dahi starter mix", "dairy-free milk", "dairy-free cheese",
            "milk free butter", "dairyfree cheese", "plantbased butter", "nondairy yogurt", "rice milk",
            "walnut milk", "hazelnut milk", "flax milk", "sunflower butter", "sesame milk", "hemp milk",
            "nut butter", "seed milk",
        ).forEach { product ->
            assertEquals(
                product,
                "group_category_membership_unproven",
                groceryIntentGroupCategoryIssue(header, "1 BrandA $product", "BrandA $product", localQuantities = true),
            )
        }
    }

    @Test
    fun dairy_category_never_drops_variant_or_form_identity() {
        val header = "BrandA dairy items, take the quantity written beside each item:"
        listOf(
            "1 BrandA low fat milk pouch (500 ml)" to "BrandA milk",
            "1 BrandA unsalted butter pack (100 g)" to "BrandA butter",
            "1 BrandA cheese slices pack (200 g)" to "BrandA cheese",
            "1 BrandA Taaza milk pouch (500 ml)" to "BrandA Gold milk",
        ).forEach { (primary, proposed) ->
            assertNotNull(
                groceryIntentGroupCategoryIssue(header, primary, proposed, listOf("pouch", "pack"), localQuantities = true),
            )
        }
    }

    @Test
    fun dairy_heading_does_not_inherit_a_brand_missing_from_the_source_item() {
        listOf("Mother", "BrandA").forEach { brand ->
            val header = "$brand Dairy items, take the quantity written beside each item:"
            assertEquals(
                "group_category_shared_identity_unproven",
                groceryIntentGroupCategoryIssue(header, "1 milk", "$brand milk", localQuantities = true),
            )
            assertEquals(
                "group_category_identity_lost",
                groceryIntentGroupCategoryIssue(header, "1 $brand Dairy milk", "$brand milk", localQuantities = true),
            )
            assertNull(
                groceryIntentGroupCategoryIssue(header, "1 $brand Dairy milk", "$brand Dairy milk", localQuantities = true),
            )
        }
    }

    @Test
    fun six_dairy_members_cover_one_verified_local_heading_without_erasing_forms() {
        val header = "BrandA dairy items, take the quantity written beside each item:"
        val members = listOf(
            "1 BrandA milk pouch (500 ml)" to "BrandA milk pouch",
            "1 BrandA butter pack (100 g)" to "BrandA butter pack",
            "1 BrandA dahi cup (400 g)" to "BrandA dahi cup",
            "1 BrandA curd tub (400 g)" to "BrandA curd tub",
            "1 BrandA paneer packet (200 g)" to "BrandA paneer packet",
            "1 BrandA cheese slices box (200 g)" to "BrandA cheese slices box",
        )
        val requirements = listOf("pouch", "pack", "cup", "tub", "packet", "box").map { listOf(it) }

        assertNull(groceryIntentGroupCategoryCoverageIssue(header, members, requirements, localQuantities = true))
    }

    @Test
    fun dairy_category_cannot_override_conflicting_required_modifiers() {
        val header = "BrandA dairy items, take the quantity written beside each item:"
        listOf("vegan", "dairy_free", "plant-based", "rice", "milk free").forEach { required ->
            assertEquals("group_category_membership_unproven", groceryIntentGroupCategoryIssue(
                header, "1 BrandA milk", "BrandA milk", listOf(required), localQuantities = true,
            ))
        }
    }

    private fun objectAt(value: JsonValue?): Map<String, JsonValue> =
        (value as? JsonValue.Obj)?.members ?: error("Expected JSON object")

    private fun arrayAt(value: JsonValue?): List<JsonValue> =
        (value as? JsonValue.Arr)?.items ?: error("Expected JSON array")

    private fun stringAt(value: JsonValue?): String =
        (value as? JsonValue.Str)?.value ?: error("Expected JSON string")

    private fun nullableStringAt(value: JsonValue?): String? = when (value) {
        JsonValue.Null -> null
        is JsonValue.Str -> value.value
        else -> error("Expected nullable JSON string")
    }

    private fun stringListAt(value: JsonValue?): List<String> = arrayAt(value).map(::stringAt)
}
