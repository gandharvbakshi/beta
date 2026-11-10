package com.example.beta

import org.junit.Assert.*
import org.junit.Test

class NutritionMeasureEvidenceTest {
    @Test fun explicit_claim_is_not_physical_package_mass() {
        for (basis in listOf("serving", "portion", "100 g", "100 grams", "100 ml")) {
            val result = nutritionMeasureEvidence("Protein Bar 50 g · 20.0 grams protein per $basis")
            assertFalse(result.unsupported)
            assertEquals(1, result.claims.size)
            assertEquals("20", result.claims.single().amount)
            assertTrue(result.physicalText.contains("50 g"))
            assertFalse(result.physicalText.contains("20.0"))
            assertFalse(result.physicalText.contains("100"))
        }
    }

    @Test fun unknown_claim_roles_cannot_supply_package_mass() {
        for (text in listOf(
            "20 g protein per serving of 50 g", "20 g protein per serving(50g)",
            "20 g protein per serving (about 50g)", "20g protein per serving (size 50g)",
            "20g protein per serving: 50g", "20g protein per serving = 50g",
            "20g protein per 100g/2", "20g protein per serving / 2",
            "20 g protein per portion of fifty grams", "20 g protein per serving 50g",
            "20 g protein", "protein: 20g", "protein 20g", "sugar: 20g",
            "twenty g protein per 100g", "-20 g protein per serving",
            "+20 g protein per serving", "1/20 g protein per serving",
            "20g sugar Chocolate", "Chocolate with 20g sugar", "sugar 20g per serving",
            "Sugar 20g Chocolate", "Brand Sugar 20g 50g", "Brand Sugar 20g · Chocolate",
            "Yogurt low fat 20g per serving", "Chocolate no added sugar: 20g",
            "Protein Bar low fat 20g per bar", "Chocolate low sugar 20g per 2 pieces",
        )) assertTrue(text, nutritionMeasureEvidence(text).unsupported)
    }

    @Test fun normal_sugar_and_explicit_physical_product_sizes_survive() {
        for (text in listOf("500 g sugar", "Sugar 500g", "Organic Brown Sugar 500g",
            "Sunrise 1 kg Sugar", "Protein Bar 50g", "Low Fat Yogurt 500g", "Milk 1 L",
            "Chocolate no added sugar 50g", "Yogurt low fat 500g", "50g sugar free bar")) {
            val result = nutritionMeasureEvidence(text)
            assertFalse(text, result.unsupported)
            assertEquals(text, result.physicalText)
        }
    }

    @Test fun claim_amount_nutrient_and_basis_are_all_identity() {
        val request = "20 g protein per serving bar"
        assertTrue(nutritionClaimsMatch(request, "Bar 50g · 20.0 grams protein per serving"))
        for (label in listOf("Bar 50g", "Bar 20g", "Bar 50g · 15 g protein per serving",
            "Bar 50g · 20 g protein per 100g", "Bar 50g · 20 g sugar per serving",
            "Bar 50g · 20 g protein per serving · 15 g protein per serving")) {
            assertFalse(label, nutritionClaimsMatch(request, label))
        }
        assertTrue(nutritionClaimsMatch(request, "20 g protein per serving bar · 2g fibre per serving"))
        assertFalse(nutritionClaimsMatch("bar", "20 g protein per serving bar · 15g protein per serving"))
    }

    @Test fun unicode_whitespace_and_zero_amount_preserve_claim() {
        val result = nutritionMeasureEvidence("0\u00a0g\u202fsugar per serving bar 50g")
        assertFalse(result.unsupported)
        assertEquals("0", result.claims.single().amount)
        assertTrue(result.physicalText.contains("50g"))
    }
}
