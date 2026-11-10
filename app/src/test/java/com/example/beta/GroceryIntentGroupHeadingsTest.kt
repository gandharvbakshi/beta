package com.example.beta

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroceryIntentGroupHeadingsTest {
    @Test
    fun recognizes_only_closed_organizational_produce_headings() {
        mapOf(
            "fruits:" to "fruits",
            "and fruit：" to "fruits",
            "AUR phal —" to "fruits",
            "phir fruits‒" to "fruits",
            "vegetables–" to "vegetables",
            "and sabzi:" to "vegetables",
            "aur sabziyan：" to "vegetables",
            "Sabzi side:" to "vegetables",
            "Fruits section:" to "fruits",
            "phir vegetables side:" to "vegetables",
        ).forEach { (header, expected) ->
            assertEquals(header, expected, groceryIntentOrganizationalCategoryHeading(header))
        }
        listOf(
            "fruits", "fresh fruits:", "BrandA vegetables:", "two fruits:", "fruits for smoothies:",
            "fruits and vegetables:", "fruits-", "and and fruits:", "fruits\n:", "fruits:\n",
            "fruits: milk", "fruıts:", "x".repeat(181) + " fruits:",
            "fruits side dish:", "fruits low sugar:", "BrandA fruits side:", "for smoothies fruits side:",
            "two fruits side:", "fruitsside:", "fruits\nside:", "fruits\u00a0side:",
            "fruits sіde:", "fruits side: extra",
        ).forEach { header ->
            assertNull(header, groceryIntentOrganizationalCategoryHeading(header))
        }
    }

    @Test
    fun recognizes_only_closed_local_quantity_heading_grammars() {
        assertEquals(
            "BrandA organic",
            groceryIntentLocalQuantityHeadingIdentity(
                "BrandA organic waale items likh raha hoon, har naam ke saamne jitne likhe hain utne hi lena:",
            ),
        )
        assertEquals(
            "BrandA",
            groceryIntentLocalQuantityHeadingIdentity(
                "For the kitchen shelf BrandA items, please take the stated quantity of each named item:",
            ),
        )
        assertEquals(
            "BrandA",
            groceryIntentLocalQuantityHeadingIdentity(
                "BrandA items, take the quantity written next to each item:",
            ),
        )
        assertEquals(
            "Organic fruit",
            groceryIntentLocalQuantityHeadingIdentity(
                "Organic fruit ke liye alag section bana do; har ORGANIC   FRUIT ki apni ginti hai:",
            ),
        )
        assertNull(groceryIntentLocalQuantityHeadingIdentity("BrandA items, take each quantity and also buy two"))
        assertNull(groceryIntentLocalQuantityHeadingIdentity("BrandA ke liye alag section bana do; har BrandB ki apni ginti hai"))
        assertNull(groceryIntentLocalQuantityHeadingIdentity("BrandA items; har naam ke saamne jitne likhe hain utne hi lena"))
        assertNull(groceryIntentLocalQuantityHeadingIdentity("BrandA items, har naam ke saamne jitne likhe hain utne hi lena\nthen more"))
    }

    @Test
    fun preserves_imperative_do_and_semantic_numeric_identity_separately() {
        assertEquals(
            "BrandA",
            groceryIntentLocalQuantityHeadingIdentity(
                "BrandA ke liye alag section bana do; har BrandA ki apni ginti hai",
            ),
        )
        assertEquals(
            "2 BrandA",
            groceryIntentLocalQuantityHeadingIdentity(
                "2 BrandA items, har naam ke saamne jitne likhe hain utne hi lena",
            ),
        )
        assertNull(groceryIntentLocalQuantityHeadingIdentity("BrandA—organic items, har naam ke saamne jitne likhe hain utne hi lena"))
    }

    @Test
    fun group_request_heading_accepts_only_complete_original_ascii_identities() {
        mapOf(
            "BrandA bhi chahiye" to "BrandA",
            "  BrandA organic bhi chaahiye,  " to "BrandA organic",
            "no sugar BrandA bhi chahiye:" to "no sugar BrandA",
            "sugar-free BrandA bhi daalna" to "sugar-free BrandA",
            "children's cereal bhi daalna," to "children's cereal",
            "2 BrandA bhi chahiye" to "2 BrandA",
            "do BrandA bhi chahiye" to "do BrandA",
            "the BrandA bhi chahiye" to "the BrandA",
            "BrandA fruit yoghurt cups bhi," to "BrandA fruit yoghurt cups",
            "BrandA organic bhi:" to "BrandA organic",
            "2 BrandA bhi" to "2 BrandA",
        ).forEach { (heading, expected) ->
            assertEquals(heading, expected, groceryIntentGroupRequestIdentity(heading))
        }
    }

    @Test
    fun group_request_heading_accepts_only_scoped_lowercase_mein_with_delimiter() {
        mapOf(
            "BrandA organic makhana mein," to "BrandA organic makhana",
            "BrandA organic makhana mein:" to "BrandA organic makhana",
            "  BrandA organic makhana mein,  " to "BrandA organic makhana",
        ).forEach { (heading, expected) ->
            assertEquals(heading, expected, groceryIntentGroupRequestIdentity(heading))
        }

        listOf(
            "Chow Mein:", "Lo Mein,", "BrandA organic makhana mein",
            "BrandA organic makhana Mein,", "BrandA organic makhana\nmein,",
            "BrandA organic makhana mein, please add",
        ).forEach { heading ->
            assertNull(heading, groceryIntentGroupRequestIdentity(heading))
        }
    }

    @Test
    fun group_request_heading_accepts_complete_for_request_wrapper() {
        mapOf(
            "For the Meadow fruit yoghurt cups, I want" to "the Meadow fruit yoghurt cups",
            "for Meadow fruit yoghurt cups, I need:" to "Meadow fruit yoghurt cups",
            "FOR THE Meadow fruit yoghurt cups, I WANT," to "THE Meadow fruit yoghurt cups",
        ).forEach { (heading, expected) ->
            assertEquals(heading, expected, groceryIntentGroupRequestIdentity(heading))
        }
    }

    @Test
    fun group_request_heading_for_wrapper_keeps_properties_and_rejects_incomplete_or_confusable_forms() {
        mapOf(
            "For the Meadow no-added-sugar fruit yoghurt cups, I want" to "the Meadow no-added-sugar fruit yoghurt cups",
            "For the Meadow 2 fruit yoghurt cups, I want" to "the Meadow 2 fruit yoghurt cups",
        ).forEach { (heading, expected) ->
            assertEquals(heading, expected, groceryIntentGroupRequestIdentity(heading))
        }
        listOf(
            "For, I want", "For the Meadow fruit yoghurt cups I want", "For the Meadow fruit yoghurt cups, maybe",
            "For the Meadow fruit yoghurt cups, I want extra",
            "For the Meadow fruit yoghurt cups, I want\n", "For the Meadow fruıt yoghurt cups, I want",
            "From Nature honey:",
        ).forEach { heading ->
            assertNull(heading, groceryIntentGroupRequestIdentity(heading))
        }
    }

    @Test
    fun group_request_heading_accepts_closed_from_then_wrappers_without_recursive_peeling() {
        mapOf(
            "From BrandA I need" to "BrandA",
            "from BrandA I want:" to "BrandA",
            "from 2 BrandA I need," to "2 BrandA",
            "from no-sugar BrandA I want:" to "no-sugar BrandA",
            "Then BrandB" to "BrandB",
            "then the BrandA fruit yoghurt cups:" to "the BrandA fruit yoghurt cups",
            "Then BrandA organic, " to "BrandA organic",
            "Then from BrandA" to "from BrandA",
            "Then BrandA if available" to "BrandA if available",
        ).forEach { (heading, expected) ->
            assertEquals(heading, expected, groceryIntentGroupRequestIdentity(heading))
        }
    }

    @Test
    fun group_request_heading_wrappers_reject_empty_malformed_and_confusable_forms() {
        listOf(
            "from", "Then", "from ,", "Then :", "from BrandA I need,:", "Then BrandA,,",
            "from BrandA", "from BrandA,", "From Then BrandA", "From Nature honey:",
            "from BrandA I need\n", "from BrandA\nI need", "frоm BrandA", "Then BrаndA", "BrandA",
        ).forEach { heading ->
            assertNull(heading, groceryIntentGroupRequestIdentity(heading))
        }
    }

    @Test
    fun group_request_heading_rejects_bare_or_different_request_suffixes() {
        listOf(
            "bhi chahiye",
            "bhi chaahiye",
            "bhi daalna",
            "bhi",
            "Bhi BrandA",
            "BrandA chahiye",
            "BrandA bhi chahie",
            "BrandA bhi daal do",
            "BrandA bhi daalo",
            "BrandA bhi add karna",
            "BrandA bhi lena",
        ).forEach { heading ->
            assertNull(heading, groceryIntentGroupRequestIdentity(heading))
        }
    }

    @Test
    fun group_request_heading_rejects_negatives_unknown_tails_and_repeated_separators() {
        listOf(
            "BrandA bhi nahi chahiye",
            "BrandA bhi nahi",
            "BrandA bhi 2",
            "BrandA bhi, sugar-free",
            "BrandA bhi na chahiye",
            "BrandA bhi mat daalna",
            "BrandA bhi chahiye please",
            "BrandA bhi chahiye 2",
            "BrandA bhi chahiye if available",
            "BrandA bhi chahiye, but sugar-free",
            "BrandA bhi chahiye,,",
            "BrandA bhi chahiye::",
            "BrandA bhi chahiye,:",
            "BrandA bhi chahiye, :",
        ).forEach { heading ->
            assertNull(heading, groceryIntentGroupRequestIdentity(heading))
        }
    }

    @Test
    fun group_request_heading_rejects_split_joined_multiline_or_confusable_forms() {
        listOf(
            "BrandA bhi\nchahiye",
            "BrandA\norganic bhi chahiye",
            "BrandA bhi chahiye\nmilk",
            "BrandA bhichahiye",
            "BrandA bhi cha hiye",
            "BrandA bhi chahi ye",
            "BrandA bhi daal na",
            "BrandA b hi chahiye",
            "BrandА bhi chahiye",
            "BrаndA bhi chahiye",
            "BrandA bһi chahiye",
            "BrandA bhi chāhiye",
            "children’s cereal bhi chahiye",
            "BrandA–organic bhi chahiye",
        ).forEach { heading ->
            assertNull(heading, groceryIntentGroupRequestIdentity(heading))
        }
    }

    @Test
    fun recognizes_only_closed_product_free_purpose_group_headings(): Unit {
        mapOf(
            "For breakfast:" to "For breakfast",
            "By the sink:" to "By the sink",
            "Kitchen:" to "Kitchen",
            "Washroom:" to "Washroom",
            "Pantry side:" to "Pantry side",
            "nashta ke liye:" to "nashta ke liye",
            "bathroom ke liye:" to "bathroom ke liye",
            "  For breakfast —  " to "For breakfast",
        ).forEach { (heading, expected) ->
            assertEquals(heading, expected, groceryIntentPurposeGroupHeading(heading))
        }
        listOf(
            "For breakfast", "By the sink", "Kitchen", "For diabetics:",
            "For breakfast organic:", "For BrandA breakfast:", "For 2 breakfasts:",
            "For breakfast: milk", "For breakfast nested: For lunch:",
            "For breakfast\n:", "For breakfast\t:", "For breakfast\u00a0:",
            "For breakfast\u2028:", "Kitchen:\u0000", "x".repeat(181) + " Kitchen:",
        ).forEach { heading ->
            assertNull(heading, groceryIntentPurposeGroupHeading(heading))
        }

        listOf(
            "By the sink, get", "For breakfast, please add", "In the kitchen, kindly bring",
            "For laundry, include", "Near the pantry, buy",
        ).forEach { heading ->
            assertEquals(heading, heading, groceryIntentPurposeGroupHeading(heading))
        }
        listOf(
            "By the sink, deliver", "For breakfast, order", "For diabetics, please add",
            "By the BrandA sink, get", "For breakfast add", "For breakfast, add organic milk",
            "For breakfast, get two",
        ).forEach { heading ->
            assertNull(heading, groceryIntentPurposeGroupHeading(heading))
        }
    }

    @Test
    fun contextual_affirmed_heading_requires_literal_identity_and_sentence_edge_transition() {
        fun fixture(
            transition: String = "Then",
            sentenceSeparator: String = ".",
            headerText: String = "BrandA yogurt, haan, ",
            sharedEach: Boolean = false,
            sharedTotal: Boolean = false,
            localAmounts: Boolean = true,
            forgedHeaderOffset: Boolean = false,
            includeLastPrimary: Boolean = true,
        ): Pair<GroceryIntentReviewV2Group, Pair<String, List<Pair<Int, String>>>> {
            val instruction = "one rice$sentenceSeparator $transition $headerText organic mango one, organic strawberry two"
            val headerStart = instruction.indexOf(headerText)
            val groupSourceText = instruction.substring(headerStart)
            fun quote(text: String, startAt: Int = instruction.indexOf(text)): GroceryIntentReviewV2Quote {
                require(startAt >= 0)
                return GroceryIntentReviewV2Quote(text, startAt, startAt + text.length)
            }
            val header = quote(headerText, headerStart + if (forgedHeaderOffset) 1 else 0)
            val source = quote(groupSourceText, headerStart)
            val memberQuote = quote("organic mango one")
            val sharedAmount = GroceryIntentReviewV2GroupAmount(memberQuote, "one", BigDecimal.ONE, "count")
            val members = if (localAmounts) listOf(
                GroceryIntentReviewV2GroupMember(1, BigDecimal.ONE, "count", "local"),
                GroceryIntentReviewV2GroupMember(2, BigDecimal("2"), "count", "local"),
            ) else listOf(
                GroceryIntentReviewV2GroupMember(1, null, "unspecified", "not_stated"),
                GroceryIntentReviewV2GroupMember(2, null, "unspecified", "not_stated"),
            )
            val group = GroceryIntentReviewV2Group(
                status = "structurally_verified",
                amountStatus = when {
                    sharedEach -> "each_verified"
                    sharedTotal -> "total_verified"
                    localAmounts -> "local_amounts_verified"
                    else -> "amounts_unspecified"
                },
                source = source,
                header = header,
                memberIndices = listOf(1, 2),
                members = members,
                packaging = null,
                total = if (sharedTotal) sharedAmount else null,
                each = if (sharedEach) sharedAmount else null,
            )
            val primaries = buildList {
                add(0 to "one rice")
                add(1 to "organic mango one")
                if (includeLastPrimary) add(2 to "organic strawberry two")
            }
            return group to (instruction to primaries)
        }

        val (validGroup, validContext) = fixture()
        assertEquals("BrandA yogurt", validGroup.contextualAffirmedHeadingIdentity(validContext.first, validContext.second))
        val heading = requireNotNull(validGroup.contextualAffirmedHeading(validContext.first, validContext.second))
        assertEquals(
            GroceryIntentCoverageSpan(validContext.first.indexOf('.') + 1, validContext.first.indexOf("BrandA yogurt")),
            heading.transition,
        )
        listOf("then", "phir", "fir").forEach { transition ->
            val (group, context) = fixture(transition = transition)
            assertEquals(transition, "BrandA yogurt", group.contextualAffirmedHeadingIdentity(context.first, context.second))
        }

        listOf(
            fixture(headerText = "BrandA yogurt, Haan, "),
            fixture(headerText = "BrandA yogurt haan "),
            fixture(headerText = "BrandA yogurt, yaar, "),
            fixture(headerText = "BrandA 2 yogurt, haan, "),
            fixture(headerText = "BrandA yogurt if available, haan, "),
            fixture(headerText = "BrandA yogurt, haan, please "),
            fixture(transition = "BrandA"),
            fixture(transition = "", sentenceSeparator = "."),
            fixture(transition = "then", sentenceSeparator = ","),
            fixture(sharedEach = true),
            fixture(sharedTotal = true),
            fixture(localAmounts = false),
            fixture(forgedHeaderOffset = true),
            fixture(includeLastPrimary = false),
        ).forEach { (group, context) ->
            assertNull(context.first, group.contextualAffirmedHeadingIdentity(context.first, context.second))
        }
    }
}
