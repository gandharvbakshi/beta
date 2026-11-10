package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.automation.IntentLocalAmount
import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Synthetic wire fixtures only: this exercises the OFF-default interpreted-review boundary. */
class GroceryIntentInterpretedReviewTest {
    @Test fun originalTamilAndKannadaMetricUnitsBindOneNounNotAnOrderOrPackRole() {
        for ((source, number) in listOf("250 ಗ್ರಾಂ oats" to "250", "௨௫௦ கிராம் oats" to "௨௫௦")) {
            val row = Row("oats", 250, "g", "physical_total", source = source, quantityText = number)
            val prepared = prepare(wireRows(source, listOf(row)))
            val item = prepared.items.single()
            val proof = requireNotNull(item.auditedIntent())
            assertEquals(Quantity.Weight(250), item.quantity)
            assertEquals("oats", proof.merchandise.merchantNoun)
            assertEquals(number, proof.amountAtoms().single().atom)
            assertEquals(source, proof.amountAtoms().single().source)
            for ((title, pack, expectedUnits) in listOf(Triple("Oats", "250 g", 1),
                Triple("Soap", "250 g", null), Triple("Oats", "400 g", null))) {
                val sku = candidate("shelf", title, sku = "item", variant = pack,
                    quantityFields = CatalogueQuantityFields(variantName = pack))
                val checked = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(sku)))
                val cart = SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to sku), emptySet(), prepared.auditedBasket)
                assertEquals("$source; $title; $pack", expectedUnits, cart?.requestedItems?.single()?.quantity)
            }
            fun blocked(instruction: String, claim: Row = row) = assertTrue(instruction,
                runCatching { prepare(wireRows(instruction, listOf(claim))).items.isNotEmpty() }.getOrDefault(false).not())
            blocked(source, row.copy(name = "soap"))
            blocked(source, row.copy(quantity = 500))
            blocked(source, row.copy(unit = "ml"))
            for (condition in listOf("ಬೇಡ", "இல்லை", "ಇದ್ದರೆ", "இருந்தால்")) {
                blocked("$condition $source")
                blocked("$source $condition")
            }
            val perPack = "2 packs of oats, $number " + source.substringAfter(' ').substringBefore(' ') + " each"
            blocked(perPack, row.copy(source = perPack))
            blocked(perPack, row.copy(source = "$number " + source.substringAfter(' ').substringBefore(' ')))
            blocked("2 packs: $source each")
        }
    }

    @Test
    fun plainInterpretedRowsRequestKeyedMerchandiseAndNeverReuseALegacyResponse() {
        val rows = listOf("pears", "oats").map { Row(it, null, "unspecified", "unstated", source = it) }
        val items = prepare(wireRows("pears; oats", rows)).items
        fun fields(source: List<com.example.beta.automation.ParsedItem>? = items, offset: Int? = null,
                   window: List<com.example.beta.automation.ParsedItem> = source ?: items) =
            SwiggyMcpClient.recommendationBatchFields("store", window.map(::swiggyRecommendationQuery),
                window.map { it.strictMatchPhrase }, window.map { it.ingredientProvenance }, source, sourceOffset = offset)
        val strict = fields()
        assertEquals(true, strict.body["includeMerchandiseEvidence"])
        assertFalse(strict.body.containsKey("sellerProperties"))
        assertEquals(listOf(emptyList<Any>(), emptyList<Any>()), strict.body["ingredientPredicates"])
        val window = fields(offset = 1, window = items.takeLast(1))
        assertEquals(true, window.body["includeMerchandiseEvidence"])
        val plain = fields(source = null)
        assertFalse(plain.body.containsKey("includeMerchandiseEvidence"))
        val native = items.map { com.example.beta.automation.ParsedItem(it.rawText, it.query, strictMatchPhrase = it.strictMatchPhrase) }
        assertFalse(fields(native).body.containsKey("includeMerchandiseEvidence"))
        for (invalid in listOf(items.reversed(), items.take(1), listOf(items[0], native[1]),
            listOf(items[0], items[1].copy(query = "nuts")))) assertThrows(IllegalArgumentException::class.java) { fields(invalid) }
        assertThrows(IllegalArgumentException::class.java) { fields(offset = 1, window = items.take(1)) }
        val row = """{"query":"oats","ingredientPredicates":[],"candidates":[]}"""
        val old = """{"ingredientPredicateVersion":1,"results":[$row]}"""
        assertTrue(plain.parse(old).single().candidates.isEmpty())
        val prefix = """"merchandiseEvidenceVersion":"""
        fun reply(value: String) = old.replaceFirst("{", "{$prefix$value,")
        assertTrue(window.parse(reply("1")).single().candidates.isEmpty())
        for (bad in listOf(old, reply("true"), reply("\"1\""), reply("1.0"), reply("1e0"), reply("null"),
            reply("2"), reply("1,${prefix}1"), old.replace("\"candidates\":[]", "${prefix}1,\"candidates\":[]")))
            assertThrows(IllegalArgumentException::class.java) { window.parse(bad) }
        // Empty batches cannot simulate a supported server either.
        assertThrows(IllegalArgumentException::class.java) {
            SwiggyMcpClient.parseRecommendationBatch("""{"results":[]}""", expectMerchandiseEvidence = true)
        }
        assertTrue(SwiggyMcpClient.parseRecommendationBatch("""{"merchandiseEvidenceVersion":1,"results":[]}""",
            expectMerchandiseEvidence = true).isEmpty())
        // Even a correct version is not SKU authority.
        val unproved = window.parse(reply("1").replace("\"candidates\":[]",
            """"candidates":[{"spinId":"spin","skuId":"sku","label":"Oats","price":50,"available":true}]""")).single()
        assertEquals(1, unproved.candidates.size)
        assertTrue(swiggyUsableCandidates(assessSwiggyIntentQuantity(items.last(), unproved), unproved).isEmpty())
    }

    @Test
    fun merchandiseAuthorityCannotUseArbitraryPropertyRangesCropTheSourceOrDiscardAnEnforcedRestriction() {
        fun quote(text: String) = GroceryIntentReviewV2Quote(text, 0, text.length)
        fun source(raw: String, noun: String, end: Int = raw.length, obligations: List<SourceMerchandiseProperty> = emptyList()) =
            SourceMerchandise.compile(raw, quote(raw), end, noun, "unstated", null, null, null,
                GroceryIntentInterpretedAmounts(raw), obligations)
        val raw = quote("unsalted butter")
        val word = quote("unsalted")
        val seller = requireNotNull(SellerPropertyRequirement.fromSource("butter", raw, listOf(word)))
        val property = requireNotNull(SourceMerchandiseProperty.fromSource(raw, word, "seller", emptySet(), seller))
        val bound = requireNotNull(source(raw.text, "butter", obligations = listOf(property)))
        assertTrue(bound.belongsTo(raw.text, emptyList(), seller))
        assertFalse(bound.belongsTo(raw.text, emptyList(), null))
        assertNull(source("green butter", "butter", obligations = listOf(property)))
        assertNull(source("butter soap", "butter", end = "butter".length))
        val green = quote("green butter")
        val unsupported = requireNotNull(SellerPropertyRequirement.fromSource("butter", green, listOf(quote("green"))))
        assertNull(SourceMerchandiseProperty.fromSource(green, quote("green"), "seller", emptySet(), unsupported))
    }

    private fun dry(original: String, name: String, label: String, rowText: String = original,
                    form: String? = "packet", size: Int? = null, offeredSize: Int = size ?: 250,
                    count: Int = 2, token: String = "2", role: String = "retail_units"): String? {
        val prep = try {
            prepare(wireRows(original, listOf(Row(name, count, "count", role, form,
                source = rowText, quantityText = token, packValue = size, packText = size?.toString(),
                packUnit = if (size == null) "unspecified" else "g"))))
        } catch (_: IllegalArgumentException) { return null }
        val offer = candidate("stock", label, sku = "sku-$label", quantityFields =
            if (role == "requested_pieces") CatalogueQuantityFields(variantName = "1 piece")
            else CatalogueQuantityFields(variantName = "$offeredSize g", quantityDescription = form?.let { "1 $it" }))
        val rec = SwiggyMcpClient.Recommendations(listOf(offer))
        val item = assessSwiggyIntentQuantity(prep.items.single(), rec)
        val selected = swiggyUsableCandidates(item, rec).takeIf { it.isNotEmpty() }?.let {
            swiggyDefaultSuggestion(item, it, rec.suggested)
        } ?: return null
        return SwiggyCartReviewRequest.build(listOf(item), mapOf(0 to selected), emptySet(), prep.auditedBasket)
            ?.requestedItems?.single()?.takeIf { it.quantity == count }?.skuId
    }

    @Test
    fun unmarkedPurchaseCountCannotAuthorizeAnUnrelatedPartialOrExtendedSubject() {
        for (role in listOf("requested_pieces", "retail_units")) {
            for ((text, proposed, seller) in listOf(Triple("2 apples", "oranges", "oranges"),
                Triple("2 green apples", "apples", "apples"), Triple("two green apples", "green apples soap", "green apples soap"),
                Triple("one nutmeg", "nutmeg powder", "nutmeg powder"), Triple("green apples 2", "apples", "apples"),
                Triple("bottle openers 2", "openers", "openers"))) {
                val word = when { text.startsWith("one") -> "one"; text.startsWith("two") -> "two"; else -> "2" }
                assertNull(text, dry(text, proposed, seller, form = null, count = if (word == "one") 1 else 2,
                    token = word, role = role))
            }
            for ((text, noun) in listOf("2 green apples" to "green apples", "green apples 2" to "green apples",
                "2 aloo" to "potato", "2 red aloo" to "red potato")) {
                assertEquals(text, "sku-$noun", dry(text, noun, noun, form = null, role = role))
            }
            for (tail in listOf(" and kumquats", ", 3 garlic", " if cheap", " flour")) {
                assertNull(tail, dry("2 green apples$tail", "green apples", "green apples", "2 green apples",
                    form = null, role = role))
            }
        }
        assertNull(dry("2 green apples", "green apples", "green apples", form = "packet"))
        assertThrows(IllegalArgumentException::class.java) { prepare(wireRows("2 green apples", listOf(
            Row("oranges", null, "unspecified", "unstated", source = "2 green apples", packText = "2",
                packValue = 2, packUnit = "piece")))) }
    }

    @Test
    fun markedRequestQuoteCutDoesNotChangeTheOriginalSubjectOrAmounts() {
        fun row(quote: String, name: String = "green apples") =
            if ("500" in quote) Row(name, 500, "g", "physical_total", source = quote, quantityText = "500")
            else Row(name, null, "unspecified", "unstated", source = quote)
        for (frame in listOf("please get ", "Kindly buy ", "Could you please bring ", "Would you pick up ")) {
            for (item in listOf("green apples", "500 g green apples", "green apples 500 g")) {
                val original = frame + item
                for (quoted in listOf(original, original.substringAfter(' '), item)) {
                    val parsed = prepare(wireRows(original, listOf(row(quoted)))).items.single()
                    assertEquals(original, "green apples", parsed.query)
                    assertEquals(quoted, parsed.rawText)
                    assertEquals(if ("500" in quoted) 1 else 0, requireNotNull(parsed.auditedIntent()).amountAtoms().size)
                }
            }
        }
        for ((original, quote, proposed) in listOf(
            Triple("please get green apples", "please get green apples", "apples"),
            Triple("please get green apples", "please get green apples", "oranges"),
            Triple("please get green apples", "please get green apples", "green apples soap"),
            Triple("please get green apples and barley", "please get green apples", "green apples"),
            Triple("if cheap, please get green apples", "please get green apples", "green apples"),
            Triple("please do not get green apples", "please do not get green apples", "green apples"),
            Triple("please get buy green apples", "please get buy green apples", "green apples"),
            Triple("please get green apples without salt", "please get green apples without salt", "green apples"),
            Triple("please get green apples 500 g each", "please get green apples 500 g each", "green apples"),
            Triple("please get green apples", "lease get green apples", "green apples"),
        )) assertThrows(original, IllegalArgumentException::class.java) { prepare(wireRows(original, listOf(row(quote, proposed)))) }
        val overlap = "Please Get green apples"
        for (property in listOf("Please Get", "Get", "Get green")) {
            val uncertainMaker = row(overlap).copy(requirements = listOf(Requirement(property, "seller")))
            assertThrows(property, IllegalArgumentException::class.java) { prepare(wireRows(overlap, listOf(uncertainMaker))) }
        }
    }

    @Test
    fun everyProjectedNameRequiresOriginalMerchandiseEvenWithoutAnOrdinaryCount() {
        fun bare(name: String, quote: String, vararg requirements: Requirement) =
            Row(name, null, "unspecified", "unstated", source = quote, requirements = requirements.toList())
        fun assertHeld(source: String, row: Row) {
            assertThrows(source, IllegalArgumentException::class.java) { prepare(wireRows(source, listOf(row))) }
        }
        assertHeld("dark chocolate", bare("oranges", "dark chocolate"))
        assertHeld("green apples", bare("apples", "green apples"))
        assertHeld("nutmeg", bare("nutmeg powder", "nutmeg"))
        assertHeld("please green apples", bare("oranges", "green apples"))
        assertHeld("two green apples", bare("apples", "apples"))
        assertHeld("two green apples", bare("green apples", "green apples"))
        assertHeld("green apples", bare("apples", "green apples", Requirement("green", "seller")))
        assertHeld("barley flour", bare("barley", "barley"))
        assertHeld("green apples", bare("apples", "apples"))
        assertHeld("500 g green lentils", Row("oranges", 500, "g", "physical_total", quantityText = "500"))
        assertHeld("500 g green lentils", Row("lentils", 500, "g", "physical_total", quantityText = "500"))
        for (noun in listOf("green apples", "dark chocolate", "nutmeg")) {
            val projected = prepare(wireRows(noun, listOf(bare(noun, noun)))).items.single()
            assertEquals(noun, projected.query)
        }
    }

    @Test
    fun countedRetailCannotShopAnotherNounOrDropAnOrderedQualifierEvenWhenBothModelsAgree() {
        assertNull(dry("2 packet barley", "doodh", "Doodh"))
        assertNull(dry("2 packet plain barley", "barley", "Barley"))
        for ((body, noun) in listOf("2 packets of barley" to "barley", "2 packets of plain barley" to "plain barley",
            "plain barley 2 packets" to "plain barley", "2 packet doodh" to "milk")) {
            val sku = "sku-$noun"
            assertEquals(body, sku, dry(body, noun, noun))
            assertEquals(body, sku, dry("$body chahiye", noun, noun))
            assertEquals(body, sku, dry("$body chahiye", noun, noun, body))
        }
        for (body in listOf("2 packets of plain barley", "plain barley 2 packets")) {
            assertNull(dry(body, "barley", "Barley"))
            assertNull(dry("$body chahiye", "barley", "Barley"))
        }
        assertNull(dry("2 packets of barley flour", "barley", "Barley", "2 packets of barley"))
        assertNull(dry("2 bottle openers", "openers", "Openers", form = "bottle"))
        assertNull(dry("2 red bottle openers", "openers", "Openers", form = "bottle"))
        val owned = "2 packets of barley"
        for (extra in listOf(" Rare herb.", " Murtava kernels.", " If cheap.", " Only 3.", " Try those.", " By dinner.")) {
            assertNull(dry("$owned.$extra", "barley", "Barley", owned))
        }
    }

    @Test
    fun measuredOuterReceiptMustStillOwnItsCompleteMerchandiseAtDryCart() {
        for (noun in listOf("plain barley", "green lentils")) {
            val head = noun.substringAfter(' ')
            for (source in listOf("2 200 g packets of $noun", "2 packets of 200 g $noun", "2 packets 200 g $noun")) {
                assertNull(source, dry(source, "doodh", "Doodh", size = 200))
                assertNull(source, dry(source, head, head, size = 200))
            }
            val exact = "2 200 g packets of $noun"
            assertEquals(exact, "sku-$noun", dry(exact, noun, noun, size = 200))
            assertNull(exact, dry(exact, noun, noun, size = 200, offeredSize = 250))
        }
    }

    @Test
    fun completePositiveRequestAtListEndKeepsTheOriginalRowsAndCannotHideAnotherItem() {
        val first = Row("coriander", 50, "g", "physical_total", source = "pachaas g coriander", quantityText = "pachaas")
        val last = Row("tea", 1, "kg", "physical_total", source = "one kg tea", quantityText = "one")
        fun prepared(join: String, tail: String, lastRow: Row = last) =
            prepare(wireRows(first.source + join + lastRow.source + tail, listOf(first, lastRow)))
        for ((join, tail) in listOf(" aur " to " chahiye.", ", " to " chaahiye!", " and " to " please.")) {
            val basket = prepared(join, tail)
            assertEquals(listOf(first.source, last.source), basket.items.map { it.rawText })
            assertEquals(listOf(Quantity.Weight(50), Quantity.Weight(1000)), basket.items.map { it.quantity })
        }
        val inside = prepared(" aur ", "", last.copy(source = "one kg tea chahiye."))
        assertEquals("one kg tea chahiye.", inside.items.last().rawText)
        assertEquals(Quantity.Weight(1000), inside.items.last().quantity)
        for (tail in listOf(" nahi chahiye.", " aur filter chahiye.", " if cheap chahiye.", "bags chahiye.")) {
            assertThrows(tail, IllegalArgumentException::class.java) { prepared(" aur ", tail) }
        }
        for (crop in listOf("one kg tea nahi", "one kg tea aur filter", "one kg tea only if cheap", "one kg tea filter")) {
            assertThrows(crop, IllegalArgumentException::class.java) { prepared(" aur ", " chahiye.", last.copy(source = crop)) }
            assertThrows(crop, IllegalArgumentException::class.java) { prepared(" aur ", "", last.copy(source = "$crop chahiye.")) }
        }
        assertThrows(IllegalArgumentException::class.java) { prepared(", mystery item, ", " chahiye.") }
        assertThrows(IllegalArgumentException::class.java) { prepared(" aur ", " chahiye.", last.copy(auditIssues = listOf("binding"))) }
    }

    @Test
    fun pluralOuterSizeCannotBorrowAuthorityFromPunctuationOrWordOrder() {
        val row = Row("milk", 2, "count", "retail_units", "bottle",
            quantityText = "2", packText = "100", packValue = 100, packUnit = "ml")
        for (source in listOf("2 bottles of milk 100 ml", "2 bottles of milk; 100 ml",
            "2 bottles of milk (100 ml)", "milk 100 ml, 2 bottles", "milk 100 ml 2 bottles",
            "2 bottles of milk per 100 ml", "milk 2 bottles 100 ml altogether",
            "2 bottles of 100 ml milk", "2 bottles of milk of 100 ml",
            "2 bottles of milk, up to 100 ml per bottle")) {
            assertThrows(source, IllegalArgumentException::class.java) { prepare(wireRows(source, listOf(row))) }
        }
        for (source in listOf("2 bottles of milk, 100 ml each", "2 bottles of milk 100 ml each",
            "milk 2 bottles 100 ml per bottle", "2 milk bottles 100 ml each", "2 100 ml bottles of milk")) {
            assertEquals(source, Quantity.Count(2), prepare(wireRows(source, listOf(row))).items.single().quantity)
        }
        val conventional = Row("paneer", 2, "count", "retail_units", "block", quantityText = "two",
            packText = "200", packValue = 200, packUnit = "g")
        assertEquals(Quantity.Count(2),
            prepare(wireRows("two 200g blocks of paneer", listOf(conventional))).items.single().quantity)
    }

    @Test
    fun terminalNamedRetailMeasureKeepsBothOriginalAmountsAndThePurchasedOuter() {
        fun check(text: String, name: String, word: String, count: Int, form: String, amount: Int, unit: String) {
            val row = Row(name, count, "count", "retail_units", form,
                quantityText = word, packValue = amount, packUnit = unit, packText = amount.toString())
            val item = prepare(wireRows(text, listOf(row))).items.single()
            assertEquals(text, Quantity.Count(count), item.quantity)
            assertTrue(text, item.retailPackCount)
            val receipt = item.auditedIntent()!!
            assertEquals(text, form, receipt.packaging)
            assertEquals(text, listOf(word, amount.toString()), receipt.amountAtoms().map { it.atom })
            for (atom in receipt.amountAtoms()) assertEquals(text.indexOf(atom.atom), atom.startUtf16)
            assertEquals(text, unit, receipt.packAtom!!.unit)
        }
        check("rice flour ondu packet, 500 g.", "rice flour", "ondu", 1, "packet", 500, "g")
        check("Rose water eradu bottles, 100 ml each.", "Rose water", "eradu", 2, "bottle", 100, "ml")
        check("tamarind ondu packet, 200 g.", "tamarind", "ondu", 1, "packet", 200, "g")
        check("one jar of jam, 200 g", "jam", "one", 1, "jar", 200, "g")
        check("two bottles of rose water, 100 ml each", "rose water", "two", 2, "bottle", 100, "ml")
        check("dish soap two pouches, 125 ml per pouch", "dish soap", "two", 2, "pouch", 125, "ml")
    }

    @Test
    fun terminalNamedRetailMeasureDoesNotInventEachOrBorrowAnotherItemsSize() {
        val row = Row("rose water", 2, "count", "retail_units", "bottle", quantityText = "two",
            packValue = 100, packUnit = "ml", packText = "100")
        fun reject(text: String, proposed: Row = row) {
            assertThrows(text, IllegalArgumentException::class.java) { prepare(wireRows(text, listOf(proposed))) }
        }
        for (text in listOf("rose water two bottles, 100 ml", "two bottles of rose water, 100 ml",
            "two bottles of rose water, 100 ml total", "rose water two bottles, 100 ml between both",
            "rose water two bottles, 100 ml per serving", "rose water two bottles, 100 ml per carton",
            "rose water two bottles, 100 ml per bottles", "rose water two bottles, 100 ml each or 125 ml",
            "rose water two bottles, under 100 ml each", "rose water two bottles, 100 ml protein each",
            "rose water two bottles, 100 ml each for 3 people")) reject(text)
        val good = "rose water two bottles, 100 ml each"
        reject(good, row.copy(packaging = "jar"))
        reject(good, row.copy(role = "requested_pieces"))
        reject(good, row.copy(name = "perfume"))
        reject("$good only if available", row.copy(source = good))
        val previous = Row("jam", 1, "count", "retail_units", "jar", source = "one jar of jam", quantityText = "one")
        val shared = "one jar of jam and $good"
        assertThrows(shared, IllegalArgumentException::class.java) {
            prepare(wireRows(shared, listOf(previous, row.copy(source = good))))
        }
    }

    @Test
    fun scopedCardinalsKeepLiteralAmountDimensionsAndNamedOuterRole() {
        fun check(source: String, row: Row, expected: Quantity) {
            val item = prepare(wireRows(source, listOf(row))).items.single()
            assertEquals(source, expected, item.quantity)
            val bound = item.auditedIntent()!!.amountAtoms().first()
            assertEquals(row.quantityText, bound.atom)
            assertEquals(source.indexOf(bound.atom), bound.startUtf16)
            assertEquals(row.role == "retail_units", item.retailPackCount)
        }
        for ((word, value) in mapOf("bees" to 20, "tees" to 30, "chalis" to 40, "chaalis" to 40,
            "pachas" to 50, "pachaas" to 50, "assi" to 80, "nabbe" to 90)) {
            check("Daalchini powder $word gram", Row("Daalchini powder", value, "g", "physical_total", quantityText = word), Quantity.Weight(value))
            assertNull(parseIntentCorrectionAmount(word)) // never a global fallback for pieces or packs
        }
        check("pachaas gram turmeric", Row("turmeric", 50, "g", "physical_total", quantityText = "pachaas"), Quantity.Weight(50))
        check("ondu litre milk", Row("milk", 1, "l", "physical_total", quantityText = "ondu"), Quantity.Volume(1000))
        check("eradu bottles of milk", Row("milk", 2, "count", "retail_units", "bottle", quantityText = "eradu"), Quantity.Count(2))
        check("juice eradu bottles", Row("juice", 2, "pack", "retail_units", "bottle", quantityText = "eradu"), Quantity.Count(2))
        check("salt crackers 150 g ond packet", Row("salt crackers", 1, "count", "retail_units", "packet",
            quantityText = "ond", packValue = 150, packUnit = "g", packText = "150"), Quantity.Count(1))
    }

    @Test
    fun scopedCardinalsCannotBeDroppedConvertedOrBorrowed() {
        fun rejected(original: String, row: Row) {
            assertThrows(original, IllegalArgumentException::class.java) { prepare(wireRows(original, listOf(row))) }
        }
        val metric = Row("rice", 50, "g", "physical_total", quantityText = "pachaas")
        for (source in listOf("rice with pachaas g protein", "pachaas g per serving rice", "rice under pachaas g",
            "rice pachaas grams per litre", "rice sawa pachaas g", "rice pachaas-gram", "rice pachaas a gram",
            "rice pachaas\ngram", "rice pachaas ml", "rice pachaas g to nabbe g")) rejected(source, metric)
        rejected("under pachaas g rice", metric.copy(source = "pachaas g rice"))
        rejected("pachaas g rice with protein", metric.copy(source = "pachaas g rice"))
        rejected("rice pachaas g", metric.copy(quantity = 5))
        rejected("rice pachaas g", metric.copy(quantity = null, unit = "unspecified", role = "unstated",
            quantityText = null, packValue = 50, packUnit = "g", packText = "pachaas"))
        val retail = Row("milk", 2, "count", "retail_units", "bottle", quantityText = "eradu")
        rejected("eradu bottles of milk", retail.copy(role = "requested_pieces"))
        rejected("eradu bottles of milk", retail.copy(packaging = "jar"))
        rejected("eradu bottle caps", retail.copy(name = "caps"))
        rejected("milk eradu kilograms", retail)
        rejected("milk eradu metres", retail)
        rejected("milk eradu bottles", retail.copy(quantity = null, unit = "unspecified", role = "unstated", quantityText = null))
        rejected("eradu bottles of milk", Row("milk", null, "unspecified", "unstated", source = "milk"))
        rejected("bees wax", Row("wax", 20, "piece", "requested_pieces", quantityText = "bees"))
        rejected("pachaas bottles of milk", retail.copy(quantity = 50, quantityText = "pachaas"))
        assertFalse(ScopedInterpretedCardinal.hasUnboundWord("pond condenser"))
    }

    @Test fun nativeCardinalsKeepTheirExactSourceNounFormPositionAndMetric() {
        fun proof(source:String, row:Row) = prepare(wireRows(source,listOf(row))).items.single()
        for ((word,value) in listOf("ஒரு" to 1,"ஒன்று" to 1,"இரண்டு" to 2,"மூன்று" to 3,"நான்கு" to 4,
            "ஐந்து" to 5,"ஆறு" to 6,"ஏழு" to 7,"எட்டு" to 8,"ஒன்பது" to 9,"பத்து" to 10,
            "ಒಂದು" to 1,"ಎರಡು" to 2,"ಮೂರು" to 3,"ನಾಲ್ಕು" to 4,"ಐದು" to 5,"ಆರು" to 6,
            "ಏಳು" to 7,"ಎಂಟು" to 8,"ಒಂಬತ್ತು" to 9,"ಹತ್ತು" to 10)) {
            val unit = if (word.first().code in 0x0c80..0x0cff) "ಕಿಲೋ" else "கிலோ"
            val item = proof("$word $unit oats",Row("oats",value,"kg","physical_total",quantityText=word))
            assertEquals(Quantity.Weight(value*1000),item.quantity)
            assertEquals("oats",item.auditedIntent()?.merchandise?.merchantNoun)
            assertNull(parseIntentCorrectionAmount(word))
        }
        for ((source,word,form) in listOf(Triple("oats 500 கிராம் ஒரு pack","ஒரு","pack"),
            Triple("oats 500 ಗ್ರಾಂ ಪ್ಯಾಕ್ ಒಂದು","ಒಂದು","pack"), Triple("oats 500 ಗ್ರಾಂ ಒಂದು ಪೌಚ್","ಒಂದು","pouch"),
            Triple("oats 500 ಗ್ರಾಂ ಚೀಲ ಒಂದು","ಒಂದು","bag"),
            Triple("oats 500 ಗ್ರಾಂ ಕಪ್ ಒಂದು","ಒಂದು","cup"), Triple("oats 500 கிராம் பாக்கெட் ஒன்று","ஒன்று","packet"),
            Triple("oats 500 கிராம் ஒரு பை","ஒரு","bag"), Triple("oats 500 g packet ஒன்று","ஒன்று","packet"))) {
            val item=proof(source,Row("oats",1,"pack","retail_units",form,quantityText=word,packValue=500,packUnit="g",packText="500"))
            assertEquals(source,Quantity.Count(1),item.quantity)
            assertEquals(source,"oats",item.auditedIntent()?.merchandise?.merchantNoun)
        }
        val nakedHolds = mutableListOf<String>()
        for ((source,word,form) in listOf(Triple("oats packets இரண்டு","இரண்டு","packet"),
            Triple("oats ಎರಡು packets","ಎರಡು","packet"), Triple("oats ಪ್ಯಾಕ್ ಎರಡು","ಎರಡು","pack"),
            Triple("oats ಎರಡು ಪ್ಯಾಕ್","ಎರಡು","pack"))) {
            val prepared=GroceryIntentInterpretedReview.parse(wireRows(source,listOf(
                Row("oats",2,"pack","retail_units",form,quantityText=word))),source)
            if (prepared.holdReasons.isNotEmpty()) nakedHolds += "$source: ${prepared.holdReasons.map { it.code }}"
            else assertEquals(source,Quantity.Count(2),prepared.prepareForReview().items.single().quantity)
        }
        val physical=prepare(wireRows("ಎರಡು ಕಿಲೋ oats",listOf(
            Row("oats",2,"kg","physical_total",quantityText="ಎರಡು"))))
        assertSkuReview(physical,variation("Oats","500 g"),4)
        assertSkuReview(physical,variation("Oats","750 g"),null)
        assertTrue(nakedHolds.joinToString("\n"),nakedHolds.isEmpty())
    }

    @Test fun sourceValidatedNativePackCountsSurviveCatalogueTitleCheckAndPriceTheCorrectTwoSkuReview() {
        val rows = listOf("Toor dal" to 500, "green beans" to 250).map { (name, grams) ->
            Row(name, 1, "count", "retail_units", "pack", source = "$name $grams ಗ್ರಾಂ ಒಂದು ಪ್ಯಾಕ್",
                quantityText = "ಒಂದು", packValue = grams, packUnit = "g", packText = "$grams")
        }
        val prep = prepare(wireRows(rows.joinToString(", ") { it.source }, rows))
        // The synthetic merchant supplies both facts on the identical variation. The price
        // lookup below uses the selected variation's keys, never the shopper's query/name.
        val stock = rows.mapIndexed { i, r ->
            val size = "${r.packValue} g"
            val variant = "1 pack · $size"
            candidate("native-$i", r.name, variant = variant, sku = "stock-$i",
                quantityFields = CatalogueQuantityFields(variantName = variant, packSize = size))
        }
        val unitPrices = mapOf((stock[0].spinId to stock[0].skuId) to 88, (stock[1].spinId to stock[1].skuId) to 34)
        val recs = stock.map { SwiggyMcpClient.Recommendations(listOf(it)) }
        val checked = prep.items.mapIndexed { i, item -> assessSwiggyIntentQuantity(item, recs[i]) }
        val selected = checked.mapIndexed { i, item ->
            val usable = swiggyUsableCandidates(item, recs[i])
            assertEquals("Expected one quantity-checked seller variation for row $i", 1, usable.size)
            i to usable.single()
        }.toMap()
        val basket = requireNotNull(SwiggyCartReviewRequest.build(checked, selected, emptySet(), prep.auditedBasket))
        assertEquals(listOf("stock-0" to 1, "stock-1" to 1), basket.requestedItems.map { it.skuId to it.quantity })
        assertEquals(122, basket.requestedItems.sumOf { it.quantity * unitPrices.getValue(it.spinId to it.skuId) })

        // Comparison-only path: a native word as an actual adjacent number in a *product title*
        // still vetoes a forced selection. It cannot be treated as another spelling of "buy one".
        val conflicted = candidate("brand", "ಒಂದು ಪ್ಯಾಕ್ Toor dal", sku = "numeric-title",
            quantityFields = CatalogueQuantityFields(packSize = "500 g", quantityDescription = "1 pack"))
        val wrong = assessSwiggyIntentQuantity(prep.items[0], SwiggyMcpClient.Recommendations(listOf(conflicted)))
        assertEquals("numeric_product_quantity_ambiguous", wrong.intentQuantityCatalogueCheck?.issue)
        assertNull(SwiggyCartReviewRequest.build(listOf(wrong, checked[1]), mapOf(0 to conflicted, 1 to stock[1]),
            emptySet(), prep.auditedBasket))
    }

    @Test fun privateNativeAtomCannotBeForgedCroppedOrHandedToTheLegacyQuantityPath() {
        val source = "Toor dal 500 ಗ್ರಾಂ ಒಂದು ಪ್ಯಾಕ್"
        val row = Row("Toor dal", 1, "count", "retail_units", "pack", source = source, quantityText = "ಒಂದು",
            packValue = 500, packUnit = "g", packText = "500")
        val prep = prepare(wireRows(source, listOf(row)))
        val item = prep.items.single()
        val p = requireNotNull(item.intentQuantityProvenance)
        val atom = requireNotNull(item.auditedIntent()).amountAtoms().first()
        val offer = candidate("native", row.name, sku = "sku",
            quantityFields = CatalogueQuantityFields(packSize = "500 g", quantityDescription = "1 pack"))
        for (invalid in listOf(p.copy(local = atom.copy(startUtf16 = atom.startUtf16 + 1)),
            p.copy(local = atom.copy(value = java.math.BigDecimal.TEN)), p.copy(audited = null))) {
            val changed = item.copy(intentQuantityProvenance = invalid)
            val rec = SwiggyMcpClient.Recommendations(listOf(offer))
            val checked = assessSwiggyIntentQuantity(changed, rec)
            assertTrue(swiggyUsableCandidates(checked, rec).isEmpty())
            assertNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to offer), emptySet(), prep.auditedBasket))
        }
        assertThrows(IllegalArgumentException::class.java) {
            intentQuantityIdentityConflict(source, atom.startUtf16, atom.endUtf16, atom.value, atom.unit, row.name)
        }
    }

    @Test fun packedFoodCutIsARequiredPartOfTheNameNeverThePurchaseCount() {
        fun row(name: String, literal: String = name, suffix: String = "100 கிராம் ஒரு pack",
                properties: List<String> = listOf(literal.substringAfterLast(' '))) =
            Row(name, 1, "count", "retail_units", "pack", source="$literal $suffix", quantityText="ஒரு",
                packValue=100, packUnit="g", packText="100", requirements=properties.map { Requirement(it,"seller") })
        fun project(r: Row) = prepare(wireRows(r.source,listOf(r)))
        for (full in listOf("cheese slices", "mango pieces")) {
            val p = project(row(full))
            val item = p.items.single()
            assertEquals(Quantity.Count(1), item.quantity)
            assertEquals(full, item.auditedIntent()!!.merchandise.merchantNoun)
            assertEquals(listOf(full.substringAfterLast(' ')), validatedSellerPropertyRequirement(item)!!.requirements.map { it.text })
            // An accepted source is not an accepted SKU: no independent seller attestation.
            assertSkuReview(p, variation(full, "1 pack · 100 g"), null)
            assertSkuReview(p, variation(full.substringBeforeLast(' '), "1 pack · 100 g"), null)
        }
        for (bad in listOf(row("cheese slices", properties=emptyList()), row("cheese", literal="cheese slices"),
            row("cheese slices", properties=listOf("pack")), row("protein pieces"),
            row("cheese slices", suffix="100 கிராம் per serving ஒரு pack"),
            row("mango pieces", suffix="100 கிராம் each ஒரு pack"))) {
            val accepted = runCatching { project(bad).items.isNotEmpty() }.getOrDefault(false)
            assertFalse(bad.source, accepted)
        }
    }

    @Test fun privateNativeCardinalsNeverLicenseCroppedCompoundsOmittedAmountsOrChangedForm() {
        fun denied(source:String,row:Row) = assertThrows(source,IllegalArgumentException::class.java) {
            prepare(wireRows(source,listOf(row)))
        }
        for ((prefix,word,unit) in listOf(Triple("இருபத்து ","ஐந்து","கிராம்"),Triple("ಇಪ್ಪತ್ತು ","ಐದು","ಗ್ರಾಂ"))) {
            val short = "$word $unit oats"
            val row = Row("oats",5,"g","physical_total",quantityText=word)
            denied(prefix+short,row.copy(source=short)); denied(prefix+short,row)
            for (bad in listOf("x$short","x\u200d$short",word+"\u0301 $unit oats",word+"\u200c $unit oats"))
                denied(bad,row)
            denied("if fresh, $short",row.copy(source=short))
        }
        val source="oats 500 ಗ್ರಾಂ ಪ್ಯಾಕ್ ಒಂದು"
        val row=Row("oats",1,"pack","retail_units","pack",quantityText="ಒಂದು",packValue=500,packUnit="g",packText="500")
        denied(source,row.copy(name="rice")); denied(source,row.copy(packaging="cup"))
        denied(source,row.copy(packValue=null,packUnit="unspecified",packText=null))
        denied(source+" rice",row); denied(source+" ಮಾತ್ರ",row.copy(source=source))
        denied(source.replace("ಪ್ಯಾಕ್","ಕಪ್"),row)
        denied(source.replace("ಪ್ಯಾಕ್","ಪ್ಯಾಕ್\u200d"),row)
        denied(source.replace("ಒಂದು","ಒಂದೇ"),row)
        denied(source.replace("oats","ಓಟ್ಸ್"),row) // no unproved translation
        denied("oats 500 ಗ್ರಾಂ ಎರಡು packs",row.copy(quantity=2,quantityText="ಎರಡು"))
        denied("ಎರಡು ಕಿಲೋ rice bowl",Row("rice",2,"kg","physical_total",quantityText="ಎರಡು"))
        denied("ಎರಡು ಕಿಲೋ water bottle",Row("water bottle",2,"kg","physical_total",quantityText="ಎರಡು"))
        denied("ಎರಡು ಕಿಲೋ unsalted oats",Row("oats",2,"kg","physical_total",quantityText="ಎರಡು"))
        denied("oats ನಾಲ್ಕು apples in bag",Row("oats",4,"count","retail_units","bag",quantityText="ನಾಲ್ಕು"))
    }

    @Test fun wholeGroceryNounCanBeMeasuredButNotBorrowedFromNutritionOrDietText() {
        val failures = mutableListOf<String>()
        for (noun in listOf("sugar","salt")) {
            val source="$noun 500 கிராம் ஒரு pack"
            val row=Row(noun,1,"pack","retail_units","pack",quantityText="ஒரு",packValue=500,packUnit="g",packText="500")
            val parsed=GroceryIntentInterpretedReview.parse(wireRows(source,listOf(row)),source)
            if (parsed.holdReasons.isNotEmpty()) failures += "$source: ${parsed.holdReasons.map { it.code }}"
            else assertEquals(noun,parsed.prepareForReview().items.single().auditedIntent()?.merchandise?.merchantNoun)
            for (invalid in listOf("without $source","oats contains $source","no added $source",
                "per serving $source","$source each serving","$source with milk")) {
                assertThrows(invalid,IllegalArgumentException::class.java) { prepare(wireRows(invalid,listOf(row))) }
            }
            assertThrows(IllegalArgumentException::class.java) { prepare(wireRows(source,listOf(row.copy(name="oats")))) }
        }
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }

    @Test
    fun neutralCountPackagingMustBeOwnedByCompleteOriginalSource() {
        data class Case(val source: String, val product: String, val canonical: String)
        val cases = listOf(
            Case("two boxes of tissues", "tissues", "box"),
            Case("three loaves of bread", "bread", "loaf"),
            Case("two bars of sandalwood soap", "sandalwood soap", "bar"),
        )
        fun row(case: Case, role: String, packaging: String?) = Row(
            case.product, if (case.source.startsWith("three")) 3 else 2, "count", role, packaging,
            source = case.source, quantityText = case.source.substringBefore(' '),
        )
        fun rejected(case: Case, role: String, packaging: String?) {
            val failure = assertThrows(IntentNeedsReviewException::class.java) {
                prepare(wireRows(case.source, listOf(row(case, role, packaging))))
            }
            assertTrue(failure.affectedSourceText.contains(case.source))
        }

        for (case in cases) {
            // A boxed/loaf/bar count is a requested retail unit, not loose pieces.
            rejected(case, "requested_pieces", case.canonical)
            rejected(case, "retail_units", null)
            rejected(case, "retail_units", when (case.canonical) {
                "box" -> "bar"
                "loaf" -> "box"
                else -> "loaf"
            })

            val prepared = prepare(wireRows(case.source, listOf(row(case, "retail_units", case.canonical))))
            val item = prepared.items.single()
            assertEquals(case.source, item.rawText)
            assertTrue(item.retailPackCount)
            assertEquals(Quantity.Count(if (case.source.startsWith("three")) 3 else 2), item.quantity)
            val amount = item.auditedIntent()!!.amountAtoms().single()
            val amountStart = case.source.indexOf(case.source.substringBefore(' '))
            assertEquals(case.source.substringBefore(' '), amount.atom)
            assertEquals(amountStart, amount.startUtf16)
            assertEquals(amountStart + amount.atom.length, amount.endUtf16)
        }

        // Without a named selling unit the ordinary individual count still works.
        val pears = prepare(wireRows("two pears", listOf(
            Row("pears", 2, "count", "requested_pieces", source = "two pears", quantityText = "two"),
        )))
        assertEquals("two pears", pears.items.single().rawText)
        assertEquals(Quantity.Count(2), pears.items.single().quantity)
        assertFalse(pears.items.single().retailPackCount)
    }

    @Test
    fun decimalDigitScriptsRetainOriginalAtomsAndExactPhysicalTotals() {
        for ((source, value, unit) in listOf(
            Triple("१ किलो चावल", 1, "kg"), Triple("२०० ग्राम पनीर", 200, "g"),
            Triple("২০০ g flour", 200, "g"), Triple("٢٠٠ g rice", 200, "g"),
            Triple("𝟚 g flour", 2, "g"),
        )) {
            val noun = source.substringAfterLast(' ')
            val preparation = prepare(wireRows(source, listOf(Row(noun, value, unit, "physical_total"))))
            if (noun != "flour") assertThrows(IllegalArgumentException::class.java) {
                prepare(wireRows(source, listOf(Row("flour", value, unit, "physical_total"))))
            }
            assertEquals(source, preparation.items.single().rawText)
            val atom = preparation.items.single().auditedIntent()!!.amountAtoms().single()
            assertEquals(source.substringBefore(' '), atom.atom)
            assertEquals(0, atom.startUtf16)
            assertEquals(source.substringBefore(' ').length, atom.endUtf16)
        }
    }

    @Test
    fun decimalDigitsCannotHideUnownedRangeCorrectionOrAmountComponents() {
        for ((source, atom, value) in listOf(
            Triple("१-२ kg rice", "२", 2), Triple("१ and २ kg rice", "२", 2),
            Triple("१ १/२ kg rice", "२", 2), Triple("१२.५ g rice", "५", 5),
            Triple("१,२०० g rice", "२००", 200), Triple("१ kg rice, actually २ kg", "२", 2),
        )) {
            assertTrue(source, runCatching { prepare(wireRows(source,
                listOf(Row("rice", value, if (source.contains("kg")) "kg" else "g", "physical_total", quantityText = atom)))) }.isFailure)
        }
    }

    @Test
    fun nativeMultiplierBeforePhysicalAmountKeepsTheAmountUnbound() {
        for (prefix in listOf("हजार", "हज़ार", "लाख", "करोड़", "दर्जन")) {
            val source = "$prefix एक किलो चावल"
            assertNull(prefix, GroceryIntentInterpretedAmounts(source).bind(java.math.BigDecimal.ONE, "kg"))
            assertTrue(prefix, runCatching {
                prepare(wireRows(source, listOf(Row(
                    "चावल", 1, "kg", "physical_total", source = source, quantityText = "एक",
                ))))
            }.isFailure)
        }
    }

    @Test
    fun retailCountAndPackSizeBindToExactAmountsWithoutParsingComplexProductNames() {
        val cases = listOf(
            Triple("a 250 g Haldiram's bhujia", "Haldiram's bhujia", "a" to "250"),
            Triple("एक २०० ग्राम मक्खन का पैक", "मक्खन", "एक" to "२००"),
            Triple("one 200 g cafe\u0301 crème pack", "cafe\u0301 crème", "one" to "200"),
        )
        for ((source, name, atoms) in cases) {
            val (quantityText, packText) = atoms
            val packValue = if (packText == "250") 250 else 200
            val item = runCatching { prepare(wireRows(source, listOf(Row(
                name, 1, "count", "retail_units", source = source, quantityText = quantityText,
                packValue = packValue, packUnit = "g", packText = packText,
            )))).items.single() }.getOrElse { throw AssertionError(source, it) }

            assertEquals(source, item.rawText)
            assertEquals(Quantity.Count(1), item.quantity)
            assertTrue(item.retailPackCount)
            assertEquals(listOf(quantityText, packText), item.auditedIntent()!!.amountAtoms().map { it.atom })
            val quantityStart = source.indexOf(quantityText)
            val packStart = source.indexOf(packText, quantityStart + quantityText.length)
            val boundAtoms = item.auditedIntent()!!.amountAtoms()
            assertEquals(quantityStart, boundAtoms[0].startUtf16)
            assertEquals(quantityStart + quantityText.length, boundAtoms[0].endUtf16)
            assertEquals(packStart, boundAtoms[1].startUtf16)
            assertEquals(packStart + packText.length, boundAtoms[1].endUtf16)
            assertEquals(quantityText, source.substring(boundAtoms[0].startUtf16, boundAtoms[0].endUtf16))
            assertEquals(packText, source.substring(boundAtoms[1].startUtf16, boundAtoms[1].endUtf16))
        }
    }

    @Test
    fun sameNumericCountAndPhysicalSizeBindToDifferentOriginalOccurrences() {
        data class Case(
            val source: String,
            val product: String,
            val packageForm: String,
            val quantityOccurrence: Int,
            val packOccurrence: Int,
        )
        val cases = listOf(
            Case("1 bottle 1 litre milk", "milk", "bottle", 0, 1),
            Case("one bottle one litre milk", "milk", "bottle", 0, 1),
            Case("1 bag 1 kg rice", "rice", "bag", 0, 1),
            Case("1 litre milk packed in 1 bottle", "milk", "bottle", 1, 0),
            Case("🧺 1 bottle 1 litre milk", "milk", "bottle", 0, 1),
        )
        for (case in cases) {
            val positions = Regex("(?:one|1)").findAll(case.source).map { it.range.first }.toList()
            val quantityStart = positions[case.quantityOccurrence]
            val packStart = positions[case.packOccurrence]
            val quantityText = case.source.substring(quantityStart, quantityStart + if (case.source.startsWith("one", quantityStart)) 3 else 1)
            val packText = case.source.substring(packStart, packStart + if (case.source.startsWith("one", packStart)) 3 else 1)
            val row = Row(
                case.product, 1, "count", "retail_units", case.packageForm, source = case.source,
                quantityText = quantityText, quantityOccurrence = case.quantityOccurrence,
                packValue = 1, packUnit = if (case.packageForm == "bag") "kg" else "l", packText = packText,
                packOccurrence = case.packOccurrence,
            )

            val inventory = GroceryIntentInterpretedAmounts(case.source)
            val count = inventory.bind(java.math.BigDecimal.ONE, "count")
            val physical = inventory.bind(java.math.BigDecimal.ONE, row.packUnit)
            assertEquals("count source position: ${case.source}", quantityStart, count?.startUtf16)
            assertEquals("physical source position: ${case.source}", packStart, physical?.startUtf16)

            val prepared = runCatching { prepare(wireRows(case.source, listOf(row))).items.single() }
                .getOrElse { throw AssertionError(case.source, it) }
            val intent = prepared.auditedIntent()!!
            assertEquals(case.source, prepared.rawText)
            assertEquals(Quantity.Count(1), prepared.quantity)
            assertEquals(quantityStart, intent.amountAtoms().single { it.unit == "count" }.startUtf16)
            assertEquals(packStart, intent.amountAtoms().single { it.unit == row.packUnit }.startUtf16)
            assertEquals(java.math.BigDecimal.ONE, intent.amountAtoms().single { it.unit == row.packUnit }.value)
        }
    }

    @Test
    fun sameDimensionAlternativesAndRepeatedCountsStayHeldAndExactValuesAreNotRounded() {
        val equivalentMass = "500 g or 0.5 kg rice"
        assertTrue(runCatching { prepare(wireRows(equivalentMass, listOf(Row(
            "rice", 0.5, "kg", "physical_total", source = equivalentMass, quantityText = "0.5",
        )))) }.isFailure)

        val equivalentCount = "2 packs of 2 eggs"
        assertTrue(runCatching { prepare(wireRows(equivalentCount, listOf(Row(
            "eggs", 2, "count", "retail_units", "pack", source = equivalentCount,
            quantityText = "2", packValue = 2, packUnit = "piece", packText = "2", packOccurrence = 1,
        )))) }.isFailure)

        val exactSource = "0.333 kg rice"
        val exact = prepare(wireRows(exactSource, listOf(Row(
            "rice", 0.333, "kg", "physical_total", source = exactSource, quantityText = "0.333",
        )))).items.single().auditedIntent()!!.amountAtoms().single()
        assertEquals(java.math.BigDecimal("0.333"), exact.value)
        assertTrue("Rounded claim must not bind to the unrounded source atom", runCatching {
            prepare(wireRows(exactSource, listOf(Row(
                "rice", 0.33, "kg", "physical_total", source = exactSource, quantityText = "0.333",
            ))))
        }.isFailure)
    }

    @Test
    fun swappedOriginalCountAndPackOffsetsAreRejected() {
        val source = "1 bottle 1 litre milk"
        val body = wireRows(source, listOf(Row(
            "milk", 1, "count", "retail_units", "bottle", source = source,
            quantityText = "1", quantityOccurrence = 0,
            packValue = 1, packUnit = "l", packText = "1", packOccurrence = 1,
        )))
        val tampered = body
            .replace("\"quantity_atom\":{\"text\":\"1\",\"start_utf16\":0,\"end_utf16\":1}",
                "\"quantity_atom\":{\"text\":\"1\",\"start_utf16\":9,\"end_utf16\":10}")
            .replace("\"pack_atom\":{\"text\":\"1\",\"start_utf16\":9,\"end_utf16\":10}",
                "\"pack_atom\":{\"text\":\"1\",\"start_utf16\":0,\"end_utf16\":1}")
        assertTrue("Both numeric roles must stay tied to their exact original occurrence",
            runCatching { prepare(tampered) }.isFailure)
    }

    @Test
    fun gluedArticleCannotActAsAnImmediatePhysicalUnit() {
        assertNull(GroceryIntentInterpretedAmounts("1a litre milk").bind(java.math.BigDecimal.ONE, "l"))
    }

    @Test
    fun croppedCompoundAndDuplicateEquivalentAmountsRemainHeld() {
        val cases = listOf(
            "a dozen eggs" to listOf(Row(
                "eggs", 1, "count", "requested_pieces", source = "a dozen eggs", quantityText = "a",
            )),
            "one and a half litre milk" to listOf(Row(
                "milk", 1, "count", "retail_units", source = "one and a half litre milk", quantityText = "one",
                packValue = 0.5, packUnit = "l", packText = "half",
            )),
            "one half litre milk" to listOf(Row(
                "milk", 1, "count", "retail_units", source = "one half litre milk", quantityText = "one",
                packValue = 0.5, packUnit = "l", packText = "half",
            )),
            "one-half litre milk" to listOf(Row(
                "milk", 1, "count", "retail_units", source = "one-half litre milk", quantityText = "one",
                packValue = 0.5, packUnit = "l", packText = "half",
            )),
            "two-three kg rice" to listOf(Row(
                "rice", 2, "count", "retail_units", source = "two-three kg rice", quantityText = "two",
                packValue = 3, packUnit = "kg", packText = "three",
            )),
            "500 g rice and 0.5 kg rice" to listOf(Row(
                "rice", 0.5, "kg", "physical_total", source = "500 g rice and 0.5 kg rice", quantityText = "0.5",
            )),
            "500 g or 0.5 kg rice" to listOf(Row(
                "rice", 0.5, "kg", "physical_total", source = "500 g or 0.5 kg rice", quantityText = "0.5",
            )),
            "2 packs of 2 eggs" to listOf(Row(
                "eggs", 2, "count", "retail_units", "pack", source = "2 packs of 2 eggs",
                quantityText = "2", packValue = 2, packUnit = "piece", packText = "2", packOccurrence = 1,
            )),
            "x𝟙L milk" to listOf(Row(
                "milk", 1, "l", "physical_total", source = "x𝟙L milk", quantityText = "𝟙",
            )),
            "two times three apples" to listOf(Row(
                "apples", 2, "count", "retail_units", source = "two times three apples", quantityText = "two",
                packValue = 3, packUnit = "piece", packText = "three",
            )),
            "one 200 g butter pack and one 200 g butter pack" to listOf(Row(
                "butter", 1, "count", "retail_units", source = "one 200 g butter pack and one 200 g butter pack",
                quantityText = "one", packValue = 200, packUnit = "g", packText = "200",
            )),
        )
        for ((source, rows) in cases) {
            assertTrue(source, runCatching { prepare(wireRows(source, rows)) }.isFailure)
        }
    }

    @Test
    fun astralDigitHasAWholeTokenBoundaryAndValidLitreAmountStillBinds() {
        val source = "𝟙L milk"
        val item = prepare(wireRows(source, listOf(Row(
            "milk", 1, "l", "physical_total", source = source, quantityText = "𝟙",
        )))).items.single()
        val atom = item.auditedIntent()!!.amountAtoms().single()
        val start = source.indexOf("𝟙")
        assertEquals("𝟙", atom.atom)
        assertEquals(start, atom.startUtf16)
        assertEquals(start + "𝟙".length, atom.endUtf16)
        assertEquals("𝟙", source.substring(atom.startUtf16, atom.endUtf16))
    }

    @Test
    fun arithmeticConnectorsCannotConsumeProductNamePrefixes() {
        for (name in listOf("Andhra pickle", "Anderson milk", "PlusCare soap", "Timesaver tissues")) {
            val source = "one $name"
            val prepared = prepare(wireRows(source, listOf(Row(name, 1, "count", "retail_units", quantityText = "one"))))
            assertEquals(source, prepared.items.single().rawText)
        }
    }

    @Test
    fun completeAmountGuardFamilyMatchesHostWithoutLosingMeasuredContainers() {
        for (suffix in listOf("halves", "quarters", "lakhs", "crores", "thousand", "dozens")) {
            assertNull(suffix, GroceryIntentInterpretedAmounts("one $suffix rice").bind(java.math.BigDecimal.ONE, "count"))
        }
        val ambiguous = GroceryIntentInterpretedAmounts("one half-litre milk")
        assertNull(ambiguous.bind(java.math.BigDecimal("0.5"), "l"))
        for ((amount, value) in listOf("half" to "0.5", "one" to "1")) {
            val source = "two $amount-litre bottles"
            val inventory = GroceryIntentInterpretedAmounts(source)
            assertEquals("two", inventory.bind(java.math.BigDecimal(2), "count")?.atom)
            assertEquals(amount, inventory.bind(java.math.BigDecimal(value), "l")?.atom)
        }
    }

    @Test
    fun unicodeWhitespaceAndNumericJoinersCannotHideAnIncompleteAmount() {
        for (space in listOf("\u00a0", "\u2003", "\u202f")) {
            assertNull(GroceryIntentInterpretedAmounts("one${space}half litre milk").bind(java.math.BigDecimal("0.5"), "l"))
            assertNull(GroceryIntentInterpretedAmounts("one${space}+ half litre milk").bind(java.math.BigDecimal("0.5"), "l"))
            assertNull(GroceryIntentInterpretedAmounts("one${space}thousand ml milk").bind(java.math.BigDecimal.ONE, "count"))
            assertNull(GroceryIntentInterpretedAmounts("250${space}g rice").bind(java.math.BigDecimal(250), "count"))
            assertEquals("250", GroceryIntentInterpretedAmounts("250${space}g rice").bind(java.math.BigDecimal(250), "g")?.atom)
            val fraction = "one${space}and${space}a${space}half"
            assertEquals(fraction, GroceryIntentInterpretedAmounts("$fraction kg rice").bind(java.math.BigDecimal("1.5"), "kg")?.atom)
            val competitors = GroceryIntentInterpretedAmounts("500${space}g or 0.5 kg rice")
            assertNull(competitors.bind(java.math.BigDecimal(500), "count"))
            assertNull(competitors.bind(java.math.BigDecimal(500), "g"))
        }
        for (joiner in listOf("–", "—", "⁄", "∕", "/", "\u00a0+\u00a0")) {
            assertNull(joiner, GroceryIntentInterpretedAmounts("two${joiner}three kg rice").bind(java.math.BigDecimal(3), "kg"))
        }
    }

    @Test
    fun auditedRetailUnitsReachReviewAsProposalButNeverAsExactQuantity() {
        val instruction = "one milk packet"
        val preparation = GroceryIntentInterpretedReview.parse(wire(instruction, "milk", 1, "pack", "retail_units", "pack"), instruction)
            .prepareForReview()
        val item = preparation.items.single()
        assertEquals(instruction, item.rawText)
        assertTrue(isSwiggyCandidateAllowed(item, offer("milk", "Milk Packet", "500 ml")))
        val offer = offer("milk", "Milk Packet", "500 ml")
        val assessed = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(offer)))
        assertTrue(resolveSwiggyCartQuantity(assessed, offer) is SwiggyCartQuantityResolution.RetailProposal)
        assertTrue(SwiggyCartReviewRequest.build(listOf(assessed), mapOf(0 to offer), emptySet(), preparation.auditedBasket) != null)
    }

    @Test
    fun requestedPiecesConvertOnlyByExactSameSkuDivisorAndUnstatedDefaultsToOneSku() {
        val instruction = "twelve bananas"
        val preparation = prepare(wireRows(instruction, listOf(Row("bananas", 12, "piece", "requested_pieces"))))
        val bananas = preparation.items.single()
        assertEquals(IntentQuantityOrigin.INTERPRETED, bananas.intentQuantityProvenance?.origin)
        val sixPack = candidate("bananas", "Bananas", "Banana pack", sku = "sku-bananas",
            quantityFields = CatalogueQuantityFields(variantName = "6 pieces"))
        val checked = assessSwiggyIntentQuantity(bananas, SwiggyMcpClient.Recommendations(listOf(sixPack)))
        assertTrue(resolveSwiggyCartQuantity(checked, sixPack) is SwiggyCartQuantityResolution.Exact)
        assertEquals(2, (resolveSwiggyCartQuantity(checked, sixPack) as SwiggyCartQuantityResolution.Exact).cartUnits)
        val request = SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to sixPack), emptySet(), preparation.auditedBasket)
        assertEquals(2, requireNotNull(request).requestedItems.single().quantity)

        val indivisible = prepare(wireRows("thirteen bananas", listOf(Row("bananas", 13, "piece", "requested_pieces")))).items.single()
        val held = resolveSwiggyCartQuantity(assessSwiggyIntentQuantity(indivisible,
            SwiggyMcpClient.Recommendations(listOf(sixPack))), sixPack)
        assertTrue(held is SwiggyCartQuantityResolution.Held)
        assertNull(SwiggyCartReviewRequest.build(listOf(indivisible), mapOf(0 to sixPack), emptySet()))
        val swappedAmountRole = wireRows(instruction, listOf(Row("bananas", 12, "piece", "retail_units")))
        assertThrows(IllegalArgumentException::class.java) { GroceryIntentInterpretedReview.parse(swappedAmountRole, instruction) }

        val defaultPreparation = prepare(wireRows("milk", listOf(Row("milk", null, "unspecified", "unstated"))))
        val milk = defaultPreparation.items.single()
        val concreteSku = RecommendationCandidate("single-milk", "Milk", skuId = "sku-single-milk",
            catalogueIngredients = sellerTitleFixture("single-milk", "sku-single-milk", "Milk"))
        val defaultResolution = resolveSwiggyCartQuantity(assessSwiggyIntentQuantity(milk,
            SwiggyMcpClient.Recommendations(listOf(concreteSku))), concreteSku)
        assertEquals(SwiggyCartQuantityResolution.Exact(1), defaultResolution)
        val defaultRequest = SwiggyCartReviewRequest.build(listOf(assessSwiggyIntentQuantity(milk,
            SwiggyMcpClient.Recommendations(listOf(concreteSku)))), mapOf(0 to concreteSku), emptySet(), defaultPreparation.auditedBasket)
        assertEquals(1, requireNotNull(defaultRequest).requestedItems.single().quantity)
        assertTrue(defaultRequest.quantityProposals.isEmpty())
    }

    private fun assertSkuReview(prep: GroceryIntentPreparation, offer: RecommendationCandidate, units: Int?) {
        val recs = SwiggyMcpClient.Recommendations(listOf(offer), suggested = offer)
        val item = assessSwiggyIntentQuantity(prep.items.single(), recs)
        val usable = swiggyUsableCandidates(item, recs)
        val msg = "${item.rawText} / ${offer.label}"
        assertEquals(msg, units != null, usable.isNotEmpty())
        if (units != null) assertEquals(msg, offer, swiggyDefaultSuggestion(item, usable, recs.suggested))
        // Direct submission deliberately bypasses selection; the final boundary must agree.
        val request = SwiggyCartReviewRequest.build(listOf(item), mapOf(0 to offer), emptySet(), prep.auditedBasket)
        assertEquals(msg, units, request?.requestedItems?.single()?.quantity)
        if (units != null) assertEquals(msg, offer.skuId, request!!.requestedItems.single().skuId)
    }
    private fun exercise(name: String, total: Int, unit: String, offer: RecommendationCandidate, units: Int?) {
        val source = "$total $unit $name"
        val prep = prepare(wireRows(source, listOf(Row(name, total, unit, "physical_total",
            source = source, quantityText = total.toString()))))
        assertSkuReview(prep,offer,units)
    }
    private fun variation(title: String, variant: String) = candidate("shop",title,variant,sku="sku",
        quantityFields=CatalogueQuantityFields(variantName=variant)).copy(label="$title · $variant")

    @Test fun projectedPlainSizeNeedsTheSameFirstOwnedFieldInBothViewsAndCannotProveAContainer() {
        fun evidence(fields: Map<String,String>, sku: String = "sku"): JsonValue {
            val json = fields.entries.joinToString(",") { (k,v) -> "${JSONObject.quote(k)}:${JSONObject.quote(v)}" }
            return JsonParser("""{"version":1,"source":"search_variation","spinId":"spin","skuId":${JSONObject.quote(sku)},"fields":{$json}}""").parseStrict()
        }
        fun offer(title: String, descriptor: String, quantity: Map<String,String>, identity: Map<String,String> = quantity,
                  merchantTitle: String? = title): RecommendationCandidate {
            val titles = merchantTitle?.let { mapOf("displayName" to it) }.orEmpty()
            return RecommendationCandidate("spin", "$title · $descriptor", skuId="sku", variant=descriptor,
                catalogueIngredients=CatalogueIngredientEvidence.parse(evidence(titles+identity),"spin","sku",emptySet()),
                catalogueQuantity=parseCatalogueQuantityEvidence(evidence(quantity),"spin","sku"))
        }
        val size = mapOf("quantityDescription" to "250 g")
        fun counted(name: String, form: String, measure: Int): GroceryIntentPreparation {
            val source = "one $measure g $form of $name"
            return prepare(wireRows(source, listOf(Row(name, 1, "count", "retail_units", form,
                source=source, quantityText="one", packText=measure.toString(), packValue=measure, packUnit="g"))))
        }
        for ((name, form, measure) in listOf(Triple("potatoes", "bag", 500), Triple("barley", "pack", 250))) {
            val wanted = counted(name, form, measure)
            val declared = "1 $form · $measure g"
            fun listed(title: String, description: String) =
                offer(title, description, mapOf("quantityDescription" to description))
            assertSkuReview(wanted, listed(name, declared), 1)
            for (wrong in listOf(listed(name, "$form, $measure g"), listed(name, "$measure g"),
                listed(name, "2 $form · $measure g"), listed(name, "1 $form · 375 g"),
                listed("$name storage container", declared), listed("other goods", declared),
                offer(name, declared, mapOf("quantityDescription" to declared), mapOf("subTitle" to declared))))
                assertSkuReview(wanted, wrong, null)
        }
        // Documented Instamart direct variation: no variantName is required.
        // Both bounded evidence views must agree on the very same source key.
        val packaged = mapOf("quantityDescription" to "bag, 1 kg")
        exercise("potatoes", 2, "kg", offer("Potatoes", "bag, 1 kg", packaged), 2)
        exercise("toor dal", 500, "g", offer("Toor Dal", "pack, 500 g",
            mapOf("quantityDescription" to "pack, 500 g")), 1)
        for (bad in listOf(
            offer("Potatoes", "bag, 1 kg", packaged, mapOf("subTitle" to "bag, 1 kg")),
            offer("Potatoes", "bag, 1 kg", packaged, packaged + ("variantName" to "bag, 2 kg")),
            offer("Potatoes", "bag, 1 kg", mapOf("subTitle" to "bag, 1 kg")),
            offer("Potatoes", "bag, 1 kg", packaged, merchantTitle="Potato Printed Bag"),
            offer("Potatoes", "bag, 1 kg", packaged + ("packSize" to "500 g")),
            offer("Potatoes", "bag, 1 kg", packaged, packaged + ("subTitle" to "Empty Sack"))
        )) exercise("potatoes", 2, "kg", bad, null)
        val plain = offer("Oats","250 g",size)
        exercise("oats",250,"g",plain,1)
        exercise("oats",750,"g",plain,3)
        exercise("milk",1,"l",offer("Milk","500 ml",mapOf("packSize" to "500 ml")),2)
        exercise("oats",275,"g",plain,null)

        val objections = listOf(
            offer("Oats","250 g",size+("variantName" to "500 g")),
            offer("Oats","250 g",size+("variant" to "250 g + bowl")),
            offer("Oats","250 g",size+("packSize" to "200 g")),
            offer("Oats","250 g",size,emptyMap()),
            offer("Oats","250 g",emptyMap(),size),
            offer("Oats","250 g",size,mapOf("packSize" to "250 g")),
            offer("Oats","250 g",mapOf("subTitle" to "250 g")),
            offer("Oats","250 g",size,merchantTitle=null), // display or parent title alone
            offer("Oats","250 g",size,merchantTitle="Oats Bowl"),
            offer("Oats","250 g",size,size+("subTitle" to "Empty packet")),
            offer("Oats","250 g",size,size+("subTitle" to "500 g")),
            offer("Oats","250 g free",mapOf("quantityDescription" to "250 g free")),
            offer("Oats","250 g x 2",mapOf("quantityDescription" to "250 g x 2")),
            plain.copy(skuId="sibling"), plain.copy(catalogueQuantity=plain.catalogueQuantity?.copy(skuId="sibling")),
            plain.copy(catalogueIngredients=CatalogueIngredientEvidence.parse(
                evidence(size+("displayName" to "Oats"),"sibling"),"spin","sibling",emptySet())),
        )
        for (bad in objections) exercise("oats",250,"g",bad,null)
        for (title in listOf("Measuring Cup","Water Bottle")) {
            exercise(title.lowercase(),1,"l",offer(title,"500 ml",mapOf("quantityDescription" to "500 ml")),null)
        }
        for ((form,noun,descriptor) in listOf(Triple("carton","milk","500 ml"),Triple("packet","oats","250 g"))) {
            val source = "2 ${form}s of $noun"
            val preparation = prepare(wireRows(source,listOf(Row(noun,2,"count","retail_units",form,
                quantityText="2"))))
            assertSkuReview(preparation,offer(noun,descriptor,mapOf("quantityDescription" to descriptor)),null)
        }
    }

    @Test fun completeNamedEditionAndMetricMustBelongToTheRequestedSourceAndPrimary() {
        val oats = variation("Oats", "Plain Rolled Oats, 400 g")
        exercise("plain rolled oats", 800, "g", oats, 2)
        exercise("roasted cumin seeds", 250, "g", variation("Cumin Seeds", "Roasted Cumin Seeds, 250 g"), 1)
        exercise("oats", 800, "g", oats, null)
        exercise("rolled oats", 800, "g", oats, null)
        exercise("plain rolled oats", 750, "g", oats, null)
        for (bad in listOf(
            variation("Granola", "Plain Rolled Oats, 400 g"),
            variation("Oats Bowl", "Plain Rolled Oats, 400 g"),
            variation("Oats", "Plain Rolled, 400 g"),
            variation("Oats", "Plain Rolled Oats for Baking, 400 g"),
            oats.copy(subtitle="Instant Oats"),
            oats.copy(skuId="other"),
            oats.copy(cataloguePurchaseLimit=SwiggyMcpClient.CataloguePurchaseLimit("spin-shop","sku",1)),
        )) exercise("plain rolled oats", 800, "g", bad, null)
    }

    @Test
    fun repeatedWholeTitleAndMeasuredSizeCanReachExactlySizedCartReviewOnlyOnTheSelectedSku() {
        exercise("rolled oats",800,"g",variation("Rolled Oats","Rolled Oats, 400 g"),2)
        exercise("cumin seeds",250,"g",variation("Cumin Seeds","Cumin Seeds, 250 g"),1)
        exercise("apple juice",1,"l",variation("Apple Juice","Apple Juice, 500 ml"),2)
        // The established pure-size path is separate; it does not grant an edition new authority.
        exercise("rolled oats",800,"g",variation("Rolled Oats","400 g"),2)

        val real = variation("Rolled Oats","Rolled Oats, 400 g")
        val qty = requireNotNull(real.catalogueQuantity)
        for (bad in listOf(
            real.copy(catalogueQuantity=null), real.copy(catalogueIngredients=null),
            real.copy(catalogueQuantity=qty.copy(skuId="other")),
            real.copy(catalogueIngredients=sellerTitleFixture("spin-shop","other","Rolled Oats",real.variant)),
            real.copy(catalogueIngredients=sellerTitleFixture("spin-shop","sku","Rolled Oats")),
            real.copy(catalogueQuantity=qty.copy(fields=CatalogueQuantityFields(packSize="400 g"))),
            real.copy(catalogueQuantity=qty.copy(fields=qty.fields.copy(packSize="250 g"))),
            real.copy(catalogueQuantity=qty.copy(fields=qty.fields.copy(variant="Whole Oats, 400 g"))),
            real.copy(catalogueIngredients=sellerTitleFixture("spin-shop","sku","Rolled Oats",real.variant,"Rolled Oats Printed Lunch Boxes")),
            real.copy(catalogueIngredients=sellerTitleFixture("spin-shop","sku","Rolled Oats",real.variant,detail="Scented Soap")),
            real.copy(label="Rolled Oats Printed Lunch Boxes · ${real.variant}"),
            real.copy(skuId="other"), real.copy(cataloguePurchaseLimit=SwiggyMcpClient.CataloguePurchaseLimit("spin-shop","sku",1)),
            variation("Rolled Oats Printed Lunch Boxes","Rolled Oats, 400 g"),
            variation("Rolled Oats","Oats, 400 g"),variation("Rolled Oats","whole, 400 g"),
            variation("Rolled Oats","Rolled Oats Scented Soap, 400 g"),
            variation("Rolled Oats","Rolled Oats, 2 × 200 g"),variation("Rolled Oats","Rolled Oats, 400 g + 250 g free"),
            variation("Rolled Oats","Rolled Oats, per 100 g"),variation("Rolled Oats","Rolled Oats, 0 g"),
            variation("Rolled Oats","Rolled Oats, 400 ml"),variation("Rolled Oats","Rolled Oats, 450 g"),
        )) exercise("rolled oats",800,"g",bad,null)
        exercise("oats",400,"g",variation("Oats","plain rolled oats, 400 g"),null)
        exercise("cumin seeds",250,"g",variation("Cumin Seeds","whole, 250 g"),null)
    }

    @Test fun wholeContainerThenMetricDescribesContentsButNotAnOuterCount() {
        val pack = variation("Potatoes", "bag, 1 kg")
        exercise("potatoes", 2, "kg", pack, 2)
        exercise("toned milk", 1, "l", variation("Toned Milk", "pouch, 500 ml"), 2)
        exercise("potatoes", 1500, "g", pack, null)
        for (descriptor in listOf("bags, 1 kg", "bag, per 1 kg", "bag or pack, 1 kg",
            "bag, 1 kg + free scoop", "bag, 1 kg, 2 pieces", "empty bag, 1 kg",
            "bag, 2 × 500 g", "roll, 1 kg", "bag, 1 l")) {
            exercise("potatoes", 2, "kg", variation("Potatoes", descriptor), null)
        }
        exercise("water bottle", 1, "l", variation("Water Bottle", "bottle, 500 ml"), null)
        exercise("potatoes", 2, "kg", pack.copy(subtitle="Potato Printed Bag"), null)
        exercise("potatoes", 2, "kg", pack.copy(skuId="sibling"), null)
        val counted = prepare(wireRows("2 bags of potatoes", listOf(
            Row("potatoes", 2, "count", "retail_units", "bag", quantityText="2"))))
        assertSkuReview(counted, pack, null)
    }

    @Test fun documentedStapleNamesExplainWeightButNeverEquateDifferentGoods() {
        for (noun in listOf("toor dal", "moong dal", "masoor dal", "red sticky rice", "soya chunks")) {
            exercise(noun, 1, "kg", variation(noun, "pack, 500 g"), 2)
        }
        exercise("toor dal", 1, "kg", variation("moong dal", "pack, 500 g"), null)
        exercise("toor dal", 1, "kg", variation("toor dal soap", "pack, 500 g"), null)
        exercise("toor dal soap", 1, "kg", variation("toor dal soap", "pack, 500 g"), null)
        exercise("toor dal", 1, "l", variation("toor dal", "bag, 500 ml"), null)
        exercise("red sticky rice", 1, "kg", variation("rice", "pack, 500 g"), null)
    }

    @Test fun metricThenSingleContainerNeverInfersNumberOfContainersOrProductIdentity() {
        val real = variation("Toned Milk","500 ml pouch")
        exercise("toned milk",1,"l",real,2)
        exercise("oil",1,"l",variation("Oil","500 ml pouch"),2)
        exercise("sunflower oil",1,"l",variation("Sunflower Oil","500 ml pouch"),2)
        exercise("mango drink",200,"ml",variation("Mango Drink","200 ml tetra pack"),1)
        exercise("apple juice",1,"l",variation("Apple Juice","500 ml bottle"),2)
        exercise("cheese",300,"g",variation("Cheese","150 g pack"),2)
        exercise("cheese slices",300,"g",variation("Cheese Slices","150 g pack"),2)
        exercise("toned milk",750,"ml",real,null)
    }

    @Test fun metricThenContainerNeverConfirmsConflictsOrPartialSellerLegends() {
        val real = variation("Toned Milk","500 ml pouch")
        val qty = requireNotNull(real.catalogueQuantity)
        val wrongForms = listOf("2 × 500 ml pouch","500 ml bottle cap","500 ml per pouch","2 pouches",
            "500 ml pouch + free sample","500 ml refill","500 ml sleeve","offer: 500 ml pouch","0 ml pouch",
            "500 ml / 250 ml pouch", "500 ml pouches", "500 ml pouch, when diluted","500 g pouch",
            "500 ml bar","500 ml block","500 ml roll","500 ml loaf","500 ml bunch")
        val objections = wrongForms.map { variation("Toned Milk",it) } + listOf(
            variation("Printed Milk Pouch","500 ml pouch"), real.copy(variant="200 ml pouch"),
            real.copy(catalogueBrand=SwiggyMcpClient.CatalogueBrandEvidence("Dairy","spin-shop","other","search_variation")),
            real.copy(catalogueQuantity=qty.copy(spinId="sibling")), real.copy(catalogueIngredients=null),
            real.copy(catalogueIngredients=sellerTitleFixture("spin-shop","other","Toned Milk",real.variant)),
            real.copy(catalogueIngredients=sellerTitleFixture("spin-shop","sku","Toned Milk")),
            real.copy(catalogueIngredients=sellerTitleFixture("spin-shop","sku","Toned Milk",real.variant,detail="Empty pouch")),
            real.copy(catalogueQuantity=qty.copy(fields=qty.fields.copy(packSize="250 ml"))),
            real.copy(catalogueQuantity=qty.copy(fields=qty.fields.copy(quantityDescription="2 pouches"))),
            real.copy(catalogueQuantity=qty.copy(fields=CatalogueQuantityFields(packSize="500 ml"))),
            real.copy(cataloguePurchaseLimit=SwiggyMcpClient.CataloguePurchaseLimit("spin-shop","sku",1))
        )
        val admitted = objections.mapIndexedNotNull { i, bad ->
            try { exercise("toned milk",1,"l",bad,null); null }
            catch (e: AssertionError) { "case $i: ${e.message}" }
        }
        assertTrue(admitted.joinToString("\n"), admitted.isEmpty())
    }

    @Test fun metricThenContainerIsNotAnOuterCountOrVesselCapacity() {
        for ((source, row, selected) in listOf(
            Triple("2 pouches of juice", Row("juice",2,"count","retail_units","pouch",quantityText="2"),variation("Juice","500 ml pouch")),
            Triple("2 bottles of juice", Row("juice",2,"count","retail_units","bottle",quantityText="2"),variation("Juice","500 ml pouch")),
            Triple("2 apples",Row("apples",2,"count","requested_pieces",quantityText="2"),variation("Apples","250 g pack"))
        )) assertSkuReview(prepare(wireRows(source,listOf(row))),selected,null)
        // A vessel's nominal capacity is not the amount of merchandise in the request.
        for (title in listOf("Measuring Cup","Water Bottle","Plastic Jar"))
            for (descriptor in listOf("500 ml bottle","$title, 500 ml"))
                exercise(title.lowercase(),1,"l",variation(title,descriptor),null)
    }

    @Test
    fun qualifiedSaleUnitNeedsWholeSkuDescriptorAndIndependentSize() {
        data class Case(val product: String, val container: String, val size: String,
                        val descriptor: String, val subtitleConflict: String)
        val cases = listOf(
            Case("paneer block", "block", "200 g", "1 sealed block", "2 blocks · 200 g"),
            Case("mixed fruit jam jar", "jar", "500 g", "1 glass jar", "1 jar · 250 g"),
            Case("laundry detergent bottle", "bottle", "1 L", "1 plastic bottle", "2 bottles · 1 L"),
        )

        fun titleEvidence(
            spin: String, sku: String, title: String, extraFields: Map<String, String> = emptyMap(),
        ): CatalogueIngredientEvidence {
            val fields = (listOf("title" to title) + extraFields.entries.map { it.key to it.value })
                .joinToString(",") { (key, value) -> "${JSONObject.quote(key)}:${JSONObject.quote(value)}" }
            val payload = """{"version":1,"source":"search_variation","spinId":"$spin","skuId":"$sku","fields":{$fields}}"""
            return requireNotNull(CatalogueIngredientEvidence.parse(
                JsonParser(payload).parseStrict(), spin, sku, emptySet(),
            ))
        }

        val qualifiedFailures = mutableListOf<String>()
        for ((index, c) in cases.withIndex()) {
            val source = "one ${c.product}"
            val preparation = prepare(wireRows(source, listOf(Row(
                c.product, 1, "count", "retail_units", c.container,
                source = source, quantityText = "one",
            ))))
            val item = preparation.items.single()
            val sku = "qualified-unit-$index"
            val spin = "qualified-spin-$index"
            fun offer(
                productTitle: String = c.product,
                variantName: String? = c.size,
                quantityDescription: String? = c.descriptor,
                subtitle: String? = null,
                displayVariant: String? = null,
                titleEvidenceSku: String = sku,
                quantityEvidenceSku: String = sku,
                catalogueFields: Map<String, String> = emptyMap(),
                quantityFields: CatalogueQuantityFields? = CatalogueQuantityFields(
                    variantName = variantName, quantityDescription = quantityDescription, subTitle = subtitle,
                ),
            ): RecommendationCandidate = RecommendationCandidate(
                spinId = spin, label = c.product, variant = displayVariant, subtitle = null,
                skuId = sku,
                catalogueQuantity = quantityFields?.let {
                    CatalogueQuantityEvidence(1, "search_variation", spin, quantityEvidenceSku, it)
                },
                catalogueIngredients = titleEvidence(spin, titleEvidenceSku, productTitle, catalogueFields),
            ).copy(subtitle = subtitle)

            // The undecorated, exact single-unit description remains a valid
            // baseline; separate typed metadata establishes both size and unit.
            val baseline = offer(quantityDescription = "1 ${c.container}")
            val baselineChecked = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(baseline)))
            assertTrue("undecorated ${c.container} baseline",
                resolveSwiggyCartQuantity(baselineChecked, baseline) is SwiggyCartQuantityResolution.RetailProposal)
            assertTrue("baseline cart review", SwiggyCartReviewRequest.build(
                listOf(baselineChecked), mapOf(0 to baseline), emptySet(), preparation.auditedBasket,
            ) != null)

            val badOffers = listOf(
                "qualified as free" to offer(quantityDescription = "1 free ${c.container}"),
                "claims an inner unit" to offer(quantityDescription = "1 inner ${c.container}"),
                "claims two outer units" to offer(quantityDescription = "2 glass ${c.container}s"),
                "wrong whole product title" to offer(productTitle = "${c.product}s"),
                "title evidence belongs to another SKU" to offer(titleEvidenceSku = "different-sku"),
                "container lid is not the product" to offer(productTitle = "${c.product} lid"),
                "contradictory typed subtitle" to offer(subtitle = c.subtitleConflict),
                "display-only count and size" to offer(
                    variantName = null, quantityDescription = null,
                    displayVariant = "1 ${c.container}, ${c.size}", quantityFields = null,
                ),
                "size evidence belongs to another SKU" to offer(quantityEvidenceSku = "different-sku"),
                "unknown qualifying word" to offer(quantityDescription = "1 mystery ${c.container}"),
                "same-SKU ingredient snapshot contradicts typed size" to offer(
                    catalogueFields = mapOf("packSize" to "1 kg"),
                ),
                "same-SKU secondary snapshot names inner carton" to offer(
                    catalogueFields = mapOf("subTitle" to "inner carton"),
                ),
            )
            for ((reason, bad) in badOffers) {
                val assessed = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(bad)))
                assertTrue("$reason: ${c.product}", resolveSwiggyCartQuantity(assessed, bad) is SwiggyCartQuantityResolution.Held)
                assertNull(reason, SwiggyCartReviewRequest.build(
                    listOf(assessed), mapOf(0 to bad), emptySet(), preparation.auditedBasket,
                ))
            }
            if (c.container == "jar") {
                val pouchSubtitle = offer(subtitle = "1 pouch")
                val pouchChecked = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(pouchSubtitle)))
                assertTrue("subtitle names pouch, not jar",
                    resolveSwiggyCartQuantity(pouchChecked, pouchSubtitle) is SwiggyCartQuantityResolution.Held)
                assertNull(SwiggyCartReviewRequest.build(
                    listOf(pouchChecked), mapOf(0 to pouchSubtitle), emptySet(), preparation.auditedBasket,
                ))
                val holder = offer(productTitle = "mixed fruit jam holder for jar")
                val holderChecked = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(holder)))
                assertTrue("holder for jar is not jam", resolveSwiggyCartQuantity(holderChecked, holder) is SwiggyCartQuantityResolution.Held)
                assertNull(SwiggyCartReviewRequest.build(
                    listOf(holderChecked), mapOf(0 to holder), emptySet(), preparation.auditedBasket,
                ))
            }

            // Qualified natural descriptions are the positive regression target:
            // title identity, independent physical size, and named count all bind
            // to this same sellable SKU.
            val qualified = offer()
            val qualifiedChecked = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(qualified)))
            val qualifiedResolution = resolveSwiggyCartQuantity(qualifiedChecked, qualified)
            val qualifiedRequest = SwiggyCartReviewRequest.build(
                listOf(qualifiedChecked), mapOf(0 to qualified), emptySet(), preparation.auditedBasket,
            )
            if (qualifiedResolution !is SwiggyCartQuantityResolution.RetailProposal || qualifiedRequest == null) {
                qualifiedFailures += "${c.product}: resolution=$qualifiedResolution, cart=${qualifiedRequest != null}"
            } else {
                assertEquals(qualified.skuId, qualifiedRequest.requestedItems.single().skuId)
                assertEquals(1, qualifiedRequest.requestedItems.single().quantity)
            }

            val explicitSource = when (c.container) {
                "block" -> "4 paneer blocks 200 g each"
                "jar" -> "2 mixed fruit jam jars 500 g each"
                else -> null
            }
            if (explicitSource != null) {
                val sourceCount = if (c.container == "block") 4 else 2
                val mass = if (c.container == "block") 200 else 500
                val explicitPreparation = prepare(wireRows(explicitSource, listOf(Row(
                    c.product, sourceCount, "count", "retail_units", c.container,
                    source = explicitSource, quantityText = sourceCount.toString(),
                    packValue = mass, packUnit = "g", packText = mass.toString(),
                ))))
                val explicitItem = explicitPreparation.items.single()
                val exactOffer = offer()
                val exactChecked = assessSwiggyIntentQuantity(explicitItem, SwiggyMcpClient.Recommendations(listOf(exactOffer)))
                val exactResolution = resolveSwiggyCartQuantity(exactChecked, exactOffer)
                val exactCart = SwiggyCartReviewRequest.build(
                    listOf(exactChecked), mapOf(0 to exactOffer), emptySet(), explicitPreparation.auditedBasket,
                )
                if (exactResolution !is SwiggyCartQuantityResolution.RetailProposal || exactCart == null) {
                    qualifiedFailures += "$explicitSource: resolution=$exactResolution, cart=${exactCart != null}"
                } else {
                    assertEquals(exactOffer.skuId, exactCart.requestedItems.single().skuId)
                    assertEquals(sourceCount, exactCart.requestedItems.single().quantity)
                }
                val wrongSize = offer(variantName = if (mass == 200) "250 g" else "750 g")
                val wrongSizeChecked = assessSwiggyIntentQuantity(explicitItem,
                    SwiggyMcpClient.Recommendations(listOf(wrongSize)))
                assertTrue("wrong per-unit size $explicitSource",
                    resolveSwiggyCartQuantity(wrongSizeChecked, wrongSize) is SwiggyCartQuantityResolution.Held)
                assertNull(SwiggyCartReviewRequest.build(
                    listOf(wrongSizeChecked), mapOf(0 to wrongSize), emptySet(), explicitPreparation.auditedBasket,
                ))
            }
        }
        assertTrue("qualified sale-unit positives: ${qualifiedFailures.joinToString()}", qualifiedFailures.isEmpty())
    }

    @Test
    fun highSourcePiecesReachReviewOnlyAsTheExactSkuCartQuotient() {
        data class Case(val source: String, val name: String, val pieces: Int, val perSku: Int, val cartUnits: Int)
        val cases = listOf(
            Case("50 pieces of cotton wicks", "cotton wicks", 50, 10, 5),
            Case("100 paper filters", "paper filters", 100, 5, 20),
            Case("24 limes", "limes", 24, 6, 4),
        )
        for ((index, c) in cases.withIndex()) {
            val prep = prepare(wireRows(c.source, listOf(Row(c.name, c.pieces, "piece", "requested_pieces",
                quantityText = c.pieces.toString()))))
            val item = prep.items.single()
            assertEquals(Quantity.Count(c.pieces), item.quantity)
            assertTrue(item.logicalPieceCount)
            assertEquals(c.source, item.rawText)
            val offer = candidate("piece-$index", c.name, sku = "sku-piece-$index",
                quantityFields = CatalogueQuantityFields(variantName = "${c.perSku} pieces"))
            val checked = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(offer)))
            assertEquals(c.source, SwiggyCartQuantityResolution.Exact(c.cartUnits), resolveSwiggyCartQuantity(checked, offer))
            val request = requireNotNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to offer), emptySet(), prep.auditedBasket))
            assertEquals(offer.skuId, request.requestedItems.single().skuId)
            assertEquals(c.cartUnits, request.requestedItems.single().quantity)
            assertTrue(request.quantityProposals.isEmpty())
        }
    }

    @Test
    fun highBarePiecesRequireCompleteMatchingSellerNamesForTheCurrentSku() {
        val prep = prepare(wireRows("100 paper filters", listOf(Row("paper filters", 100, "piece",
            "requested_pieces", quantityText = "100"))))
        val sku = "sku-filters"
        fun names(boundSku: String, vararg fields: Pair<String, String>): CatalogueIngredientEvidence {
            val raw = fields.joinToString(",") { (k, v) -> "${JSONObject.quote(k)}:${JSONObject.quote(v)}" }
            return requireNotNull(CatalogueIngredientEvidence.parse(JsonParser(
                """{"version":1,"source":"search_variation","spinId":"spin-filters","skuId":"$boundSku","fields":{$raw}}"""
            ).parseStrict(), "spin-filters", boundSku, emptySet()))
        }
        val decorated = candidate("filters", "Paper Filters · 5 pieces", variant = "5 pieces", sku = sku,
            quantityFields = CatalogueQuantityFields(variantName = "5 pieces")).copy(
                catalogueIngredients = names(sku, "productName" to "Paper Filters"))
        fun check(offer: RecommendationCandidate) =
            assessSwiggyIntentQuantity(prep.items.single(), SwiggyMcpClient.Recommendations(listOf(offer)))
        val checked = check(decorated)
        assertEquals(SwiggyCartQuantityResolution.Exact(20), resolveSwiggyCartQuantity(checked, decorated))
        val request = requireNotNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to decorated), emptySet(), prep.auditedBasket))
        assertEquals(sku, request.requestedItems.single().skuId)
        assertEquals(20, request.requestedItems.single().quantity)

        for ((reason, bad) in mapOf(
            "modified display label despite exact typed name" to decorated.copy(label = "Reusable Paper Filters · 5 pieces"),
            "substring display label despite exact typed name" to decorated.copy(label = "Paper Filters Holder"),
            "modified typed name despite exact display label" to decorated.copy(label = "Paper Filters",
                catalogueIngredients = names(sku, "productName" to "Reusable Paper Filters")),
            "second typed title disagrees" to decorated.copy(catalogueIngredients = names(sku,
                "productName" to "Paper Filters", "itemName" to "Paper Filters Holder")),
            "exact title transplanted from another SKU" to decorated.copy(
                catalogueIngredients = names("stale-sku", "title" to "Paper Filters")),
            "exact noun occurs only in a subtitle" to decorated.copy(label = "Filter Holder", subtitle = "Paper Filters",
                catalogueIngredients = names(sku, "subTitle" to "Paper Filters")),
            "display variant not corroborated by typed quantity fields" to decorated.copy(
                label = "Paper Filters · standard", variant = "standard"),
        )) {
            val assessed = check(bad)
            assertTrue(reason, resolveSwiggyCartQuantity(assessed, bad) is SwiggyCartQuantityResolution.Held)
            assertNull(reason, SwiggyCartReviewRequest.build(listOf(assessed), mapOf(0 to bad), emptySet(), prep.auditedBasket))
        }
    }

    @Test
    fun highSourcePiecesCannotRoundOrBypassCartCapSellerCapOrSkuEvidence() {
        val source = "100 paper filters"
        val prep = prepare(wireRows(source, listOf(Row("paper filters", 100, "piece", "requested_pieces", quantityText = "100"))))
        fun offer(size: Int) = candidate("filters", "Paper Filters", sku = "sku-filters",
            quantityFields = CatalogueQuantityFields(packSize = "$size pieces"))
        val exact = offer(5)
        val capped = exact.copy(cataloguePurchaseLimit = SwiggyMcpClient.CataloguePurchaseLimit(exact.spinId,
            requireNotNull(exact.skuId), 19))
        for ((reason, bad) in mapOf(
            "one-piece SKU would exceed twenty cart units" to offer(1),
            "inexact quotient" to offer(6),
            "divisor belongs to a different SKU" to exact.copy(skuId = "changed-sku"),
            "display-only divisor" to exact.copy(variant = "5 pieces", catalogueQuantity = null),
            "seller permits only nineteen" to capped,
        )) {
            val checked = assessSwiggyIntentQuantity(prep.items.single(), SwiggyMcpClient.Recommendations(listOf(bad)))
            val resolution = resolveSwiggyCartQuantity(checked, bad)
            assertTrue(reason, resolution is SwiggyCartQuantityResolution.Held)
            if (bad === capped) assertEquals(SwiggyCartQuantityResolution.Held("seller_limit_exceeded"), resolution)
            assertNull(reason, SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to bad), emptySet(), prep.auditedBasket))
        }
    }

    @Test
    fun highPiecesUseOnlyACompleteConjunctionBetweenBoundItems() {
        val left = "36 tealight candles"
        val right = "2 kilo rice"
        val rows = listOf(Row("tealight candles", 36, "piece", "requested_pieces", source = left, quantityText = "36"),
            Row("rice", 2, "kg", "physical_total", source = right, quantityText = "2"))
        for (gap in listOf(" and ", " aur ", ", ")) {
            assertEquals(2, prepare(wireRows(left + gap + right, rows)).items.size)
        }
        for (source in listOf("aur $left and $right", "$left and $right aur", "$left aur nahi $right",
                "$left aur 3 $right", "$left aur if available $right")) {
            assertThrows(source, IllegalArgumentException::class.java) { prepare(wireRows(source, rows)) }
        }
    }

    @Test
    fun highPieceAuditCannotPromoteRetailDoseModelOrIncompleteSourceToPieces() {
        fun high(name: String, span: String) =
            Row(name, 50, "piece", "requested_pieces", source = span, quantityText = "50")
        val cases = listOf(
            "50 packets of cotton wicks" to high("cotton wicks", "50 packets of cotton wicks"),
            "50 dabba chai" to high("dabba chai", "50 dabba chai"),
            "50 mg iron" to high("mg iron", "50 mg iron"),
            "50 doses of mouthwash" to high("doses of mouthwash", "50 doses of mouthwash"),
            "Model 50 paper filters" to high("paper filters", "Model 50 paper filters"),
            "50 unbleached paper filters" to high("paper filters", "50 unbleached paper filters"),
            "50 kagaz ke filter" to high("paper filters", "50 kagaz ke filter"),
            "50 paper filters if unbleached" to high("paper filters", "50 paper filters"),
            "don't get the 50-pack of paper filters" to high("paper filters", "50-pack of paper filters"),
        )
        for ((instruction, row) in cases) {
            val body = wireRows(instruction, listOf(row))
            assertThrows(instruction, IllegalArgumentException::class.java) { prepare(body) }
        }
        val instruction = "50 cotton wicks and 100 paper filters"
        val swapped = wireRows(instruction, listOf(
            Row("paper filters", 100, "piece", "requested_pieces", source = "100 paper filters", quantityText = "100"),
            high("cotton wicks", "50 cotton wicks"),
        ))
        assertThrows("Audit indices reversed against original source order", IllegalArgumentException::class.java) { prepare(swapped) }
        val valid = wireRows("50 paper filters", listOf(high("paper filters", "50 paper filters")))
        val wrongClaim = valid.replaceFirst("\"quantity_unit\":\"piece\"", "\"quantity_unit\":\"count\"")
        assertTrue(valid != wrongClaim)
        assertThrows("A count claim cannot be projected as high pieces", IllegalArgumentException::class.java) { prepare(wrongClaim) }
        assertThrows("An unprojected high count cannot bypass the discrete count cap", IllegalArgumentException::class.java) {
            prepare(wireRows("50 paper filters", listOf(high("paper filters", "50 paper filters").copy(unit = "count"))))
        }
    }

    @Test
    fun typedAbsenceAndNoAdditionStayDistinctThroughRecommendationWireAndSameSkuEvidence() {
        val cases = listOf(
            Triple("one soap without fragrance", "soap", IngredientPredicate(IngredientPredicateOperator.ABSENT, "fragrance")),
            Triple("one cereal no added sugar", "cereal", IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, "sugar")),
        )
        for ((instruction, name, predicate) in cases) {
            val item = prepare(wireRows(instruction, listOf(Row(
                name, 1, "count", "requested_pieces",
                requirements = listOf(Requirement(
                    if (predicate.operator == IngredientPredicateOperator.ABSENT) "without fragrance" else "no added sugar",
                    "ingredient", listOf(predicate),
                )),
            )))).items.single()
            val goodFields = if (predicate.operator == IngredientPredicateOperator.ABSENT) "Soap no fragrance" else "No added sugar"
            val good = candidate(name, name.replaceFirstChar(Char::uppercase), sku = "sku-$name",
                ingredientField = goodFields)
            assertTrue(isSwiggyCandidateAllowed(item, good))
            assertEquals(listOf(predicate), SwiggyMcpClient.recommendationPredicates(
                listOf(swiggyRecommendationQuery(item)), listOf(item.strictMatchPhrase),
                listOf(item.ingredientProvenance), listOf(item))?.single())
            val wrongStrength = if (predicate.operator == IngredientPredicateOperator.ABSENT) "Soap no added fragrance" else "No sugar"
            assertFalse(isSwiggyCandidateAllowed(item, candidate(name, name, sku = "sku-$name", ingredientField = wrongStrength)))
            assertFalse(isSwiggyCandidateAllowed(item, good.copy(skuId = "other-sku")))
        }
    }

    @Test
    fun familiarLiteralIngredientAlarmsHoldSellerDowngradesAndMissingOrWrongTypedClaims() {
        val cases = listOf(
            Triple("sugar-free cereal", "sugar-free", IngredientPredicate(IngredientPredicateOperator.ABSENT, "sugar")),
            Triple("no added salt chips", "no added salt", IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, "salt")),
        )
        for ((source, literal, expected) in cases) {
            val downgraded = wireRows(source, listOf(Row("item", null, "unspecified", "unstated",
                requirements = listOf(Requirement(literal, "seller")))))
            val review = GroceryIntentInterpretedReview.parse(downgraded, source)
            assertTrue(source, review.holdReasons.contains(
                GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.UNSUPPORTED_PROPERTY, 0)))
            assertThrows(IntentNeedsReviewException::class.java) { review.prepareForReview() }

            val wrongOperator = if (expected.operator == IngredientPredicateOperator.NO_ADDITION) {
                IngredientPredicate(IngredientPredicateOperator.ABSENT, expected.operand)
            } else {
                IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, expected.operand)
            }
            val wrong = GroceryIntentInterpretedReview.parse(wireRows(source, listOf(Row(
                "item", null, "unspecified", "unstated", requirements = listOf(
                    Requirement(literal, "ingredient", listOf(wrongOperator)),
                ),
            ))), source)
            assertTrue("wrong operator: $source", wrong.holdReasons.contains(
                GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.UNSUPPORTED_PROPERTY, 0)))

            assertThrows(IllegalArgumentException::class.java) { prepare(wireRows(source, listOf(Row("item",
                null, "unspecified", "unstated", requirements = listOf(Requirement(literal, "ingredient", listOf(expected))))))) }
            val faithful = prepare(wireRows(source, listOf(Row(source.substringAfterLast(' '), null, "unspecified", "unstated",
                requirements = listOf(Requirement(literal, "ingredient", listOf(expected))))))).items.single()
            assertEquals(listOf(expected), faithful.auditedIntent()!!.predicates())
        }

        val omittedSource = "gluten-free oats"
        val omitted = GroceryIntentInterpretedReview.parse(wireRows(omittedSource,
            listOf(Row("oats", null, "unspecified", "unstated"))), omittedSource)
        assertTrue(omitted.holdReasons.contains(
            GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.UNSUPPORTED_PROPERTY, 0)))
    }

    @Test
    fun unsaltedIsStillAConservativeAlarmButCannotBeInterpretedAsSaltAbsence() {
        val source = "unsalted cashews"
        val omitted = GroceryIntentInterpretedReview.parse(wireRows(source,
            listOf(Row("cashews", null, "unspecified", "unstated"))), source)
        assertTrue(omitted.holdReasons.contains(
            GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.UNSUPPORTED_PROPERTY, 0)))

        val falseTypedAbsence = GroceryIntentInterpretedReview.parse(wireRows(source, listOf(Row(
            "cashews", null, "unspecified", "unstated",
            requirements = listOf(Requirement("unsalted", "ingredient", listOf(
                IngredientPredicate(IngredientPredicateOperator.ABSENT, "salt"),
            ))),
        ))), source)
        assertTrue(falseTypedAbsence.holdReasons.contains(
            GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.UNSUPPORTED_PROPERTY, 0)))
        for (word in listOf("unsalted", "unsweetened", "unflavoured")) {
            val row = Row("cashews", null, "unspecified", "unstated", requirements = listOf(Requirement(word, "seller")))
            val faithful = prepare(wireRows("$word cashews", listOf(row))).items.single()
            assertTrue(faithful.auditedIntent()!!.predicates().isEmpty())
            for (scoped in listOf("not $word cashews", "biscuits made with $word cashews", "$word* cashews")) {
                val review = GroceryIntentInterpretedReview.parse(wireRows(scoped, listOf(row)), scoped)
                assertTrue(scoped, review.holdReasons.isNotEmpty())
            }
        }
        for (other in listOf("biscuits coated in unsalted butter", "biscuits (unsalted butter filling)")) {
            val row = Row("biscuits", null, "unspecified", "unstated", requirements = listOf(Requirement("unsalted", "seller")))
            assertTrue(GroceryIntentInterpretedReview.parse(wireRows(other, listOf(row)), other).holdReasons.isNotEmpty())
        }
    }

    @Test
    fun conservativeIngredientAlarmDocumentsBrandAndScopeFalsePositivesWithoutChangingOtherSellerClaims() {
        // This may be a brand or scoped wording, not the shopper's ingredient
        // intent. The generic alarm still holds; it never infers or rewrites meaning.
        for ((source, requirement) in listOf(
            "Sugar Free gum" to "Sugar Free",
            "gluten-free oats for breakfast" to "gluten-free",
        )) {
            val review = GroceryIntentInterpretedReview.parse(wireRows(source, listOf(Row(
                if (source.startsWith("Sugar")) "gum" else "oats", null, "unspecified", "unstated",
                requirements = listOf(Requirement(requirement, "seller")),
            ))), source)
            assertTrue(source, review.holdReasons.contains(
                GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.UNSUPPORTED_PROPERTY, 0)))
        }

        val unsweetened = "unsweetened cocoa"
        val unchangedSeller = prepare(wireRows(unsweetened, listOf(Row("cocoa", null, "unspecified", "unstated",
            requirements = listOf(Requirement("unsweetened", "seller")))))).items.single()
        assertEquals("cocoa", unchangedSeller.query)
        assertTrue(unchangedSeller.auditedIntent()!!.predicates().isEmpty())
    }

    @Test
    fun expectedBasketRejectsRemovalReorderingAndCrossBasketTransplant() {
        val instruction = "one milk packet, two bananas"
        val first = prepare(wireRows(instruction, listOf(
            Row("milk", 1, "pack", "retail_units", "pack", source = "one milk packet"),
            Row("bananas", 2, "count", "requested_pieces", source = "two bananas"),
        )))
        val second = prepare(wireRows(instruction, listOf(
            Row("milk", 1, "pack", "retail_units", "pack", source = "one milk packet"),
            Row("bananas", 2, "count", "requested_pieces", source = "two bananas"),
        )))
        val milkOffer = offer("milk", "Milk Packet", "500 ml")
        val bananaOffer = candidate("bananas", "Bananas", sku = "sku-bananas",
            quantityFields = CatalogueQuantityFields(variantName = "1 piece"))
        val candidates = mapOf(0 to milkOffer, 1 to bananaOffer)
        assertNull(SwiggyCartReviewRequest.build(emptyList(), emptyMap(), emptySet(), first.auditedBasket))
        assertNull(SwiggyCartReviewRequest.build(first.items.dropLast(1), mapOf(0 to milkOffer), emptySet(), first.auditedBasket))
        assertNull(SwiggyCartReviewRequest.build(first.items.reversed(), mapOf(0 to bananaOffer, 1 to milkOffer), emptySet(), first.auditedBasket))
        val transplanted = listOf(first.items.first(), second.items.last())
        assertNull(SwiggyCartReviewRequest.build(transplanted, candidates, emptySet(), first.auditedBasket))
        val assessed = first.items.mapIndexed { index, item -> assessSwiggyIntentQuantity(item,
            SwiggyMcpClient.Recommendations(listOf(candidates.getValue(index)))) }
        assertTrue(SwiggyCartReviewRequest.build(assessed, candidates, emptySet(), first.auditedBasket) != null)
    }

    @Test
    fun enforcedDietaryPropertyNeedNotBeRepeatedInTheTitle() {
        val predicate = IngredientPredicate(IngredientPredicateOperator.ABSENT, "gluten")
        val item = prepare(wireRows("gluten free oats", listOf(Row("oats", null, "unspecified", "unstated",
            requirements = listOf(Requirement("gluten free", "ingredient", listOf(predicate))))))).items.single()
        assertEquals("oats", item.query)
        val good = candidate("oats", "Oats", sku = "sku-oats", ingredientField = "Gluten free")
        assertTrue(isSwiggyCandidateAllowed(item, good))
        assertEquals(SwiggyCartQuantityResolution.Exact(1), resolveSwiggyCartQuantity(
            assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(good))), good))
        assertFalse(isSwiggyCandidateAllowed(item, good.copy(catalogueIngredients = null)))
        val faithful = wireRows("gluten free oats", listOf(Row("oats", null, "unspecified", "unstated",
            requirements = listOf(Requirement("gluten free", "ingredient", listOf(predicate))))))
        val omitted = faithful.replaceAfter("\"audit\":", faithful.substringAfter("\"audit\":")
            .replace(predicatesJson(listOf(predicate)), "[]"))
        assertThrows(IllegalArgumentException::class.java) { prepare(omitted) }
    }

    @Test
    fun combinedIngredientRequirementsCannotExceedRecommendationRowCapacity() {
        val predicates = (0 until 17).map {
            IngredientPredicate(IngredientPredicateOperator.ABSENT, "ingredient " + ('a' + it))
        }
        fun body(size: Int) = wireRows("oats without first group and second group", listOf(
            Row("oats", null, "unspecified", "unstated", requirements = listOf(
                Requirement("without first group", "ingredient", predicates.take(8)),
                Requirement("second group", "ingredient", predicates.drop(8).take(size - 8)),
            ))))
        val source = "oats without first group and second group"
        val withinCapacity = GroceryIntentInterpretedReview.parse(body(16), source)
        assertThrows(IntentNeedsReviewException::class.java) { withinCapacity.prepareForReview() }
        assertThrows(IllegalArgumentException::class.java) { GroceryIntentInterpretedReview.parse(body(17), source) }
    }

    @Test
    fun unsupportedPropertyTypesStayVisibleInsteadOfTrustingSellerTokenOverlap() {
        for (property in listOf("vegetarian", "low sodium", "unsweetened")) {
            val instruction = "$property biscuits"
            val hold = assertThrows(IntentNeedsReviewException::class.java) {
                prepare(wireRows(instruction, listOf(Row("biscuits", null, "unspecified", "unstated",
                    requirements = listOf(Requirement(property, "unsupported")), auditIssues = listOf("unknown")))))
            }
            assertEquals(listOf(instruction), hold.affectedSourceText)
        }
    }

    @Test
    fun completeSellerPropertySubjectCannotLoseItsRequirement() {
        val instruction = "organic organic milk"
        val prepared = prepare(wireRows(instruction, listOf(Row(instruction, null, "unspecified", "unstated",
            requirements = listOf(Requirement("organic organic", "seller"))))))
        assertEquals(instruction, requireNotNull(validatedSellerPropertyRequirement(prepared.items.single())).subject)
    }

    @Test
    fun sellerPropertiesStayAuditedAndRequireSameSkuEvidenceForFinalReview() {
        val lowFat = wireRows("low fat milk", listOf(Row(
            "milk", null, "unspecified", "unstated", requirements = listOf(Requirement("low fat", "seller")))))
        val preparation = prepare(lowFat)
        val item = preparation.items.single()
        val requirement = requireNotNull(validatedSellerPropertyRequirement(item))
        assertEquals("milk", requirement.subject)
        assertEquals(listOf("r0:7"), requirement.properties)
        assertEquals(listOf(requirement), SwiggyMcpClient.recommendationSellerProperties(
            listOf(swiggyRecommendationQuery(item)), listOf(item.strictMatchPhrase), listOf(item)))

        val unassessed = candidate("milk", "Milk low fat", sku = "sku-milk", ingredientField = "Milk low fat")
        assertFalse(isSwiggyCandidateAllowed(item, unassessed))
        val ingredients = """{"version":1,"source":"search_variation","spinId":"spin-milk","skuId":"sku-milk","fields":{"title":"Milk low fat","variantName":"Milk low fat"}}"""
        val projection = """{"spinId":"spin-milk","skuId":"sku-milk","label":"Milk low fat","variant":null,"subtitle":null,"catalogueIngredients":$ingredients}"""
        val receipt = """{"policy":"seller-assessment-v3","snapshotFingerprint":"${"a".repeat(64)}","requirement":${payloadJson(requirement)},"candidate":$projection,"properties":["r0:7"]}"""
        val rawCandidate = JsonParser(projection).parseStrict() as JsonValue.Obj
        val clean = unassessed.copy(sellerAssessment = SwiggySellerAssessment.parse(
            JsonParser(receipt).parseStrict(), rawCandidate, requirement, unassessed))
        assertTrue(isSwiggyCandidateAllowed(item, clean))
        assertFalse(isSwiggyCandidateAllowed(item, clean.copy(label = "Full cream milk")))
        assertFalse(isSwiggyCandidateAllowed(item, clean.copy(catalogueIngredients = null)))
        assertFalse(isSwiggyCandidateAllowed(item, clean.copy(catalogueIngredientsInvalid = true)))

        val tampered = item.copy(query = "full cream milk")
        assertThrows(IllegalArgumentException::class.java) { validatedSellerPropertyRequirement(tampered) }
        val downgraded = item.copy(intentQuantityProvenance = item.intentQuantityProvenance!!.copy(audited = null))
        assertThrows(IllegalArgumentException::class.java) { validatedSellerPropertyRequirement(downgraded) }

        val assessed = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(clean)))
        assertTrue(SwiggyCartReviewRequest.build(listOf(assessed), mapOf(0 to clean), emptySet(), preparation.auditedBasket) != null)
        assertNull(SwiggyCartReviewRequest.build(listOf(assessed), mapOf(0 to clean.copy(catalogueIngredients = null)),
            emptySet(), preparation.auditedBasket))

        val conflicting = candidate("milk", "Milk", sku = "sku-milk", ingredientField = "Milk full cream")
        val reconciled = SwiggyMcpClient.reconcileCatalogueVetoes(listOf(
            SwiggyMcpClient.Recommendations(listOf(clean)), SwiggyMcpClient.Recommendations(listOf(conflicting)),
        ))
        assertTrue(reconciled.all { it.candidates.single().catalogueIngredientsInvalid })
        assertFalse(isSwiggyCandidateAllowed(item, reconciled.first().candidates.single()))
    }

    @Test
    fun holdDiagnosticsCannotBeClearedToAuthorizeAnUnsupportedReview() {
        val instruction = "freshly prepared biscuits"
        val review = GroceryIntentInterpretedReview.parse(wireRows(instruction,
            listOf(Row("biscuits", null, "unspecified", "unstated",
                requirements = listOf(Requirement("freshly prepared", "unsupported")), auditIssues = listOf("unknown")))), instruction)
        assertTrue(review.holdReasons.contains(GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.UNSUPPORTED_PROPERTY, 0)))
        assertThrows(UnsupportedOperationException::class.java) {
            (review.holdReasons as MutableSet<GroceryIntentInterpretedHoldReason>).clear()
        }
        assertThrows(IntentNeedsReviewException::class.java) { review.prepareForReview() }
    }

    @Test
    fun literalRequirementBindingAndIndependentAuditMustAgree() {
        val source = "low fat milk"
        val valid = wireRows(source, listOf(Row("milk", null, "unspecified", "unstated",
            requirements = listOf(Requirement("low fat", "seller")))))
        for (tampered in listOf(
            valid.replace("\"requirements\":[[{\"source_text\":\"low fat\",\"kind\":\"seller\",\"ingredient_predicates\":[]}]]", "\"requirements\":[[]]"),
            valid.replace("\"id\":\"r0:7\"", "\"id\":\"r1:8\""),
            valid.replace("\"kind\":\"seller\"", "\"kind\":\"ingredient\""),
            valid.replace("\"source_text\":\"low fat\"", "\"source_text\":\"low\""),
            valid.replace("\"requirements\":[{\"id\":\"r0:7\",\"kind\":\"seller\",\"ingredient_predicates\":[]}]}],\"source\"",
                "\"requirements\":[]}],\"source\""),
            valid.replaceFirst("\"ingredient_predicates\":[]", "\"ingredient_predicates\":[{\"operator\":\"absent\",\"operand\":\"sugar\"}]") ,
            valid.replace("\"package_size_role\":\"unstated\"", "\"package_size_role\":\"per_unit_mass\""),
        )) assertTrue("Must reject altered source/audit requirements", runCatching {
            GroceryIntentInterpretedReview.parse(tampered, source)
        }.isFailure)
    }

    @Test
    fun physicalTotalsAndPerUnitSizesRemainDifferentDimensions() {
        val total = prepare(wireRows("half kilo rice", listOf(Row("rice", 0.5, "kg", "physical_total")))).items.single()
        val rice = offer("rice", "Rice", "250 g")
        assertEquals(SwiggyCartQuantityResolution.Exact(2), resolveSwiggyCartQuantity(
            assessSwiggyIntentQuantity(total, SwiggyMcpClient.Recommendations(listOf(rice))), rice))
        val wrongDivisor = offer("rice", "Rice", "300 g")
        assertTrue(resolveSwiggyCartQuantity(assessSwiggyIntentQuantity(total,
            SwiggyMcpClient.Recommendations(listOf(wrongDivisor))), wrongDivisor) is SwiggyCartQuantityResolution.Held)

        val source = "two milk packets 500 ml each"
        val pack = prepare(wireRows(source, listOf(Row("milk", 2, "pack", "retail_units", "packet",
            packValue = 500, packUnit = "ml", packText = "500")))).items.single()
        val correct = offer("milk", "Milk", "500 ml")
        val checked = assessSwiggyIntentQuantity(pack, SwiggyMcpClient.Recommendations(listOf(correct)))
        assertEquals(2, (resolveSwiggyCartQuantity(checked, correct) as SwiggyCartQuantityResolution.RetailProposal).cartUnits)
        val wrongSize = offer("milk", "Milk", "750 ml")
        assertTrue(resolveSwiggyCartQuantity(assessSwiggyIntentQuantity(pack,
            SwiggyMcpClient.Recommendations(listOf(wrongSize))), wrongSize) is SwiggyCartQuantityResolution.Held)
        assertThrows(IntentNeedsReviewException::class.java) {
            prepare(wireRows(source, listOf(Row("milk", 2, "pack", "retail_units", "packet"))))
        }
    }

    @Test
    fun interpretedPhysicalTotalUsesSameSkuPackSizeForExactDivisor() {
        val source = "1000 ml juice in 250 ml packs"
        val preparation = prepare(wireRows(source, listOf(Row(
            "juice", 1000, "ml", "physical_total", source = source, quantityText = "1000",
            packValue = 250, packUnit = "ml", packText = "250",
        ))))
        val item = preparation.items.single()

        assertEquals("juice 250 ml", item.query)
        assertEquals(item.query, item.strictMatchPhrase)
        assertEquals("1000 ml juice 250 ml", swiggyRecommendationQuery(item))
        val exact = candidate("juice", "Juice", sku = "sku-juice-250",
            quantityFields = CatalogueQuantityFields(variantName = "standard", packSize = "250 ml"))
        assertTrue(isSwiggyCandidateAllowed(item, exact))
        assertEquals(SwiggyCartQuantityResolution.Exact(4), resolveSwiggyCartQuantity(
            assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(exact))), exact))
        val request = SwiggyCartReviewRequest.build(
            listOf(assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(exact)))),
            mapOf(0 to exact), emptySet(), preparation.auditedBasket,
        )
        assertEquals(4, requireNotNull(request).requestedItems.single().quantity)

        for (fields in listOf(
            CatalogueQuantityFields(variantName = "standard", packSize = "300 ml"),
            CatalogueQuantityFields(variantName = "standard", packSize = "250 g"),
            CatalogueQuantityFields(variantName = "standard"),
        )) {
            val wrong = candidate("juice", "Juice", sku = "sku-juice-wrong-${fields.packSize}", quantityFields = fields)
            assertTrue(resolveSwiggyCartQuantity(assessSwiggyIntentQuantity(item,
                SwiggyMcpClient.Recommendations(listOf(wrong))), wrong) is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test
    fun interpretedPhysicalTotalTreatsTypedSingleOuterAsOneSkuNotAnExtraMeasure() {
        val source = "1 litre juice"
        val prep = prepare(wireRows(source, listOf(Row("juice", 1, "l", "physical_total", quantityText = "1"))))
        val item = prep.items.single()
        for ((fields, cartUnits) in listOf(
            CatalogueQuantityFields(packSize = "250 ml") to 4,
            CatalogueQuantityFields(packSize = "250 ml", quantityDescription = "1 bottle") to 4,
            CatalogueQuantityFields(variantName = "250 ml", quantityDescription = "1 bottle") to 4,
            CatalogueQuantityFields(variantName = "1 L", packSize = "1000 ml", quantityDescription = "1 bottle") to 1,
        )) {
            val offer = candidate("juice", "Juice", sku = "sku-juice", quantityFields = fields)
            val checked = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(offer)))
            assertEquals("Typed seller facts: $fields", SwiggyCartQuantityResolution.Exact(cartUnits),
                resolveSwiggyCartQuantity(checked, offer))
            val request = requireNotNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to offer),
                emptySet(), prep.auditedBasket))
            assertEquals(cartUnits, request.requestedItems.single().quantity)
            assertEquals(offer.skuId, request.requestedItems.single().skuId)
            assertTrue(request.quantityProposals.isEmpty())
        }
        val conflicting = candidate("juice", "Juice", sku = "sku-juice", quantityFields =
            CatalogueQuantityFields(variantName = "1 L", packSize = "500 ml", quantityDescription = "1 bottle"))
        val checked = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(conflicting)))
        assertTrue(resolveSwiggyCartQuantity(checked, conflicting) is SwiggyCartQuantityResolution.Held)
        assertNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to conflicting), emptySet(), prep.auditedBasket))
    }

    @Test
    fun finalHarEkUnitClausePreservesBothBoundAmountsAtOriginalWireOffsets() {
        val dahi = "Plain dahi ki do dibbi, 200 gram har ek."
        val chocolate = "dark chocolate ke do bar, har ek 100 g"
        val juice = "juice ke do carton, har ek 250 ml"
        fun retail(source: String, name: String, outer: String, amount: Int, unit: String, vararg seller: String) =
            Row(name, 2, "count", "retail_units", outer, source = source, quantityText = "do",
                packValue = amount, packUnit = unit, packText = amount.toString(),
                requirements = seller.map { Requirement(it, "seller") })
        val originals = listOf(retail(dahi, "Plain dahi", "tub", 200, "g", "Plain", "dibbi"),
            retail(chocolate, "Dark chocolate bar", "bar", 100, "g", "dark", "bar"),
            retail(juice, "juice", "carton", 250, "ml"))
        for (includeDibbiSellerClaims in listOf(true, false)) {
            val rows = originals.mapIndexed { i, row ->
                if (i == 0 && !includeDibbiSellerClaims) row.copy(requirements = emptyList()) else row
            }
            val source = rows.joinToString("; ") { it.source }
            val wire = wireRows(source, rows.map { row ->
                val ek = source.indexOf(row.source) + row.source.indexOf("har ek") + 4
                row.copy(unassigned = listOf(GroceryIntentReviewV2Quote("ek", ek, ek + 2)))
            })
            val projected = prepare(wire).items
            assertEquals(rows.size, projected.size)
            rows.zip(projected).forEach { (row, item) ->
                assertEquals(Quantity.Count(2), item.quantity)
                val audited = requireNotNull(item.auditedIntent())
                assertEquals("retail_units", audited.role)
                if (row.source == dahi) {
                    assertNull("dibbi is a generic household container; tub was the provider's interpretation", audited.packaging)
                    assertFalse(Regex("\\b(?:tub|cup|packet|pack)\\b", RegexOption.IGNORE_CASE).containsMatchIn(item.query))
                } else assertEquals(row.packaging, audited.packaging)
                assertEquals(row.requirements.map { it.source }, audited.sellerProperties()?.requirements.orEmpty().map { it.text })
                val atom = requireNotNull(audited.packAtom)
                assertEquals(row.packText, atom.atom)
                assertEquals(row.packUnit, atom.unit)
                assertEquals(0, atom.value.compareTo(row.packValue.toString().toBigDecimal()))
                assertEquals(row.source.indexOf(row.packText!!), atom.startUtf16) // receipt is local; wire quotes above are absolute.
                assertEquals(listOf("do", row.packText), audited.amountAtoms().map { it.atom })
            }
            if (!includeDibbiSellerClaims) {
                val item = projected.first()
                for ((variant, hasOuter) in listOf("200 g" to false, "1 tub · 200 g" to true)) {
                    val sku = candidate("dahi", "Plain dahi", variant = variant, sku = "review-dahi",
                        quantityFields = CatalogueQuantityFields(variantName = variant))
                    val offers = SwiggyMcpClient.Recommendations(listOf(sku))
                    val checked = assessSwiggyIntentQuantity(item, offers)
                    val resolution = resolveSwiggyCartQuantity(checked, sku)
                    assertEquals(hasOuter, sku in swiggyUsableCandidates(checked, offers))
                    if (hasOuter) {
                        assertTrue("requires review rather than silently binding an exact purchase",
                            resolution is SwiggyCartQuantityResolution.RetailProposal)
                        assertEquals(2, (resolution as SwiggyCartQuantityResolution.RetailProposal).cartUnits)
                        assertTrue(swiggyQuantityProposalBinding(0, checked, sku) != null)
                    } else assertTrue("a net mass alone says nothing about the sellable outer", resolution is SwiggyCartQuantityResolution.Held)
                }
            }
        }
    }

    @Test
    fun distributiveSizeCannotSilentlyApplyOnlyToLastOfTwoCoordinatedProducts() {
        val first = Row("peanut butter", 2, "count", "retail_units", "jar", source = "peanut butter ke do jar", quantityText = "do")
        val lastSource = "honey ke do jar, har ek 500 g"
        for (join in listOf(" aur ", ", ", "; ", ". ")) {
            val source = first.source + join + lastSource
            val marker = source.indexOf("har ek") + 4
            val last = Row("honey", 2, "count", "retail_units", "jar", source = lastSource, quantityText = "do",
                packValue = 500, packUnit = "g", packText = "500", unassigned = listOf(GroceryIntentReviewV2Quote("ek", marker, marker + 2)))
            val prepared = runCatching { prepare(wireRows(source, listOf(first, last))).items }.getOrNull()
            if (join == "; " || join == ". ") assertEquals(join, 2, prepared?.size) else assertNull(join, prepared)
        }
    }

    @Test
    fun harEkCannotDiscardRecipientComparisonOrOrphanAmountsAndRequiresTheExactReportedEk() {
        val prefix = "dark chocolate ke do bar, "
        val good = prefix + "har ek 100 g"
        fun raw(source: String, leftovers: List<String>, change: (Row) -> Row = { it }): String {
            val row = Row("Dark chocolate bar", 2, "count", "retail_units", "bar", source = source,
                quantityText = "do", packValue = 100, packUnit = "g", packText = "100")
            return wireRows(source, listOf(change(row).copy(unassigned = leftovers.map { token ->
                val at = Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(token) + "(?![\\p{L}\\p{N}_])")
                    .findAll(source).last().range.first
                GroceryIntentReviewV2Quote(token, at, at + token.length)
            })))
        }
        fun cannotShop(message: String, body: String) {
            val items = try { prepare(body).items } catch (_: IllegalArgumentException) { emptyList() }
            assertTrue(message, items.isEmpty())
        }
        for ((source, leftovers) in listOf(
            prefix + "har ek bacche ko 100 g" to listOf("ek"),    // each recipient is not each bar
            prefix + "100 g har ek se zyada" to listOf("ek"),   // comparison
            prefix + "har 1 100 g" to listOf("1"),              // a digit is not the word ek
            prefix + "100 g; har ek" to listOf("ek"),           // detached from its size
            prefix + "har ek 100 g, 50 g" to listOf("ek", "50"),
            prefix + "har ek 100 g, 50 g" to listOf("ek"),      // omitted leftover still exists in source
            "har ek customer ke liye do bar, har ek 100 g" to listOf("ek"), // reports only the last ek
            "dark chocolate ke do customers bar, har ek 100 g" to listOf("ek"),
            "dark chocolate ke do bar aur carton, har ek 100 g" to listOf("ek"),
            good to emptyList(), good to listOf("do"), good to listOf("ek", "ek"),
        )) cannotShop(source, raw(source, leftovers))
        cannotShop("the purchase count must exceed one", raw("dark chocolate ke 1 bar, har ek 100 g", listOf("ek")) {
            it.copy(quantity = 1, quantityText = "1")
        })
        cannotShop("an inner piece is not a sellable outer", raw(good, listOf("ek")) { it.copy(role = "requested_pieces") })
        cannotShop("wrong claimed package", raw(good, listOf("ek")) { it.copy(packaging = "carton") })
        cannotShop("wrong pack amount", raw(good, listOf("ek")) { it.copy(packValue = 150) })
        cannotShop("a seller requirement cannot own the distributive marker too", raw(good, listOf("ek")) {
            it.copy(requirements = listOf(Requirement("har ek", "seller")))
        })
        cannotShop("a number glued to a nominal is not a purchase count", raw("dark chocolate2bar, har ek 100 g", listOf("ek")) {
            it.copy(quantityText = "2")
        })
        cannotShop("a proposed loaf is not a source-backed household container", raw("dark chocolate ke do dibbi, har ek 100 g", listOf("ek")) {
            it.copy(name = "dark chocolate", packaging = "loaf")
        })
        cannotShop("wrong physical dimension", raw(good, listOf("ek")) { it.copy(packUnit = "ml") })
        cannotShop("orphan 100 unbound", raw(good, listOf("ek")) {
            it.copy(packValue = null, packUnit = "unspecified", packText = null)
        })
        val first = "filter coffee ke do bag, har ek 250 g"
        val both = "$first; $good"
        val firstEk = first.indexOf("har ek") + 4
        val wrongRow = GroceryIntentReviewV2Quote("ek", firstEk, firstEk + 2)
        cannotShop("both occurrences spell ek, but belong to different primaries", wireRows(both, listOf(
            Row("filter coffee", 2, "count", "retail_units", "bag", source = first, quantityText = "do",
                packValue = 250, packUnit = "g", packText = "250", unassigned = listOf(wrongRow)),
            Row("Dark chocolate bar", 2, "count", "retail_units", "bar", source = good, quantityText = "do",
                packValue = 100, packUnit = "g", packText = "100", unassigned = listOf(wrongRow)),
        )))
    }

    @Test
    fun oneOuterAndMetricFromTheSameSkuReachesAnExactOrExplicitlyProposedCartReview() {
        for ((name, outer, amount) in listOf(Triple("quinoa seeds", "packet", "250 g"),
            Triple("juice", "carton", "250 ml"), Triple("peanut butter", "jar", "100 g"))) {
            val (magnitude, unit) = amount.split(' ')
            val n = magnitude.toInt()
            val fields = CatalogueQuantityFields(variantName = "1 $outer · $amount")
            val exactSku = candidate(name, name, variant = fields.variantName, sku = "live-$outer", quantityFields = fields)
            val total = "500 $unit $name in $n $unit packs"
            val count = "two ${outer}s of $name $n $unit each"
            for ((source, row, expected) in listOf(
                Triple(total, Row(name, 500, unit, "physical_total", quantityText = "500",
                    packValue = n, packUnit = unit, packText = magnitude), 500 / n),
                Triple(count, Row(name, 2, "pack", "retail_units", outer, quantityText = "two",
                    packValue = n, packUnit = unit, packText = magnitude), 2),
            )) {
                val prep = prepare(wireRows(source, listOf(row)))
                val offers = SwiggyMcpClient.Recommendations(listOf(exactSku))
                val checked = assessSwiggyIntentQuantity(prep.items.single(), offers)
                assertEquals("$source: eligible offer", listOf(exactSku), swiggyUsableCandidates(checked, offers))
                val review = requireNotNull(SwiggyCartReviewRequest.build(listOf(checked),
                    mapOf(0 to exactSku), emptySet(), prep.auditedBasket)) { "$source: no dry cart review" }
                assertEquals(expected, review.requestedItems.single().quantity)
                assertEquals(exactSku.skuId, review.requestedItems.single().skuId)
                assertEquals(row.role == "retail_units", review.quantityProposals.isNotEmpty())
            }
        }
    }

    @Test
    fun joinedMetricCannotLaunderAChangedProductAnUnboundOrConflictingSizeOrAMultipack() {
        val source = "two packets of quinoa seeds 250 g each"
        val prep = prepare(wireRows(source, listOf(Row("quinoa seeds", 2, "pack", "retail_units", "packet",
            quantityText = "two", packValue = 250, packUnit = "g", packText = "250"))))
        val wanted = "1 packet · 250 g"
        fun offer(joined: String = wanted, title: String = "quinoa seeds", otherSize: String? = null) =
            candidate("seeds", title, variant = joined, sku = "seed-unit", quantityFields =
                CatalogueQuantityFields(variantName = joined, packSize = otherSize))
        val real = offer()
        val unrelated = offer(title = "quinoa flour")
        val wrongId = real.copy(skuId = "other-variation")
        val borrowedField = real.copy(catalogueQuantity = real.catalogueQuantity!!.copy(spinId = "sibling"))
        val noOwnFact = real.copy(catalogueQuantity = null)
        val duplicateReceipt = """{"version":1,"source":"search_variation","spinId":"spin-seeds","skuId":"seed-unit","fields":{"variantName":"$wanted","variantName":"1 packet · 500 g"}}"""
        assertThrows(IllegalArgumentException::class.java) { JsonParser(duplicateReceipt).parseStrict() }
        assertNull(parseCatalogueQuantityEvidence(JsonParser(duplicateReceipt).parse(), "spin-seeds", "seed-unit"))
        for (bad in listOf(unrelated, wrongId, borrowedField, noOwnFact,
            offer(otherSize = "500 g"), offer(otherSize = "250 ml"), offer("1 packet · 500 g"),
            offer("2 packets · 250 g"), offer("1 packet · 2 × 125 g"), offer("1 packet · 250 g · 500 g"),
            offer("1 packet · about 250 g"), offer("250 g · 1 packet"), offer("1 packet · 0 g"),
            real.copy(catalogueQuantity = null, catalogueQuantityInvalid = true))) {
            val offers = SwiggyMcpClient.Recommendations(listOf(bad), suggested = bad)
            val checked = assessSwiggyIntentQuantity(prep.items.single(), offers)
            assertTrue(bad.label, swiggyUsableCandidates(checked, offers).isEmpty())
            assertNull(bad.label, SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to bad), emptySet(), prep.auditedBasket))
        }
        val conflictingCopy = offer(otherSize = "500 g")
        for ((a, b) in listOf(real to conflictingCopy, conflictingCopy to real)) {
            val reconciled = SwiggyMcpClient.reconcileCatalogueVetoes(listOf(
                SwiggyMcpClient.Recommendations(listOf(a)), SwiggyMcpClient.Recommendations(listOf(b))))
            for (offers in reconciled) {
                val checked = assessSwiggyIntentQuantity(prep.items.single(), offers)
                assertTrue("same SKU's other observation vetoes both", swiggyUsableCandidates(checked, offers).isEmpty())
                assertNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to offers.candidates.single()), emptySet(), prep.auditedBasket))
            }
        }
    }

    @Test
    fun interpretedRetailUnitsWithPackAtomAndTypedSingleOuterStillRequireProposal() {
        val source = "two flour packets 250 g each"
        val prep = prepare(wireRows(source, listOf(Row("flour", 2, "pack", "retail_units", "packet",
            quantityText = "two", packValue = 250, packUnit = "g", packText = "250"))))
        val item = prep.items.single()
        for (fields in listOf(
            CatalogueQuantityFields(variantName = "250 g"),
            CatalogueQuantityFields(variantName = "250 g", quantityDescription = "1 packet"),
            CatalogueQuantityFields(packSize = "250 g", quantityDescription = "1 packet"),
        )) {
            val offer = candidate("flour", "Flour", sku = "sku-flour", quantityFields = fields)
            val checked = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(offer)))
            val resolution = resolveSwiggyCartQuantity(checked, offer)
            assertTrue("Expected proposal for $fields: $resolution", resolution is SwiggyCartQuantityResolution.RetailProposal)
            assertEquals(2, (resolution as SwiggyCartQuantityResolution.RetailProposal).cartUnits)
            val request = requireNotNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to offer),
                emptySet(), prep.auditedBasket))
            assertEquals(2, request.requestedItems.single().quantity)
            assertEquals(offer.skuId, request.quantityProposals.single().skuId)
        }
    }

    @Test
    fun interpretedSingleOuterDoesNotRescueUnboundConflictingOrDisplayOnlySizes() {
        val source = "two flour packets 250 g each"
        val retail = prepare(wireRows(source, listOf(Row("flour", 2, "pack", "retail_units", "packet",
            quantityText = "two", packValue = 250, packUnit = "g", packText = "250"))))
        val total = prepare(wireRows("1 kg flour", listOf(Row("flour", 1, "kg", "physical_total", quantityText = "1"))))
        val fields = CatalogueQuantityFields(packSize = "250 g", quantityDescription = "1 packet")
        fun offer(raw: CatalogueQuantityFields, label: String = "Flour") =
            candidate("flour", label, sku = "sku-flour", quantityFields = raw)
        val invalid = mapOf(
            "stale SKU" to offer(fields).copy(skuId = "changed-sku"),
            "multiple outers" to offer(fields.copy(quantityDescription = "2 bottles")),
            "bundle count" to offer(fields.copy(variantName = "pack of 2")),
            "composite mass" to offer(fields.copy(variantName = "4 x 125 g")),
            "inner count" to offer(fields.copy(quantityDescription = "1 tablet")),
            "extras" to offer(fields.copy(quantityDescription = "1 packet + extra")),
            "display-only size" to offer(fields.copy(packSize = null), "Flour 250 g"),
            "wrong dimension" to offer(fields.copy(packSize = "250 ml")),
            "conflicting mass" to offer(fields.copy(variantName = "500 g")),
            "unowned title numeral" to offer(fields, "Flour 7"),
            "title container contradicts typed outer" to offer(fields, "Flour Bottle"),
        )
        for (prep in listOf(retail, total)) for ((reason, bad) in invalid) {
            val checked = assessSwiggyIntentQuantity(prep.items.single(), SwiggyMcpClient.Recommendations(listOf(bad)))
            assertTrue(reason, resolveSwiggyCartQuantity(checked, bad) is SwiggyCartQuantityResolution.Held)
            assertNull(reason, SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to bad), emptySet(), prep.auditedBasket))
        }
        val genericPack = offer(fields.copy(quantityDescription = "1 pack"))
        val packetCheck = assessSwiggyIntentQuantity(retail.items.single(), SwiggyMcpClient.Recommendations(listOf(genericPack)))
        assertTrue("Generic pack cannot establish an explicitly requested packet", resolveSwiggyCartQuantity(packetCheck,
            genericPack) is SwiggyCartQuantityResolution.Held)
        assertNull(SwiggyCartReviewRequest.build(listOf(packetCheck), mapOf(0 to genericPack), emptySet(), retail.auditedBasket))
        val named = prepare(wireRows("two bottles of juice 250 ml each", listOf(Row("juice", 2, "count",
            "retail_units", "bottle", quantityText = "two", packValue = 250, packUnit = "ml", packText = "250"))))
        val wrongContainer = candidate("juice", "Juice Bottle", sku = "sku-juice",
            quantityFields = CatalogueQuantityFields(packSize = "250 ml", quantityDescription = "1 packet"))
        val checked = assessSwiggyIntentQuantity(named.items.single(), SwiggyMcpClient.Recommendations(listOf(wrongContainer)))
        assertTrue(resolveSwiggyCartQuantity(checked, wrongContainer) is SwiggyCartQuantityResolution.Held)
        assertNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to wrongContainer), emptySet(), named.auditedBasket))
    }

    @Test
    fun standardVariantDoesNotMaskBoundPackSizeOrConflictingSizeClaims() {
        val source = "1000 ml juice in 250 ml packs"
        val item = prepare(wireRows(source, listOf(Row(
            "juice", 1000, "ml", "physical_total", source = source, quantityText = "1000",
            packValue = 250, packUnit = "ml", packText = "250",
        )))).items.single()
        val good = candidate("juice", "Juice", sku = "sku-standard-250",
            quantityFields = CatalogueQuantityFields(variantName = "standard", packSize = "250 ml"))
        assertTrue(isSwiggyCandidateAllowed(item, good))
        assertEquals(SwiggyCartQuantityResolution.Exact(4), resolveSwiggyCartQuantity(
            assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(good))), good))

        val cases = listOf(
            CatalogueQuantityFields(variantName = "250 ml", packSize = "300 ml"),
            CatalogueQuantityFields(variantName = "standard", packSize = "250 ml / 300 ml"),
            CatalogueQuantityFields(variantName = "standard", packSize = "250-300 ml"),
            CatalogueQuantityFields(variantName = "standard", packSize = "250 ml", quantityDescription = "300 ml"),
        )
        for ((index, fields) in cases.withIndex()) {
            val conflicting = candidate("juice", "Juice", sku = "sku-conflict-$index", quantityFields = fields)
            assertTrue("conflicting/repeated/ranged pack evidence $fields", resolveSwiggyCartQuantity(
                assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(conflicting))), conflicting)
                is SwiggyCartQuantityResolution.Held)
        }
        val staleSku = good.copy(skuId = "sku-renamed")
        assertTrue(resolveSwiggyCartQuantity(assessSwiggyIntentQuantity(item,
            SwiggyMcpClient.Recommendations(listOf(staleSku))), staleSku) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun standardParserAndInterpretedFixtureReachTheSameOfflineCandidateBoundary() {
        val source = "1000 ml juice in 250 ml packs"
        val standardItems = prepareSwiggyMcpItems(source) { null }
        val interpretedPreparation = prepare(wireRows(source, listOf(Row(
            "juice", 1000, "ml", "physical_total", source = source, quantityText = "1000",
            packValue = 250, packUnit = "ml", packText = "250",
        ))))
        val interpretedItem = interpretedPreparation.items.single()
        val exact = candidate("juice", "Juice", sku = "sku-juice-250",
            quantityFields = CatalogueQuantityFields(variantName = "standard", packSize = "250 ml"))
        val wrongSize = candidate("juice", "Juice", sku = "sku-juice-300",
            quantityFields = CatalogueQuantityFields(variantName = "standard", packSize = "300 ml"))
        val wrongDimension = candidate("juice", "Juice", sku = "sku-juice-250-g",
            quantityFields = CatalogueQuantityFields(variantName = "standard", packSize = "250 g"))

        fun selectedReview(item: com.example.beta.automation.ParsedItem,
                           basket: AuditedIntentBasket?,
                           offer: RecommendationCandidate): Pair<RecommendationCandidate, SwiggyCartReviewRequest>? {
            val recommendations = SwiggyMcpClient.Recommendations(listOf(offer))
            val checked = assessSwiggyIntentQuantity(item, recommendations)
            val usable = swiggyUsableCandidates(checked, recommendations)
            val selected = usable.takeIf { it.isNotEmpty() }?.let {
                swiggyDefaultSuggestion(item, it, recommendations.suggested)
            } ?: return null
            val request = SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to selected), emptySet(), basket)
                ?: return null
            return selected to request
        }

        // Interpreted fixture is the positive control for this same source and offer.
        val interpretedReview = requireNotNull(selectedReview(interpretedItem, interpretedPreparation.auditedBasket, exact))
        assertEquals("sku-juice-250", interpretedReview.first.skuId)
        assertEquals(4, interpretedReview.second.requestedItems.single().quantity)

        // Measure Standard parser capability separately: a missing or held result is reported,
        // never converted into an expected success. Any selected result must still be exact.
        val standardOutcome = when {
            standardItems.size != 1 -> "not_single_row"
            else -> {
                val standard = standardItems.single()
                val checked = assessSwiggyIntentQuantity(standard, SwiggyMcpClient.Recommendations(listOf(exact)))
                val result = selectedReview(standard, null, exact)
                if (result == null) {
                    when {
                        standard.quantity != Quantity.Volume(1000) -> "total_not_retained"
                        standard.strictMatchPhrase != "juice 250 ml" -> "unit_identity_not_retained"
                        !isSwiggyCandidateAllowed(checked, exact) -> "candidate_not_verified"
                        else -> "quantity_or_review_held"
                    }
                } else {
                    assertEquals(SwiggyCartQuantityResolution.Exact(4), resolveSwiggyCartQuantity(checked, exact))
                    assertEquals("sku-juice-250", result.first.skuId)
                    assertEquals(4, result.second.requestedItems.single().quantity)
                    "exact_four"
                }
            }
        }
        System.out.println("standard_parser_characterization=$standardOutcome")

        // Wrong-size and wrong-dimension offers must remain unusable on either route.
        val safetyCases: List<Pair<com.example.beta.automation.ParsedItem, AuditedIntentBasket?>> = standardItems.map { it to null } +
            listOf(interpretedItem to interpretedPreparation.auditedBasket)
        for ((item, basket) in safetyCases) {
            // Both offers could meet the TOTAL exactly, but neither has the requested pack size.
            for (size in listOf(500, 1000)) {
                val visible = candidate("juice", "Juice", variant = "$size ml", sku = "sku-divisor-$size",
                    quantityFields = CatalogueQuantityFields(variantName = "$size ml"))
                assertNull("A divisible total cannot substitute a different pack size",
                    selectedReview(item, basket, visible))
            }
            for (wrong in listOf(wrongSize, wrongDimension)) {
                val recommendations = SwiggyMcpClient.Recommendations(listOf(wrong))
                val checked = assessSwiggyIntentQuantity(item, recommendations)
                assertTrue(swiggyUsableCandidates(checked, recommendations).isEmpty())
                assertTrue(resolveSwiggyCartQuantity(checked, wrong) is SwiggyCartQuantityResolution.Held)
                assertNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to wrong), emptySet(), basket))
            }
        }
    }

    @Test
    fun syntheticBackendPackBatchDecodesIntoOnlyVerifiedOfflineCartReview() {
        val fixture = System.getenv("BETA_SYNTHETIC_PACK_BATCH")
        assumeTrue("BETA_SYNTHETIC_PACK_BATCH is not set", fixture != null)
        val groups = (JsonParser(requireNotNull(fixture)).parseStrict() as JsonValue.Arr).items
        assertEquals(4, groups.size)
        for ((idx, title) in listOf("Juice", "Arbor Juice").withIndex()) {
            val batchValues = (groups[idx] as JsonValue.Arr).items
            assertEquals(if (idx == 0) 3 else 5, batchValues.size)
            checkSyntheticPackGroup(title, batchValues, if (idx == 0) null else "Arbor")
        }
        for ((idx, subject) in listOf("oats" to "g", "juice" to "ml").withIndex()) {
            checkSyntheticCapGroup(subject.first, subject.second, (groups[idx + 2] as JsonValue.Arr).items)
        }
    }

    private fun checkSyntheticCapGroup(name: String, unit: String, wires: List<JsonValue>) {
        val source = "1000 $unit $name"
        val prep = prepare(wireRows(source, listOf(Row(name, 1000, unit, "physical_total", source = source, quantityText = "1000"))))
        val item = prep.items.single()
        val queries = listOf(swiggyRecommendationQuery(item))
        val strict = listOf(item.strictMatchPhrase)
        val ingredients = SwiggyMcpClient.recommendationPredicates(queries, strict, listOf(item.ingredientProvenance), listOf(item))
        val sellers = SwiggyMcpClient.recommendationSellerProperties(queries, strict, listOf(item))
        assertEquals(3, wires.size)
        val parsed = wires.map {
            val batch = SwiggyMcpClient.parseRecommendationBatch((it as JsonValue.Str).value, ingredients, sellers)
            assertTrue(areSwiggyRecommendationsOrdered(batch, queries))
            batch.single()
        }
        val base = parsed.first()
        val all = base.candidates.associateBy { it.skuId }
        assertEquals(setOf("sku-one", "sku-two", "sku-valid", "sku-unknown"), all.keys)
        assertNull(all.getValue("sku-unknown").cataloguePurchaseLimit)
        val checkedBase = assessSwiggyIntentQuantity(item, base)
        for (typed in parsed.drop(1)) {
            val remaining = typed.candidates.map { it.skuId }.toSet()
            assertEquals(setOf("sku-valid", "sku-unknown"), remaining)
            for (removed in all.keys - remaining) {
                assertEquals(SwiggyCartQuantityResolution.Held("seller_limit_exceeded"),
                    resolveSwiggyCartQuantity(checkedBase, all.getValue(removed)))
            }
            val viable = typed.candidates.single { it.skuId == "sku-valid" }
            val checked = assessSwiggyIntentQuantity(item, typed)
            assertEquals(SwiggyCartQuantityResolution.Exact(4), resolveSwiggyCartQuantity(checked, viable))
            val review = requireNotNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to viable),
                emptySet(), prep.auditedBasket)).requestedItems.single()
            assertEquals(viable.skuId, review.skuId)
            assertEquals(4, review.quantity)
        }
    }

    private fun checkSyntheticPackGroup(title: String, batchValues: List<JsonValue>, expectedBrand: String?) {
        val responseBodies = batchValues.map { (it as JsonValue.Str).value }

        val name = title.lowercase(java.util.Locale.ROOT)
        val source = "1000 ml $name in 250 ml packs"
        val preparation = prepare(wireRows(source, listOf(Row(
            name, 1000, "ml", "physical_total", source = source, quantityText = "1000",
            packValue = 250, packUnit = "ml", packText = "250",
        ))))
        val item = preparation.items.single()
        val queries = listOf(swiggyRecommendationQuery(item))
        val strict = listOf(item.strictMatchPhrase)
        val ingredients = SwiggyMcpClient.recommendationPredicates(queries, strict,
            listOf(item.ingredientProvenance), listOf(item))
        val sellers = SwiggyMcpClient.recommendationSellerProperties(queries, strict, listOf(item))
        val parsed = responseBodies.map { body ->
            val envelope = JsonParser(body).parseStrict() as JsonValue.Obj
            assertEquals(JsonValue.Num("2"), envelope.members["sellerPropertyVersion"])
            assertEquals(JsonValue.Str(SwiggySellerAssessment.POLICY), envelope.members["sellerAssessmentPolicy"])
            val batch = SwiggyMcpClient.parseRecommendationBatch(body, ingredients, sellers)
            assertTrue(areSwiggyRecommendationsOrdered(batch, queries))
            batch.single()
        }

        val exactOffers = parsed[0].candidates
        assertEquals(1, exactOffers.size)
        val exact = exactOffers.single()
        assertEquals("juice-250-sku", exact.skuId)
        assertEquals("$title · standard", exact.label)
        assertEquals(expectedBrand, exact.catalogueBrand?.name)
        assertEquals("250 ml", exact.catalogueQuantity?.fields?.packSize)
        val checked = assessSwiggyIntentQuantity(item, parsed[0])
        assertEquals(SwiggyCartQuantityResolution.Exact(4), resolveSwiggyCartQuantity(checked, exact))
        val readyReview = SwiggyCartReviewRequest.build(
            listOf(checked), mapOf(0 to exact), emptySet(), preparation.auditedBasket,
        )
        assertEquals(4, requireNotNull(readyReview).requestedItems.single().quantity)
        assertEquals("juice-250-sku", readyReview.requestedItems.single().skuId)

        for ((index, response) in parsed.drop(1).withIndex()) {
            assertTrue("Response ${index + 2} must not expose a selectable candidate", response.candidates.isEmpty())
            val noReview = SwiggyCartReviewRequest.build(
                listOf(assessSwiggyIntentQuantity(item, response)), emptyMap(), emptySet(), preparation.auditedBasket,
            )
            assertNull("Response ${index + 2} must not reach a ready cart review", noReview)
        }
    }

    @Test
    fun completeReviewRetainsBackgroundMissingRowsAndInvalidatesOldConsent() {
        val source = "please, one milk packet"
        val body = wireRows(source, listOf(Row("milk", 1, "pack", "retail_units", "packet", source = "one milk packet")))
        val preparation = prepare(body)
        assertTrue(preparation.excludedText.isEmpty())
        val basket = requireNotNull(preparation.auditedBasket)
        assertEquals("please, ", basket.backgroundProposals.single().source.text)
        val display = groceryIntentContextReviewRows(basket.backgroundProposals, {}, {})
        assertTrue(display.any { it.title == "please, " && it.detail == "Used as background, not as an item" })
        assertEquals(1, swiggyOrderedCartReviewRows(preparation.items, emptyMap(), emptySet(), emptyList(), onChange = {}).size)
        val candidate = offer("milk", "Milk", "500 ml")
        fun binding(prep: GroceryIntentPreparation) = requireNotNull(swiggyQuantityProposalBinding(0,
            assessSwiggyIntentQuantity(prep.items.single(), SwiggyMcpClient.Recommendations(listOf(candidate))), candidate))
        val old = binding(preparation)
        val gate = SwiggyQuantityProposalGate()
        gate.prepare(1L, "address", "plan", listOf(old))
        assertTrue(gate.acknowledge(1L, "address", "plan", listOf(old), true))
        assertTrue(gate.canApply(1L, "address", "plan", listOf(old)))
        val newRevision = binding(prepare(body))
        assertFalse(gate.canApply(1L, "address", "plan", listOf(newRevision)))
        assertFalse(auditedIntentBasketMatches(preparation.items, "another request", basket))
    }

    @Test
    fun detachedDietaryClausesHoldTheEntireOriginalInstruction() {
        val cases = listOf(
            "one green tea. I’m trying to avoid green tea today." to "one green tea",
            "one glass. Not drinking glasses, please." to "one glass",
            "one milk. Regular nahi." to "one milk",
            "Without peanuts. one milk" to "one milk",
            "Sugar-free only. one milk" to "one milk",
            "I am allergic to peanuts. one milk" to "one milk",
            "one milk. No worries, I can’t have regular milk." to "one milk",
            "one milk. Dont send the other brand." to "one milk",
            "one milk. Except the flavoured one." to "one milk",
            "one milk. Also two bread rolls." to "one milk",
            "one milk. Regular नहीं." to "one milk",
        )
        for ((instruction, itemSource) in cases) {
            val body = wireRows(instruction, listOf(Row(
                itemSource.substringAfter("one "), 1, "count", "retail_units", source = itemSource,
            )))
            val parsed = GroceryIntentInterpretedReview.parse(body, instruction)
            assertEquals(instruction, setOf(GroceryIntentInterpretedHoldCode.UNSUPPORTED_CONTEXT),
                parsed.holdReasons.map { it.code }.toSet())
            val hold = assertThrows(IntentNeedsReviewException::class.java) {
                parsed.prepareForReview()
            }
            assertEquals(instruction, hold.affectedSourceText.single())
        }
    }

    @Test
    fun croppedOriginalCannotTurnAFractionIntoAnIntegerOrAMeasureIntoPieces() {
        val packets = "do milk packets"
        val quantity = Row("milk", 2, "count", "retail_units", "packet", source = packets, quantityText = "do")
        for (prefix in listOf("sawa ", "saadhe ", "paune ", "सवा ", "minus ", "−", "🙂 sawa ")) {
            val original = prefix + packets
            assertTrue(original, runCatching { prepare(wireRows(original, listOf(quantity))) }.isFailure)
        }
        val cropped = "milk two"
        for (tail in listOf(" litres", " dozen", " cartons", " + 1")) {
            val original = cropped + tail
            val wrong = Row("milk", 2, "count", "requested_pieces", source = cropped, quantityText = "two")
            assertTrue(original, runCatching { prepare(wireRows(original, listOf(wrong))) }.isFailure)
        }
        val pears = Row("pears", 2, "count", "requested_pieces", source = "two pears", quantityText = "two")
        for (prefix in listOf("please ", "🙂 please ")) {
            assertEquals(Quantity.Count(2), prepare(wireRows(prefix + pears.source, listOf(pears))).items.single().quantity)
        }
        val apples = pears.copy(name = "apples", source = "two apples")
        assertEquals(listOf(Quantity.Count(2), Quantity.Count(2)),
            prepare(wireRows("two pears and two apples", listOf(pears, apples))).items.map { it.quantity })
    }

    @Test
    fun discreteCountCannotDiscardItsUnitInsideOrOutsideTheRow() {
        fun row(name: String, source: String, form: String? = null) = Row(name, 2, "count",
            if (form == null) "requested_pieces" else "retail_units", form, source = source, quantityText = "two")
        val held = listOf("foil two metres" to row("foil", "foil two"),
            "foil two metres" to row("foil", "foil two metres"),
            "two metres of foil" to row("foil", "two metres of foil"),
            "milk two servings" to row("milk", "milk two servings"),
            "juice two flarps" to row("juice", "juice two flarps"),
            "two foil rolls" to row("foil", "two foil rolls"),
            "foil two, metres" to row("foil", "foil two"),
            "doodh two din ke liye" to row("doodh", "doodh two"),
            "doodh two din ke liye" to row("milk", "doodh two din ke liye"),
            "more than two milk packets" to row("milk", "two milk packets", "packet"),
            "more than two milk packets" to row("milk", "more than two milk packets", "packet"),
            "lagbhag two milk packets" to row("milk", "two milk packets", "packet"))
        for ((original, claim) in held) assertTrue(original, runCatching { prepare(wireRows(original, listOf(claim))) }.isFailure)
        for (extra in listOf(" -3.75", ", ३", " + 2")) assertTrue(extra, runCatching {
            prepare(wireRows("foil" + extra, listOf(Row("foil", null, "unspecified", "unstated", source = "foil"))))
        }.isFailure)
        for (claim in listOf(row("foil", "foil two"), row("foil", "foil two please"),
                row("foil", "two foil rolls", "roll"), row("bottle openers", "two bottle openers"))) {
            assertEquals(claim.source, Quantity.Count(2), prepare(wireRows(claim.source, listOf(claim))).items.single().quantity)
        }
        assertEquals(2, prepare(wireRows("foil two and milk two", listOf(row("foil", "foil two"), row("milk", "milk two")))).items.size)
    }

    @Test
    fun amountEdgesSeparateListIntroductionsFromQuantityConditions() {
        val claim = Row("pears", 2, "piece", "requested_pieces", source = "two pears", quantityText = "two")
        for (frame in listOf("Could I have ", "May we please order ", "Weekly list please: ", "Our monthly shopping list: ",
                "For dinner, ", "For breakfast: ")) {
            val original = frame + claim.source
            val quantity = runCatching { prepare(wireRows(original, listOf(claim))).items.single().quantity }
                .getOrElse { throw AssertionError(original, it) }
            assertEquals(original, Quantity.Count(2), quantity)
        }
        for (frame in listOf("Could I have at least ", "Only if available: ", "By dinner, ", "For diabetics, ",
                "For two dinners: ", "Weekly list without salt: ", "Rice and milk: ")) {
            val original = frame + claim.source
            assertTrue(original, runCatching { prepare(wireRows(original, listOf(claim))) }.isFailure)
        }
    }

    @Test
    fun pluralMeasuredOuterNeedsOneExactSkuFormAndTheWholeSellerTitle() {
        for ((noun, forms) in listOf("tofu" to ("blocks" to "block"), "soap" to ("bars" to "bar"))) {
            val (plural, form) = forms
            val original = "two 200 g $plural of $noun"
            val item = prepare(wireRows(original, listOf(Row("$noun $plural", 2, "count", "retail_units",
                form, quantityText = "two", packValue = 200, packUnit = "g", packText = "200")))).items.single()
            val base = candidate(noun, noun, sku = "sku", quantityFields = CatalogueQuantityFields())
            fun offered(title: String, variant: String, second: String = title): RecommendationCandidate {
                val names = CatalogueIngredientEvidence.parse(JsonParser("""{"version":1,"source":"search_variation","spinId":"${base.spinId}","skuId":"sku","fields":{"name":"$title","title":"$second"}}""").parseStrict(),
                    base.spinId, "sku", emptySet())
                return base.copy(label = "$title · $variant", variant = variant, catalogueIngredients = names,
                    catalogueQuantity = base.catalogueQuantity!!.copy(fields = CatalogueQuantityFields(variantName = variant)))
            }
            fun matches(offer: RecommendationCandidate) = swiggyMatchesProductIdentity(item, offer.label,
                listOf(offer.label.substringBefore(" · "), offer.variant!!), offer)
            val correct = offered(noun, "1 $form · 200 g")
            assertTrue(noun, matches(correct))
            for (bad in listOf(offered(noun, "1 $form · 400 g"), offered(noun, "2 $plural · 200 g"),
                    offered("spiced $noun", "1 $form · 200 g"), offered(noun, "1 $form · 200 g", "spiced $noun"),
                    correct.copy(skuId = "wrong-sku"), correct.copy(catalogueIngredients = null))) {
                assertFalse("$noun ${bad.label}", isSwiggyCandidateAllowed(item, bad))
            }
        }
    }

    @Test
    fun enclosingAnUnboundExclusionInTheProductQuoteDoesNotProveIt() {
        val source = "one green tea, avoid the lemon brand"
        val body = wireRows(source, listOf(Row("green tea", 1, "count", "retail_units", source = source)))
        val parsed = GroceryIntentInterpretedReview.parse(body, source)
        assertTrue(parsed.holdReasons.any { it.code == GroceryIntentInterpretedHoldCode.UNSUPPORTED_PROPERTY })
        assertThrows(IntentNeedsReviewException::class.java) { parsed.prepareForReview() }
    }

    @Test
    fun cleanAuditsCannotEraseConditionsTimingOrCorrectionsInsideOrOutsideAnItemQuote() {
        val pears = Row("pears", 2, "piece", "requested_pieces", quantityText = "two")
        val tea = Row("tea", 2, "pack", "retail_units", "pack", quantityText = "two")
        val cases = listOf(
            Triple("two pears, only if ripe", pears, "two pears"),
            Triple("two, unless bruised, pears", pears, null),
            Triple("two tea packets, after lunch", tea, "two tea packets"),
            Triple("two, tomorrow morning, tea packets", tea, null),
            Triple("two pears, actually apples instead", pears, "two pears"),
            Triple("two pears, actually make it apples", pears, "two pears"),
            Triple("two pears, no wait, apples", pears, null),
            Triple("two pears provided that the shop can deliver", pears, "two pears"),
            Triple("two pears agar fresh ho toh", pears, "two pears"),
            Triple("two pears agar fresh ho", pears, null),
            Triple("two tea packets before breakfast", tea, null),
        )
        for ((instruction, row, narrowPrimary) in cases) {
            // Both models agree on the unqualified purchase. Test both hiding
            // the clause inside the row and attributing its suffix to background.
            for (primary in listOfNotNull(instruction, narrowPrimary)) {
                val body = wireRows(instruction, listOf(row.copy(source = primary)))
                val held = assertThrows("$instruction; primary=$primary", IntentNeedsReviewException::class.java) {
                    prepare(body)
                }
                assertEquals(instruction, held.affectedSourceText.single())
            }
        }
    }

    @Test
    fun cleanAuditsStillReviewSpokenFillersAndProductWordsThatResembleConditions() {
        val pears = Row("pears", 2, "piece", "requested_pieces", quantityText = "two")
        val cases = listOf(
            Triple("two, please, pears", pears, offer("pears", "Pears", "1 piece")),
            Triple("two, you know, pears", pears, offer("pears", "Pears", "1 piece")),
            Triple("two agar agar packets", Row("agar agar", 2, "pack", "retail_units", "pack"),
                offer("agar", "Agar Agar Packet", "50 g")),
        )
        for ((instruction, row, candidate) in cases) {
            val prep = prepare(wireRows(instruction, listOf(row.copy(source = instruction))))
            val checked = assessSwiggyIntentQuantity(prep.items.single(), SwiggyMcpClient.Recommendations(listOf(candidate)))
            val cart = SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet(), prep.auditedBasket)
            val requested = requireNotNull(cart) { instruction }.requestedItems.single()
            assertEquals(instruction, candidate.skuId to 2, requested.skuId to requested.quantity)
        }
    }

    @Test
    fun aDietaryClaimCannotBecomeChatterBySplittingItsAuditSpans() {
        val primary = "one milk packet"
        val source = "$primary. Sugar-free only."
        val spans = listOf(primary.length, source.indexOf("Sugar"), source.indexOf("-free"), source.length)
        fun quote(start: Int, end: Int) = quotePayload(GroceryIntentReviewV2Quote(source.substring(start, end), start, end))
        fun background(q: String) = """{"source":$q,"role":"background","row_indices":[]}"""
        val split = spans.zipWithNext().map { (start, end) -> quote(start, end) }
        val body = wireRows(source, listOf(Row("milk", 1, "pack", "retail_units", "packet", source = primary)))
            .replace(background(quote(primary.length, source.length)), split.joinToString(",") { background(it) })
            .replace("\"context\":[]", "\"context\":[" + split.joinToString(",") { """{"kind":"background","source":$it}""" } + "]")
        assertEquals(setOf(GroceryIntentInterpretedHoldCode.UNSUPPORTED_CONTEXT), GroceryIntentInterpretedReview
            .parse(body, source).holdReasons.map { it.code }.toSet())
    }

    @Test
    fun politeAndNonActionableBackgroundStillProduceReview() {
        for (instruction in listOf(
            "No worries. one milk packet",
            "one milk packet, please. Thanks for your help.",
            "Just chatting today. one milk packet",
        )) {
            val itemSource = "one milk packet"
            val preparation = prepare(wireRows(instruction, listOf(Row(
                "milk", 1, "pack", "retail_units", "packet", source = itemSource,
            ))))
            assertEquals(instruction, itemSource, preparation.items.single().rawText)
            assertEquals(1, preparation.items.size)
        }
    }

    @Test
    fun sourceMismatchAndAnyUnresolvedOrBlockedCoverageHoldTheCompleteOriginal() {
        val original = "one milk packet"
        val body = wire(original, "milk", 1, "pack", "retail_units", "pack")
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentInterpretedReview.parse(body.replace(original, "one oat milk packet"), original)
        }
        val omittedSource = body.replace("\"role\":\"item\",\"row_indices\":[0]", "\"role\":\"background\",\"row_indices\":[]")
        assertThrows(IllegalArgumentException::class.java) { GroceryIntentInterpretedReview.parse(omittedSource, original) }

        val blocked = body.replace("\"blocked_rows\":[]", "\"blocked_rows\":[{\"row_index\":0,\"source\":{\"text\":\"one milk packet\",\"start_utf16\":0,\"end_utf16\":15},\"issue_codes\":[\"binding\"]}]")
        assertThrows(IllegalArgumentException::class.java) { GroceryIntentInterpretedReview.parse(blocked, original) }

        val unresolved = wireRows("one milk packet maybe", listOf(Row(
            "milk", 1, "pack", "retail_units", "pack", source = original,
        ))).replace("\"role\":\"background\"", "\"role\":\"unresolved\"")
        val unresolvedHold = assertThrows(IntentNeedsReviewException::class.java) {
            GroceryIntentInterpretedReview.parse(unresolved, "one milk packet maybe").prepareForReview()
        }
        assertEquals(listOf("one milk packet maybe"), unresolvedHold.affectedSourceText)

        val heldReview = GroceryIntentInterpretedReview.parse(body.replace("\"issues\":[]", "\"issues\":[\"diet\"]"), original)
        val hold = assertThrows(IntentNeedsReviewException::class.java) { heldReview.prepareForReview() }
        assertEquals(listOf(original), hold.affectedSourceText)
    }

    @Test
    fun editedQueryAndQuantityCannotUseAnAuditedProvenance() {
        val original = "one milk packet"
        val prepared = GroceryIntentInterpretedReview.parse(
            wire(original, "milk", 1, "pack", "retail_units", "pack"), original,
        ).prepareForReview()
        val item = prepared.items.single()
        val offer = offer("milk", "Milk Packet", "500 ml")
        val changedQuery = item.copy(query = "oat milk")
        val changedQuantity = item.copy(quantity = com.example.beta.automation.Quantity.Count(2))
        val changedExclusion = item.copy(avoidPhrases = listOf("synthetic allergen"))
        val changedPack = item.copy(intentQuantityProvenance = item.intentQuantityProvenance!!.copy(
            unitSize = IntentLocalAmount("750 ml", 0, 3, "750", java.math.BigDecimal("750"), "ml"),
        ))
        val changedOrigin = item.copy(intentQuantityProvenance = item.intentQuantityProvenance!!.copy(
            origin = com.example.beta.automation.IntentQuantityOrigin.LOCAL,
        ))
        assertFalse(isSwiggyCandidateAllowed(changedQuery, offer))
        assertFalse(isSwiggyCandidateAllowed(changedQuantity, offer))
        assertFalse(isSwiggyCandidateAllowed(changedExclusion, offer))
        assertFalse(isSwiggyCandidateAllowed(changedPack, offer))
        assertFalse(isSwiggyCandidateAllowed(changedOrigin, offer))
        assertFalse(auditedIntentBasketMatches(listOf(changedQuery), original))
        assertFalse(auditedIntentBasketMatches(listOf(changedQuantity), original))
    }

    @Test
    fun dietaryRemovalDowngradePackChangeAndSellerEvidenceMutationInvalidateAuditedItem() {
        val instruction = "one soap without fragrance"
        val predicate = IngredientPredicate(IngredientPredicateOperator.ABSENT, "fragrance")
        val prep = prepare(wireRows(instruction, listOf(Row("soap", 1, "count", "requested_pieces",
            requirements = listOf(Requirement("without fragrance", "ingredient", listOf(predicate)))))))
        val original = prep.items.single()
        val offer = candidate("soap", "Soap no fragrance", sku = "sku-soap", ingredientField = "Soap no fragrance",
            quantityFields = CatalogueQuantityFields(variantName = "Pack"))
        val dietaryRemoved = original.copy(avoidPhrases = emptyList())
        val predicateDowngraded = original.copy(intentQuantityProvenance = original.intentQuantityProvenance!!.copy(audited = null))
        val packChanged = original.copy(intentQuantityProvenance = original.intentQuantityProvenance!!.copy(
            unitSize = IntentLocalAmount("2 pieces", 0, 1, "2", java.math.BigDecimal("2"), "piece"),
        ))
        assertFalse(isSwiggyCandidateAllowed(dietaryRemoved, offer))
        assertFalse(isSwiggyCandidateAllowed(predicateDowngraded, offer))
        assertFalse(isSwiggyCandidateAllowed(packChanged, offer))

        val poisonedQuantity = offer.copy(catalogueQuantity = offer.catalogueQuantity!!.copy(skuId = "different-sku"))
        assertTrue(resolveSwiggyCartQuantity(assessSwiggyIntentQuantity(original,
            SwiggyMcpClient.Recommendations(listOf(poisonedQuantity))), poisonedQuantity) is SwiggyCartQuantityResolution.Held)
        val transplantedIngredient = offer.copy(skuId = "other-sku")
        assertFalse(isSwiggyCandidateAllowed(original, transplantedIngredient))
    }

    private fun offer(query: String, title: String, size: String) = RecommendationCandidate(
        spinId = "synthetic-$query", label = title, skuId = "synthetic-sku-$query",
        catalogueIngredients = sellerTitleFixture("synthetic-$query", "synthetic-sku-$query", title, size),
        catalogueQuantity = CatalogueQuantityEvidence(
            1, "search_variation", "synthetic-$query", "synthetic-sku-$query",
            CatalogueQuantityFields(variantName = size),
        ),
    )

    private fun candidate(
        query: String, title: String, variant: String? = null, sku: String,
        quantityFields: CatalogueQuantityFields? = null, ingredientField: String? = null,
    ): RecommendationCandidate {
        val spin = "spin-$query"
        return RecommendationCandidate(
            spinId = spin, label = title, variant = variant, skuId = sku,
            catalogueQuantity = quantityFields?.let { CatalogueQuantityEvidence(1, "search_variation", spin, sku, it) },
            catalogueIngredients = sellerTitleFixture(spin, sku, title, ingredientField ?: variant),
        )
    }

    private fun wire(
        instruction: String, name: String, quantity: Int, unit: String, role: String, packaging: String?,
    ): String = wireRows(instruction, listOf(Row(name, quantity, unit, role, packaging, source = instruction)))

    private fun prepare(body: String): GroceryIntentPreparation {
        val root = JsonParser(body).parseStrict() as JsonValue.Obj
        return GroceryIntentInterpretedReview.parse(body, (root.members.getValue("instruction") as JsonValue.Str).value).prepareForReview()
    }

    // JVM fixtures use no Android org.json stub. Production wire construction
    // requires separate device verification; the predicate mapper is shared.
    private object JSONObject {
        fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
    }

    private fun wireRows(instruction: String, inputRows: List<Row>): String {
        val rows = inputRows.map { if (it.source.isEmpty()) it.copy(source = instruction) else it }
        val starts = rows.map { row -> instruction.indexOf(row.source).also { require(it >= 0 && instruction.indexOf(row.source, it + 1) < 0) } }
        val ranges = rows.indices.map { starts[it] until (starts[it] + rows[it].source.length) }
        val boundaries = (listOf(0, instruction.length) + ranges.flatMap { listOf(it.first, it.last + 1) }).distinct().sorted()
        val claims = rows.joinToString(",") { row -> claimJson(row) }
        val requirementClaims = rows.joinToString(",") { row ->
            row.requirements.joinToString(prefix = "[", postfix = "]") { requirement ->
                """{"source_text":${JSONObject.quote(requirement.source)},"kind":${JSONObject.quote(requirement.kind)},"ingredient_predicates":${predicatesJson(requirement.predicates)}}"""
            }
        }
        val rowJson = rows.mapIndexed { index, row ->
            val start = starts[index]
            val end = start + row.source.length
            val amount = row.quantity?.let { row.quantityText ?: row.source.substringBefore(' ') }
            val amountStart = amount?.let { start + nthIndexOf(row.source, it, row.quantityOccurrence) }
            val quantityAtom = amount?.let { "{\"text\":${JSONObject.quote(it)},\"start_utf16\":$amountStart,\"end_utf16\":${amountStart!! + it.length}}" } ?: "null"
            val packStart = row.packText?.let { start + nthIndexOf(row.source, it, row.packOccurrence) }
            val packAtom = row.packText?.let { "{\"text\":${JSONObject.quote(it)},\"start_utf16\":$packStart,\"end_utf16\":${packStart!! + it.length}}" } ?: "null"
            val projected = claimJson(row).dropLast(1) + """, "packaging":null,"supporting_text":[],"quantity_text":${amount?.let { JSONObject.quote(it) } ?: "null"},"pack_text":${row.packText?.let { JSONObject.quote(it) } ?: "null"}}"""
            val boundRequirements = row.requirements.joinToString(prefix = "[", postfix = "]") { requirement ->
                val reqStart = start + nthIndexOf(row.source, requirement.source, 0)
                val reqEnd = reqStart + requirement.source.length
                val id = "r$reqStart:$reqEnd"
                """{"id":${JSONObject.quote(id)},"source":{"text":${JSONObject.quote(requirement.source)},"start_utf16":$reqStart,"end_utf16":$reqEnd},"kind":${JSONObject.quote(requirement.kind)},"ingredient_predicates":${predicatesJson(requirement.predicates)}}"""
            }
            val unassigned = row.unassigned.joinToString(prefix = "[", postfix = "]", transform = ::quotePayload)
            """{"row_index":$index,"item":$projected,"evidence":{"primary":{"text":${JSONObject.quote(row.source)},"start_utf16":$start,"end_utf16":$end},"supporting":[]},"quantity_atom":$quantityAtom,"pack_atom":$packAtom,"unassigned_numeric":$unassigned,"requirements":$boundRequirements}"""
        }.joinToString(",")
        val auditRows = rows.mapIndexed { index, row ->
            val auditRequirements = row.requirements.joinToString(prefix = "[", postfix = "]") { requirement ->
                val reqStart = starts[index] + nthIndexOf(row.source, requirement.source, 0)
                val id = "r$reqStart:${reqStart + requirement.source.length}"
                """{"id":${JSONObject.quote(id)},"kind":${JSONObject.quote(requirement.kind)},"ingredient_predicates":${predicatesJson(requirement.predicates)}}"""
            }
            val packageRole = when {
                row.packValue == null -> "unstated"
                row.packUnit in setOf("g", "kg") -> "per_unit_mass"
                row.packUnit in setOf("ml", "l") -> "per_unit_volume"
                else -> "per_unit_pieces"
            }
            """{"row_index":$index,"issues":${strings(row.auditIssues)},"quantity_role":${JSONObject.quote(row.role)},"package_size_role":${JSONObject.quote(packageRole)},"packaging":${row.packaging?.let { JSONObject.quote(it) } ?: "null"},"requirements":$auditRequirements}"""
        }.joinToString(",")
        val sourceAudit = boundaries.zipWithNext().mapNotNull { (start, end) ->
            val text = instruction.substring(start, end).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val owners = ranges.indices.filter { start >= ranges[it].first && end <= ranges[it].last + 1 }
            val role = if (owners.isEmpty()) "background" else "item"
            """{"source":{"text":${JSONObject.quote(text)},"start_utf16":$start,"end_utf16":$end},"role":"$role","row_indices":${owners.joinToString(prefix = "[", postfix = "]")}}"""
        }.joinToString(",")
        return """{"contract_version":"grocery-intent-interpreted-v2","requires_review":true,"instruction":${JSONObject.quote(instruction)},"source_item_count":${rows.size},"claims":[$claims],"requirements":[$requirementClaims],"rows":[$rowJson],"blocked_rows":[],"context":[],"audit":{"version":"value-audit-v3","request_fingerprint":"${"a".repeat(64)}","rows":[$auditRows],"source":[$sourceAudit]}}"""
    }

    private fun claimJson(row: Row) =
        """{"name":${JSONObject.quote(row.name)},"quantity":${row.quantity ?: "null"},"quantity_unit":${JSONObject.quote(row.unit)},"pack_value":${row.packValue ?: "null"},"pack_unit":${JSONObject.quote(row.packUnit)},"required":[],"excluded":[],"source_text":${JSONObject.quote(row.source)},"needs_clarification":false}"""

    private fun strings(values: List<String>) = values.joinToString(prefix = "[", postfix = "]") { JSONObject.quote(it) }
    private fun nthIndexOf(source: String, value: String, occurrence: Int): Int {
        var from = 0
        repeat(occurrence) {
            val previous = source.indexOf(value, from)
            require(previous >= 0)
            from = previous + value.length
        }
        return source.indexOf(value, from).also { require(it >= 0) }
    }
    private fun predicatesJson(values: List<IngredientPredicate>) = values.joinToString(prefix = "[", postfix = "]") {
        val op = if (it.operator == IngredientPredicateOperator.ABSENT) "absent" else "no_addition"
        """{"operator":"$op","operand":${JSONObject.quote(it.operand)}}"""
    }

    private fun payloadJson(requirement: SellerPropertyRequirement): String {
        val entries = requirement.requirements.joinToString(prefix = "[", postfix = "]") { quote ->
            """{"id":"r${quote.startUtf16}:${quote.endUtf16}","source":${quotePayload(quote)}}"""
        }
        return """{"version":2,"subject":${JSONObject.quote(requirement.subject)},"source":${quotePayload(requirement.source)},"requirements":$entries}"""
    }

    private fun quotePayload(quote: GroceryIntentReviewV2Quote) =
        """{"text":${JSONObject.quote(quote.text)},"start_utf16":${quote.startUtf16},"end_utf16":${quote.endUtf16}}"""

    private data class Requirement(val source: String, val kind: String, val predicates: List<IngredientPredicate> = emptyList())

    private data class Row(
        val name: String, val quantity: Number?, val unit: String, val role: String, val packaging: String? = null,
        val requirements: List<Requirement> = emptyList(),
        val auditIssues: List<String> = emptyList(), val source: String = "", val quantityText: String? = null,
        val packValue: Number? = null, val packUnit: String = "unspecified", val packText: String? = null,
        val quantityOccurrence: Int = 0, val packOccurrence: Int = 0,
        val unassigned: List<GroceryIntentReviewV2Quote> = emptyList(),
    )
}
