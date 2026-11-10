package com.example.beta

import com.example.beta.SwiggyMcpClient.CartPlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyCartReviewSnapshotTest {
    @Test
    fun parsesEmptyAndNonemptyExactShapesWithImmutableRowsAndRedactedDiagnostics() {
        val empty = SwiggyMcpClient.parseCartReviewSnapshot(wire("[]"))
        assertTrue(empty.existingItems.isEmpty())
        assertEquals(BASIS, empty.reviewBasis)

        val nonempty = SwiggyMcpClient.parseCartReviewSnapshot(wire(
            """[{"spinId":"synthetic-milk-spin","skuId":"synthetic-milk-sku","quantity":100},{"spinId":"synthetic-bread-spin","quantity":1}]""",
        ))
        assertEquals(
            listOf(
                CartPlanItem("synthetic-milk-spin", 100, "synthetic-milk-sku"),
                CartPlanItem("synthetic-bread-spin", 1),
            ),
            nonempty.existingItems,
        )
        assertTrue(runCatching {
            (nonempty.existingItems as MutableList<CartPlanItem>).clear()
        }.isFailure)
        for (text in listOf(nonempty.toString(), nonempty.existingItems.first().toString())) {
            assertFalse(text.contains("synthetic-milk-spin"))
            assertFalse(text.contains("synthetic-milk-sku"))
            assertFalse(text.contains(BASIS))
        }
    }

    @Test
    fun rejectsMissingInvalidOrUnboundedBasisAndAnyAuthorityOrEnvelopeFields() {
        val items = """[{"spinId":"milk","quantity":1}]"""
        val invalid = listOf(
            """{"existingItems":$items}""",
            """{"existingItems":$items,"reviewBasis":"wrong-prefix:${"a".repeat(64)}"}""",
            """{"existingItems":$items,"reviewBasis":"cart-basis-v1:${"A".repeat(64)}"}""",
            """{"existingItems":$items,"reviewBasis":"cart-basis-v1:${"a".repeat(63)}"}""",
            """{"existingItems":$items,"reviewBasis":"cart-basis-v1:${"a".repeat(65)}"}""",
            """{"existingItems":$items,"reviewBasis":null}""",
            """{"data":{"existingItems":$items,"reviewBasis":"$BASIS"}}""",
            """{"existingItems":$items,"reviewBasis":"$BASIS","confirmationToken":"synthetic-token"}""",
            """{"existingItems":$items,"reviewBasis":"$BASIS","nonce":"synthetic-nonce"}""",
            """{"existingItems":$items,"reviewBasis":"$BASIS","proposedItems":[]}""",
            """{"existingItems":$items,"reviewBasis":"$BASIS","expiresAt":123}""",
            """{"existingItems":$items,"reviewBasis":"$BASIS","cartMutationEnabled":true}""",
            "not-json",
            """{"existingItems":$items,"existingItems":$items,"reviewBasis":"$BASIS"}""",
        )
        invalid.forEachIndexed { index, body ->
            assertTrue("invalid snapshot shape $index", runCatching {
                SwiggyMcpClient.parseCartReviewSnapshot(body)
            }.isFailure)
        }
    }

    @Test
    fun cartRowsRequireUniqueSpinIdsBoundedIntegralQuantityAndOptionalValidSku() {
        val invalidItems = listOf(
            """[{"spinId":"milk","quantity":0}]""",
            """[{"spinId":"milk","quantity":101}]""",
            """[{"spinId":"milk","quantity":1.5}]""",
            """[{"spinId":"milk","quantity":"1"}]""",
            """[{"spinId":"milk","quantity":true}]""",
            """[{"spinId":"milk"}]""",
            """[{"spinId":" ","quantity":1}]""",
            """[{"spinId":7,"quantity":1}]""",
            """[{"quantity":1}]""",
            """[{"spinId":"milk","quantity":1,"skuId":" "}]""",
            """[{"spinId":"milk","quantity":1,"skuId":7}]""",
            """[{"spinId":"milk","quantity":1,"count":1}]""",
            """[{"spinId":"milk","quantity":1,"product":{"spinId":"other"}}]""",
            """[{"spinId":"milk","quantity":1,"spin_id":"other"}]""",
            """[{"spinId":"milk","quantity":1},{"spinId":"milk","quantity":1}]""",
            """[{"spinId":"milk","quantity":1,"skuId":"sku-a"},{"spinId":"milk","quantity":1,"skuId":"sku-b"}]""",
            """[{"spinId":"milk","quantity":1,"spinId":"oats"}]""",
            """[{"spinId":"milk","quantity":1,"providerManagedFree":true}]""",
        )
        invalidItems.forEachIndexed { index, items ->
            assertTrue("invalid cart row $index", runCatching {
                SwiggyMcpClient.parseCartReviewSnapshot(wire(items))
            }.isFailure)
        }
    }

    private fun wire(items: String) =
        """{"existingItems":$items,"reviewBasis":"$BASIS"}"""

    private companion object {
        val BASIS = "cart-basis-v1:${"a".repeat(64)}"
    }
}
