package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentProduceIdentityTest {
    @Test fun admitsClosedWholeProduceLabelsAndBoundedSizeMetadata() {
        listOf(
            candidate("Fresh Organic Tomato"),
            candidate("Tomato 500 g"),
            candidate("Tomato 2 x 500 g"),
            candidate("Organic Green Capsicum", variant = "500 g", subtitle = "Fresh"),
            candidate("Royal Gala Apple", variant = "1 kg"),
            candidate("Green Apple", variant = "1 kg"),
            candidate("Seedless Grapes", variant = "500 g"),
            candidate("Seedless Grapes", subtitle = "Pack of 2"),
            candidate("Fresh Tomatoes", variant = "", subtitle = " "),
            candidate("Anaar"),
            candidate("Green Chilli"),
            candidate("Fresh Green Beans"),
            candidate("Fresh Green Peas", variant = "500 g"),
        ).forEach { product ->
            val category = if (product.label.contains("Apple", true) || product.label.contains("Grapes", true) || product.label == "Anaar") {
                IntentProduceCategory.FRUITS
            } else IntentProduceCategory.VEGETABLES
            assertTrue(product.label, swiggyIntentProduceCandidateMatches(category, product))
        }
    }

    @Test fun rejectsPreparedFoodsAndCategoryLoans() {
        listOf(
            IntentProduceCategory.VEGETABLES to candidate("Tomato Puree"),
            IntentProduceCategory.VEGETABLES to candidate("Chicken Drumstick"),
            IntentProduceCategory.FRUITS to candidate("Orange Juice"),
            IntentProduceCategory.FRUITS to candidate("Apple Cider"),
            IntentProduceCategory.FRUITS to candidate("Mango Pulp"),
            IntentProduceCategory.VEGETABLES to candidate("Potato Chips"),
            IntentProduceCategory.VEGETABLES to candidate("Tomato Soap"),
            IntentProduceCategory.VEGETABLES to candidate("Pepper Spray"),
            IntentProduceCategory.FRUITS to candidate("Tomato"),
            IntentProduceCategory.VEGETABLES to candidate("Mango"),
            IntentProduceCategory.FRUITS to candidate("Orange 500 ml"),
            IntentProduceCategory.FRUITS to candidate("Orange Apple"),
            IntentProduceCategory.VEGETABLES to candidate("Ginger Garlic"),
            IntentProduceCategory.FRUITS to candidate("Apple", variant = "Banana"),
            IntentProduceCategory.FRUITS to candidate("Raw Banana 500 g"),
            IntentProduceCategory.FRUITS to candidate("Green Papaya"),
            IntentProduceCategory.FRUITS to candidate("Raw Mango"),
            IntentProduceCategory.FRUITS to candidate("Banana", variant = "Green"),
            IntentProduceCategory.FRUITS to candidate("Banana", subtitle = "Baby"),
            IntentProduceCategory.VEGETABLES to candidate("Coffee Beans"),
            IntentProduceCategory.VEGETABLES to candidate("Chick Peas"),
            IntentProduceCategory.VEGETABLES to candidate("Black Beans"),
            IntentProduceCategory.VEGETABLES to candidate("Fresh Black Beans"),
            IntentProduceCategory.VEGETABLES to candidate("Green Peas"),
            IntentProduceCategory.VEGETABLES to candidate("Fresh Beans"),
            IntentProduceCategory.VEGETABLES to candidate("Fresh Green Beans", variant = "White"),
            IntentProduceCategory.VEGETABLES to candidate("Fresh Green Peas", subtitle = "Yellow"),
        ).forEach { (category, product) ->
            assertFalse("${category.name}: ${product.label}", swiggyIntentProduceCandidateMatches(category, product))
        }
    }

    @Test fun rejectsUnknownClaimsInEveryProviderTextField() {
        listOf(
            candidate("Fresh Tomato", variant = "UHT"),
            candidate("Fresh Tomato", subtitle = "JSON"),
            candidate("Tomato 500 g organic brandx"),
            candidate("Tomato A2"),
            candidate("Tomato 500 g", variant = "Imported", subtitle = "Grade A"),
            candidate("Fresh Tomato", subtitle = "not fresh"),
            candidate("Tomato", variant = "1st quality"),
            candidate("{\"Tomato\":\"Fresh\"}"),
            candidate("Tomato 0 g"),
            candidate("Tomato Pack of 0"),
        ).forEach { product ->
            assertFalse(product.toString(), swiggyIntentProduceCandidateMatches(IntentProduceCategory.VEGETABLES, product))
        }
        assertFalse(swiggyIntentProduceCandidateMatches(IntentProduceCategory.VEGETABLES, candidate("")))
        assertFalse(swiggyIntentProduceCandidateMatches(IntentProduceCategory.VEGETABLES, candidate("   ")))
    }

    @Test fun requiresProductNounInLabelAndChecksVariantAndSubtitleIndependently() {
        assertFalse(swiggyIntentProduceCandidateMatches(
            IntentProduceCategory.FRUITS,
            candidate("Fresh Organic", variant = "Mango"),
        ))
        assertFalse(swiggyIntentProduceCandidateMatches(
            IntentProduceCategory.VEGETABLES,
            candidate("Tomato", variant = "Puree"),
        ))
        assertFalse(swiggyIntentProduceCandidateMatches(
            IntentProduceCategory.VEGETABLES,
            candidate("Tomato", subtitle = "Chicken"),
        ))
        assertFalse(swiggyIntentProduceCandidateMatches(
            IntentProduceCategory.VEGETABLES,
            candidate("Tomato", variant = "Mango"),
        ))
    }

    private fun candidate(
        label: String,
        variant: String? = null,
        subtitle: String? = null,
    ) = RecommendationCandidate(
        spinId = "synthetic-${label.hashCode()}-${variant.hashCode()}-${subtitle.hashCode()}",
        label = label,
        variant = variant,
        subtitle = subtitle,
    )
}
