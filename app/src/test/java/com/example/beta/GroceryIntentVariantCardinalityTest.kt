package com.example.beta

import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentVariantCardinalityTest {
    @Test
    fun recognizes_variant_counts_without_hard_coding_three_or_a_brand() {
        listOf(
            "BrandA ke do flavour," to "BrandA",
            "BrandA ke teen flavour," to "BrandA",
            "three types of BrandA yoghurt:" to "BrandA yoghurt",
            "3 tarah ke BrandA yoghurt:" to "BrandA yoghurt",
            "BrandA yoghurt in three flavours" to "BrandA yoghurt",
            "three types of BrandB yoghurt:" to "BrandB yoghurt",
            "three types of yoghurt:" to "yoghurt",
            "BrandA yoghurt in two flavours" to "BrandA yoghurt",
            "BrandA yoghurt in twenty flavours" to "BrandA yoghurt",
            "Two Type of BrandA yoghurt" to "BrandA yoghurt",
        ).forEach { (header, identity) ->
            val proof = groceryIntentVariantCardinalityHeading(header)
            assertEquals(header, identity, proof?.identity)
            assertEquals(header, if (header.contains("do ") || header.contains("two ") || header.startsWith("Two Type")) 2 else if (header.contains("twenty ")) 20 else 3, proof?.count)
        }
    }

    @Test
    fun cardinality_is_not_a_purchase_quantity_and_local_member_amounts_stay_exact() {
        val instruction = "BrandA ke teen flavour, one strawberry yoghurt, two mango yoghurt, one vanilla yoghurt"
        val header = "BrandA ke teen flavour,"
        val rows = listOf(
            VariantItem("BrandA strawberry yoghurt", "one strawberry yoghurt", 1, quantityText = "one"),
            VariantItem("BrandA mango yoghurt", "two mango yoghurt", 2, quantityText = "two"),
            VariantItem("BrandA vanilla yoghurt", "one vanilla yoghurt", 1, quantityText = "one"),
        )
        val members = listOf(
            VariantMember(0, 1, "count", "local"), VariantMember(1, 2, "count", "local"), VariantMember(2, 1, "count", "local"),
        )
        val prepared = prepare(instruction, rows, listOf(group(
            instruction, instruction, header, members, null, amountStatus = "local_amounts_verified",
        )))

        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2), Quantity.Count(1)), prepared.items.map { it.quantity })
        assertEquals(listOf(header), prepared.sharedGroupNotes)
        assertEquals(1, prepared.sharedGroupNotes.size)
    }

    @Test
    fun rejects_unsupported_ambiguous_or_non_three_cardinality_headers() {
        listOf(
            "BrandA ke teen flavour and also buy two",
            "BrandA yoghurt in 3.5 flavours",
            "BrandA yoghurt in three flavours\nmore",
            "three types of BrandA yoghurt 2 extra",
            "BrandA ke teen flavour, four variants please",
        ).forEach { header ->
            assertNull(header, groceryIntentVariantCardinalityHeading(header))
        }
        listOf(
            "3 BrandA yoghurt in three flavours",
            "2 BrandA yoghurt ke teen flavour",
            "three types of 3 BrandA yoghurt",
            "three types of BrandA yoghurt 500 ml",
        ).forEach { header ->
            assertNull(header, groceryIntentVariantCardinalityHeading(header))
        }
        listOf("3 styles of yoghurt", "three formats of BrandA yoghurt").forEach { header ->
            assertNull(header, groceryIntentVariantCardinalityHeading(header))
        }
    }

    @Test
    fun numberless_variety_headings_keep_their_ordinary_group_path() {
        listOf("Mixed variety:", "BrandA in all flavours:", "BrandA different flavours:").forEach { header ->
            assertFalse(header, groceryIntentHasVariantCardinalityShape(header))
            val instruction = "$header one strawberry yoghurt, two mango yoghurt"
            val rows = listOf(
                VariantItem("BrandA strawberry yoghurt", "one strawberry yoghurt", 1, "one"),
                VariantItem("BrandA mango yoghurt", "two mango yoghurt", 2, "two"),
            )
            val members = listOf(VariantMember(0, 1, "count", "local"), VariantMember(1, 2, "count", "local"))
            parse(instruction, rows, group(
                instruction, instruction, header, members, null, amountStatus = "local_amounts_verified",
            ))
        }
    }

    @Test
    fun each_proof_assigns_one_per_member_without_turning_variant_count_into_quantity() {
        val header = "three types of BrandA yoghurt"
        val instruction = "$header: strawberry yoghurt, mango yoghurt, vanilla yoghurt, one each"
        val rows = listOf(
            VariantItem("BrandA strawberry yoghurt", "strawberry yoghurt"),
            VariantItem("BrandA mango yoghurt", "mango yoghurt"),
            VariantItem("BrandA vanilla yoghurt", "vanilla yoghurt"),
        )
        val members = rows.indices.map { VariantMember(it, 1, "count", "each") }
        val result = prepare(instruction, rows, listOf(group(
            instruction, instruction, header, members, null, amountStatus = "each_verified",
            each = VariantAmount("one each", "one", 1, "count"),
        )))

        assertEquals(listOf(Quantity.Count(1), Quantity.Count(1), Quantity.Count(1)), result.items.map { it.quantity })
        assertEquals(listOf("$header — one each"), result.sharedGroupNotes)
    }

    @Test
    fun variety_count_does_not_become_a_shared_total_even_when_local_quantities_sum_to_it() {
        val header = "BrandA ke teen flavour"
        val instruction = "$header, one strawberry yoghurt, two mango yoghurt"
        val rows = listOf(
            VariantItem("BrandA strawberry yoghurt", "one strawberry yoghurt", 1, "one"),
            VariantItem("BrandA mango yoghurt", "two mango yoghurt", 2, "two"),
        )
        val members = listOf(VariantMember(0, 1, "count", "local"), VariantMember(1, 2, "count", "local"))
        val falseTotal = VariantAmount(header, "teen", 3, "count")
        val group = group(instruction, instruction, header, members, null, falseTotal, "total_verified")

        assertThrows(IllegalArgumentException::class.java) {
            parse(instruction, rows, group)
        }
    }

    @Test
    fun bare_variety_count_heading_cannot_be_promoted_to_a_shared_total() {
        val header = "three flavours:"
        val instruction = "$header one strawberry yoghurt, two mango yoghurt"
        val rows = listOf(
            VariantItem("strawberry yoghurt", "one strawberry yoghurt", 1, "one"),
            VariantItem("mango yoghurt", "two mango yoghurt", 2, "two"),
        )
        val members = listOf(VariantMember(0, 1, "count", "local"), VariantMember(1, 2, "count", "local"))
        val misleadingTotal = VariantAmount(header, "three", 3, "count")

        assertThrows(IllegalArgumentException::class.java) {
            parse(instruction, rows, group(
                instruction, instruction, header, members, null, misleadingTotal, "total_verified",
            ))
        }
    }

    @Test
    fun local_quantity_prefixes_do_not_make_duplicate_variants_distinct() {
        val header = "two types of yoghurt:"
        val instruction = "$header one strawberry yoghurt, two strawberry yoghurt"
        val rows = listOf(
            VariantItem("strawberry yoghurt", "one strawberry yoghurt", 1, "one"),
            VariantItem("strawberry yoghurt", "two strawberry yoghurt", 2, "two"),
        )
        val members = listOf(VariantMember(0, 1, "count", "local"), VariantMember(1, 2, "count", "local"))

        assertThrows(IllegalArgumentException::class.java) {
            parse(instruction, rows, group(
                instruction, instruction, header, members, null, amountStatus = "local_amounts_verified",
            ))
        }
    }

    @Test
    fun cardinality_requires_all_members_and_explicit_quantities() {
        val header = "three types of BrandA yoghurt"
        val instruction = "$header: strawberry yoghurt, mango yoghurt, vanilla yoghurt"
        val rows = listOf(
            VariantItem("BrandA strawberry yoghurt", "strawberry yoghurt"),
            VariantItem("BrandA mango yoghurt", "mango yoghurt"),
            VariantItem("BrandA vanilla yoghurt", "vanilla yoghurt"),
        )
        val insufficientMembers = listOf(VariantMember(0, null, "unspecified", "not_stated"), VariantMember(1, null, "unspecified", "not_stated"))
        assertThrows(IllegalArgumentException::class.java) {
            parse(instruction, rows, group(instruction, instruction, header, insufficientMembers, null, amountStatus = "amounts_unspecified"))
        }

        val allMembersUnquantified = rows.indices.map { VariantMember(it, null, "unspecified", "not_stated") }
        assertThrows(IllegalArgumentException::class.java) {
            parse(instruction, rows, group(
                instruction, instruction, header, allMembersUnquantified, null, amountStatus = "amounts_unspecified",
            ))
        }
    }

    @Test
    fun preparation_does_not_drop_brand_or_diet_identity_and_rejects_forged_membership() {
        val header = "three types of BrandA dairy-free yoghurt"
        val instruction = "$header: one strawberry, one mango, one vanilla"
        val faithfulRows = listOf(
            VariantItem("BrandA dairy-free strawberry yoghurt", "one strawberry", 1, "one"),
            VariantItem("BrandA dairy-free mango yoghurt", "one mango", 1, "one"),
            VariantItem("BrandA dairy-free vanilla yoghurt", "one vanilla", 1, "one"),
        )
        val faithfulMembers = faithfulRows.indices.map { VariantMember(it, 1, "count", "local") }
        val faithful = prepare(instruction, faithfulRows, listOf(group(
            instruction, instruction, header, faithfulMembers, null, amountStatus = "local_amounts_verified",
        )))
        assertEquals(3, faithful.items.size)
        assertEquals(listOf(header), faithful.sharedGroupNotes)
        assertFalse(faithful.items.any { !it.query.contains("BrandA", ignoreCase = true) || !it.query.contains("dairy-free", ignoreCase = true) })

        val rows = listOf(
            VariantItem("BrandA strawberry yoghurt", "one strawberry", 1, "one"),
            VariantItem("BrandA mango yoghurt", "one mango", 1, "one"),
            VariantItem("BrandA vanilla yoghurt", "one vanilla", 1, "one"),
        )
        val members = rows.indices.map { VariantMember(it, 1, "count", "local") }
        val malformedGroup = group(instruction, instruction, header, members, null, null, "local_amounts_verified")
        val result = prepare(instruction, rows, listOf(malformedGroup))
        assertFalse(result.items.any { it.query.contains("BrandA", ignoreCase = true) && it.query.contains("dairy-free", ignoreCase = true) })
        assertEquals(listOf(instruction), result.excludedText)
    }

    @Test
    fun verified_flavour_cardinality_header_can_ground_its_literal_flavour_descriptor() {
        val header = "BrandA yoghurt in three flavours"
        val instruction = "$header: one strawberry yoghurt, one mango yoghurt, one vanilla yoghurt"
        val rows = listOf(
            VariantItem("BrandA strawberry flavour yoghurt", "one strawberry yoghurt", 1, "one"),
            VariantItem("BrandA mango flavour yoghurt", "one mango yoghurt", 1, "one"),
            VariantItem("BrandA vanilla flavour yoghurt", "one vanilla yoghurt", 1, "one"),
        )
        val members = rows.indices.map { VariantMember(it, 1, "count", "local") }

        val prepared = prepare(instruction, rows, listOf(group(
            instruction, instruction, header, members, null, amountStatus = "local_amounts_verified",
        )))

        assertEquals(3, prepared.items.size)
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(1), Quantity.Count(1)), prepared.items.map { it.quantity })
        assertTrue(prepared.items.all { it.query.contains("flavour", ignoreCase = true) })
    }

    @Test
    fun cardinality_descriptor_never_licenses_invented_roasting_or_sugar_free_claims() {
        val header = "BrandA yoghurt in three flavours"
        val instruction = "$header: one strawberry yoghurt, one mango yoghurt, one vanilla yoghurt"
        listOf(
            VariantItem("BrandA roasted strawberry flavour yoghurt", "one strawberry yoghurt", 1, "one"),
            VariantItem("BrandA strawberry flavour yoghurt", "one strawberry yoghurt", 1, "one", required = listOf("sugar_free")),
        ).forEach { invented ->
            val rows = listOf(
                invented,
                VariantItem("BrandA mango flavour yoghurt", "one mango yoghurt", 1, "one"),
                VariantItem("BrandA vanilla flavour yoghurt", "one vanilla yoghurt", 1, "one"),
            )
            val members = rows.indices.map { VariantMember(it, 1, "count", "local") }
            val prepared = prepare(instruction, rows, listOf(group(
                instruction, instruction, header, members, null, amountStatus = "local_amounts_verified",
            )))
            assertTrue(prepared.items.isEmpty())
            assertTrue(prepared.excludedText.isNotEmpty())
        }
    }

    @Test
    fun hindi_flavour_heading_with_one_each_keeps_literal_descriptor_in_child_queries() {
        val header = "BrandA ke teen flavour"
        val instruction = "$header, strawberry yoghurt, mango yoghurt, vanilla yoghurt, one each"
        val rows = listOf(
            VariantItem("BrandA strawberry flavour yoghurt", "strawberry yoghurt"),
            VariantItem("BrandA mango flavour yoghurt", "mango yoghurt"),
            VariantItem("BrandA vanilla flavour yoghurt", "vanilla yoghurt"),
        )
        val members = rows.indices.map { VariantMember(it, 1, "count", "each") }
        val prepared = prepare(instruction, rows, listOf(group(
            instruction, instruction, header, members, null, amountStatus = "each_verified",
            each = VariantAmount("one each", "one", 1, "count"),
        )))

        assertEquals(listOf(Quantity.Count(1), Quantity.Count(1), Quantity.Count(1)), prepared.items.map { it.quantity })
        assertTrue(prepared.items.all { it.query.contains("flavour", ignoreCase = true) })
    }

    @Test
    fun flavour_word_needs_eligible_flavour_header_and_matching_cardinality() {
        val flavourHeader = "BrandA yoghurt in three flavours"
        val typeHeader = "three types of BrandA yoghurt"
        val instruction = "$flavourHeader: one strawberry yoghurt, one mango yoghurt, one vanilla yoghurt"
        val rows = listOf(
            VariantItem("BrandA strawberry flavour yoghurt", "one strawberry yoghurt", 1, "one"),
            VariantItem("BrandA mango flavour yoghurt", "one mango yoghurt", 1, "one"),
            VariantItem("BrandA vanilla flavour yoghurt", "one vanilla yoghurt", 1, "one"),
        )
        val members = rows.indices.map { VariantMember(it, 1, "count", "local") }

        assertTrue(prepare(instruction, rows, emptyList()).items.isEmpty())

        val typeInstruction = "$typeHeader: one strawberry yoghurt, one mango yoghurt, one vanilla yoghurt"
        assertTrue(prepare(typeInstruction, rows, listOf(group(
            typeInstruction, typeInstruction, typeHeader, members, null, amountStatus = "local_amounts_verified",
        ))).items.isEmpty())

        val shortInstruction = "$flavourHeader: one strawberry yoghurt, one mango yoghurt"
        val shortRows = rows.take(2)
        val shortMembers = shortRows.indices.map { VariantMember(it, 1, "count", "local") }
        assertThrows(IllegalArgumentException::class.java) {
            parse(shortInstruction, shortRows, group(
                shortInstruction, shortInstruction, flavourHeader, shortMembers, null,
                amountStatus = "local_amounts_verified",
            ))
        }
    }
}

private data class VariantItem(
    val name: String,
    val source: String,
    val quantity: Int? = null,
    val quantityText: String? = quantity?.toString(),
    val packaging: String? = null,
    val required: List<String> = emptyList(),
)

private data class VariantMember(val rowIndex: Int, val value: Int?, val unit: String, val origin: String)
private data class VariantAmount(val source: String, val amountText: String, val value: Int, val unit: String)

private fun prepare(instruction: String, items: List<VariantItem>, groups: List<String>): GroceryIntentPreparation {
    val rows = items.mapIndexed { index, item ->
        val start = instruction.indexOf(item.source)
        require(start >= 0)
        val quantity = item.quantity?.toString() ?: "null"
        val unit = if (item.quantity == null) "unspecified" else "count"
        """{"row_index":$index,"item":{"name":${jsonString(item.name)},"quantity":$quantity,"quantity_unit":"$unit","pack_value":null,"pack_unit":"unspecified","packaging":${item.packaging?.let(::jsonString) ?: "null"},"required":[${item.required.joinToString(",") { jsonString(it) }}],"excluded":[],"source_text":${jsonString(item.source)},"supporting_text":[],"needs_clarification":false,"quantity_text":${item.quantityText?.let(::jsonString) ?: "null"},"pack_text":null},"evidence":{"primary":${quote(instruction, item.source, start)},"supporting":[]},"quantity_origin":"${if (item.quantity == null) "not_stated" else "stated"}","amount_role_status":"${if (item.quantity == null) "not_stated" else "matched"}"}"""
    }
    val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${jsonString(instruction)},"source_item_count":${rows.size},"ready_rows":[${rows.joinToString(",")}],"blocked_rows":[],"groups":[${groups.joinToString(",")}]},"model":"test","promptVersion":"test","latencyMs":1,"usage":{}}"""
    return GroceryIntentReviewV2Response.parse(body, instruction).draft.prepareForReview()
}

private fun group(
    instruction: String,
    source: String,
    header: String,
    members: List<VariantMember>,
    packaging: String?,
    total: VariantAmount? = null,
    amountStatus: String,
    each: VariantAmount? = null,
): String {
    val start = instruction.indexOf(header)
    val memberJson = members.joinToString(",") {
        """{"row_index":${it.rowIndex},"value":${it.value},"unit":"${it.unit}","origin":"${it.origin}"}"""
    }
    val totalJson = total?.let {
        """{"source":${quote(instruction, it.source, instruction.indexOf(it.source))},"amount_text":${jsonString(it.amountText)},"value":${it.value},"unit":"${it.unit}"}"""
    } ?: "null"
    val eachJson = each?.let {
        """{"source":${quote(instruction, it.source, instruction.indexOf(it.source))},"amount_text":${jsonString(it.amountText)},"value":${it.value},"unit":"${it.unit}"}"""
    } ?: "null"
    return """{"status":"structurally_verified","amount_status":"$amountStatus","source":${quote(instruction, source)},"header":${quote(instruction, header, start)},"member_indices":[${members.joinToString(",") { it.rowIndex.toString() }}],"members":[$memberJson],"packaging":${packaging?.let(::jsonString) ?: "null"},"total":$totalJson,"each":$eachJson}"""
}

private fun parse(instruction: String, items: List<VariantItem>, group: String) {
    val rows = items.mapIndexed { index, item ->
        val start = instruction.indexOf(item.source)
        val quantity = item.quantity?.toString() ?: "null"
        val quantityText = item.quantityText?.let(::jsonString) ?: "null"
        """{"row_index":$index,"item":{"name":${jsonString(item.name)},"quantity":$quantity,"quantity_unit":"${if (item.quantity == null) "unspecified" else "count"}","pack_value":null,"pack_unit":"unspecified","packaging":null,"required":[],"excluded":[],"source_text":${jsonString(item.source)},"supporting_text":[],"needs_clarification":false,"quantity_text":$quantityText,"pack_text":null},"evidence":{"primary":${quote(instruction, item.source, start)},"supporting":[]},"quantity_origin":"${if (item.quantity == null) "not_stated" else "stated"}","amount_role_status":"${if (item.quantity == null) "not_stated" else "matched"}"}"""
    }
    val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${jsonString(instruction)},"source_item_count":${rows.size},"ready_rows":[${rows.joinToString(",")}],"blocked_rows":[],"groups":[$group]},"model":"test","promptVersion":"test","latencyMs":1,"usage":{}}"""
    GroceryIntentReviewV2Response.parse(body, instruction)
}

private fun quote(instruction: String, text: String, start: Int = instruction.indexOf(text)): String {
    require(start >= 0 && instruction.substring(start, start + text.length) == text)
    return """{"text":${jsonString(text)},"start_utf16":$start,"end_utf16":${start + text.length}}"""
}

private fun jsonString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""
