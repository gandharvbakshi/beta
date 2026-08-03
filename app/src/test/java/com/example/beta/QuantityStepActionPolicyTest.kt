package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantityStepActionPolicyTest {
    @Test
    fun plusSelector_isDetectedAsIncrementAndRequiresCoordinatesOutsideSwiggy() {
        val direction = QuantityStepActionPolicy.detectDirection(
            selectorText = "+",
            actionTarget = "Increase quantity to 2",
            reasoning = "tap plus to reach requested quantity 2",
            contentDescription = "",
            resourceId = ""
        )

        assertEquals(QuantityStepDirection.INCREMENT, direction)
        assertTrue(QuantityStepActionPolicy.requiresCoordinateOnlyExecution(direction, isSwiggyForeground = false))
        assertFalse(QuantityStepActionPolicy.requiresCoordinateOnlyExecution(direction, isSwiggyForeground = true))
    }

    @Test
    fun ordinaryClick_doesNotEnterQuantityCoordinateOnlyPath() {
        val direction = QuantityStepActionPolicy.detectDirection(
            selectorText = "View cart",
            actionTarget = "Open cart",
            reasoning = "verify the selected item",
            contentDescription = "",
            resourceId = ""
        )

        assertNull(direction)
        assertFalse(QuantityStepActionPolicy.requiresCoordinateOnlyExecution(direction, isSwiggyForeground = false))
    }

    @Test
    fun minusSelector_isDetectedAsDecrementAndRequiresCoordinatesOutsideSwiggy() {
        val direction = QuantityStepActionPolicy.detectDirection(
            selectorText = "-",
            actionTarget = "Decrease quantity to remove limited-stock item",
            reasoning = "remove one before trying another product",
            contentDescription = "",
            resourceId = ""
        )

        assertEquals(QuantityStepDirection.DECREMENT, direction)
        assertTrue(QuantityStepActionPolicy.requiresCoordinateOnlyExecution(direction, isSwiggyForeground = false))
        assertFalse(QuantityStepActionPolicy.requiresCoordinateOnlyExecution(direction, isSwiggyForeground = true))
    }
}
