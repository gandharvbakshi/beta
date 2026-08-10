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

    @Test
    fun `recognizes actions that focus or open product search`() {
        assertTrue(ActionExecutor.isSearchFieldActionTarget("Focus search field"))
        assertTrue(ActionExecutor.isSearchFieldActionTarget("Open product search"))
        assertTrue(ActionExecutor.isSearchFieldActionTarget("Blinkit search bar"))
    }

    @Test
    fun `does not treat search suggestions or results as the search field`() {
        assertFalse(ActionExecutor.isSearchFieldActionTarget("Search suggestion for lady finger"))
        assertFalse(ActionExecutor.isSearchFieldActionTarget("Click search result for lady finger"))
    }

    @Test
    fun `recognizes search suggestion targets`() {
        assertTrue(ActionExecutor.isSearchSuggestionActionTarget("Search suggestion for pencil"))
        assertFalse(ActionExecutor.isSearchSuggestionActionTarget("Focus search field"))
    }

    @Test
    fun `rejects editable search field as suggestion node`() {
        assertFalse(
            ActionExecutor.isSafeSearchSuggestionNode(
                isEditable = true,
                className = "android.widget.EditText",
                viewId = "com.grofers.customerapp:id/edittext",
                isVisible = true,
                isEnabled = true
            )
        )
        assertTrue(
            ActionExecutor.isSafeSearchSuggestionNode(
                isEditable = false,
                className = "android.view.View",
                viewId = "",
                isVisible = true,
                isEnabled = true
            )
        )
    }
}
