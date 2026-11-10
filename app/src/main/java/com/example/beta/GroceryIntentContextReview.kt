package com.example.beta

/** Pure row builders for the exact context interpretation shown in final review. */
internal fun groceryIntentContextReviewRows(
    preparation: GroceryIntentContextPreparation,
    onBackground: (Int) -> Unit,
    onOriginal: () -> Unit,
): List<SwiggyStepRow> = groceryIntentContextReviewRows(preparation.proposals, onBackground, onOriginal)

/** Reuse the approved disclosure layout; interpreted background is not a
 * cancelled product and must never be presented as an item left out. */
internal fun groceryIntentContextReviewRows(
    proposals: List<GroceryIntentContextProposal>,
    onBackground: (Int) -> Unit,
    onOriginal: () -> Unit,
): List<SwiggyStepRow> = buildList {
    add(SwiggyStepRow(title = "How Beta understood your request", isHeading = true))
    proposals.forEachIndexed { index, proposal ->
        if (proposal.kind == GroceryIntentContextKind.BACKGROUND) {
            add(SwiggyStepRow(
                title = proposal.source.text,
                detail = "Used as background, not as an item",
                action = SwiggyStepAction("This matters") { onBackground(index) },
            ))
        }
    }
    add(SwiggyStepRow(
        title = "Original request",
        action = SwiggyStepAction("View original request", onOriginal),
    ))
}

/** Blocking rows are informational only; they never provide a waiver action. */
internal fun groceryIntentContextIssueRows(preparation: GroceryIntentContextPreparation): List<SwiggyStepRow> {
    return groceryIntentContextIssueRows(preparation.blockingIssues)
}

internal fun groceryIntentContextIssueRows(issues: List<GroceryIntentContextIssue>): List<SwiggyStepRow> {
    val quotes = issues.flatMap { issue ->
        listOfNotNull(issue.source?.text, issue.concern?.text)
    }.distinct()
    return quotes.map { quote ->
        SwiggyStepRow(
            title = "Beta can't follow this yet: '$quote'",
            tone = SwiggyStepTone.AMBER,
        )
    }
}
