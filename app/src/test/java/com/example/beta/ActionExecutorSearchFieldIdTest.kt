package com.example.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorSearchFieldIdTest {
    @Test
    fun `recognizes blinkit exact search edittext id`() {
        assertTrue(ActionExecutor.isKnownCommerceSearchViewId("com.grofers.customerapp:id/edittext"))
    }

    @Test
    fun `still recognizes swiggy search ids`() {
        assertTrue(ActionExecutor.isKnownCommerceSearchViewId("in.swiggy.android:id/et_search_query_v2"))
        assertTrue(ActionExecutor.isKnownCommerceSearchViewId("in.swiggy.android.instamart:id/et_search_query_v2"))
    }

    @Test
    fun `rejects unrelated ids`() {
        assertFalse(ActionExecutor.isKnownCommerceSearchViewId("com.grofers.customerapp:id/qd_search_bar"))
    }
}
