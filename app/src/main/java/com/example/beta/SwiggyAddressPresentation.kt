package com.example.beta

import com.example.beta.SwiggyMcpClient.SwiggyAddress

/**
 * The compact address copy shown to a person choosing a delivery address.
 *
 * Provider labels are useful as a heading (for example, "Home — Bengaluru"),
 * but they are not enough to distinguish two saved Home addresses.  The
 * confirmation detail is intentionally preferred because the MCP parser has
 * already removed the country, state and PIN code from it.
 */
internal data class SwiggyAddressPresentation(
    val title: String,
    val detail: String,
)

internal fun swiggyAddressPresentation(address: SwiggyAddress): SwiggyAddressPresentation {
    val title = address.shortLabel.trim()
        .ifBlank { address.categoryLabel.trim() }
        .ifBlank { "Saved Swiggy address" }
    val detailSource = address.confirmationDetail
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: address.label
    val detail = compactSwiggyAddressDetail(detailSource, address.label)
    return SwiggyAddressPresentation(title = title, detail = detail)
}

/** Spoken copy is deliberately tentative; selection is only confirmed after the button tap. */
internal fun swiggySpokenAddressSuggestion(address: SwiggyAddress): String {
    val presentation = swiggyAddressPresentation(address)
    return "Suggested address ${presentation.title}, ${presentation.detail}. Please check it before choosing."
}

internal fun swiggyAddressSuggestionPrompt(assessment: SwiggyLocationAssessment?): String = when (assessment) {
    SwiggyLocationAssessment.AREA_MATCH -> "Please check the flat and area before continuing."
    SwiggyLocationAssessment.NOT_MATCHED -> "This address may be away from your location. Continue only if you want delivery there."
    else -> "Location isn't available. Please check the flat and area."
}

private fun compactSwiggyAddressDetail(value: String, fullLabel: String): String {
    val normalized = value
        .replace(Regex("\\b\\d{6}\\b"), "")
        .replace(Regex("\\s+"), " ")
        .trim(' ', ',')
    if (normalized.isBlank()) return "Check the house or flat and area in Swiggy"

    val segments = normalized.split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot { it.equals("India", ignoreCase = true) || it.equals("Bharat", ignoreCase = true) }
        .filterNot { it.lowercase() in SwiggyMcpClient.INDIAN_STATE_NAMES }
        .filterNot { Regex("^\\d{6}$").matches(it) }
    val compact = (if (segments.isEmpty()) normalized else segments.take(3).joinToString(", "))
        .take(120)
        .ifBlank { "Check the house or flat and area in Swiggy" }
    val suffixSegments = fullLabel
        .replace(Regex("\\b\\d{6}\\b"), "")
        .split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot { it.equals("India", ignoreCase = true) || it.equals("Bharat", ignoreCase = true) }
        .filterNot { it.lowercase() in SwiggyMcpClient.INDIAN_STATE_NAMES }
        .filterNot { Regex("^\\d{6}$").matches(it) }
        // Keep the locality suffix as well as the city.  Two Home addresses
        // can share a city while differing only by road/block/area.
        .takeLast(3)
    val missingSuffix = suffixSegments.filterNot { compact.contains(it, ignoreCase = true) }
    return if (missingSuffix.isNotEmpty()) {
        "$compact · ${missingSuffix.joinToString(", ")}".take(120)
    } else compact
}
