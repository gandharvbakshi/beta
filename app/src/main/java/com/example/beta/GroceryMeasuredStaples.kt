package com.example.beta

import java.util.Locale

// Exact food names, not SKU identities or synonyms. Source: MoHFW, Indicative
// Nutritional Family Food Basket (2026), printed p. 33, regional food table.
// See docs/NOV09_CATALOGUE_FIRST.md. Never use this table to change a query,
// equate varieties, infer a diet, or determine a pack size or a cart count.
private val weighedStapleNames = setOf(
    "toor dal", "arhar dal", "chana dal", "moong dal", "masoor dal", "urad dal", "udad dal",
    "horse gram", "bengal gram", "black gram", "green gram", "moth bean",
    "raw rice", "boiled rice", "parboiled rice", "joha rice", "red sticky rice",
    "soya chunks", "roasted peanuts", "roasted chana", "roasted green gram",
)

internal fun isDocumentedWeighedStaple(wholeName: String): Boolean =
    wholeName.lowercase(Locale.ROOT) in weighedStapleNames

internal val groceryCutForms = setOf("slices", "flakes", "pieces")
