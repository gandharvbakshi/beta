package com.example.beta

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

data class SearchOcrCandidate(
    val text: String,
    val bounds: SearchBounds,
    val imageWidth: Int,
    val imageHeight: Int
)

data class SearchBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun toRect(): Rect = Rect(left, top, right, bottom)
}

enum class SearchConfidenceCategory {
    HIGH,
    MEDIUM,
    LOW
}

data class LocalSearchBootstrapResult(
    val centerX: Int,
    val centerY: Int,
    val matchedText: String,
    val confidenceCategory: SearchConfidenceCategory
)

object LocalSearchBootstrap {
    private val unsafeTerms = listOf(
        "cart",
        "checkout",
        "coupon",
        "coupons",
        "promo",
        "promos",
        "address",
        "payment",
        "order"
    )

    fun detectSearchBootstrap(
        bitmap: Bitmap,
        onResult: (LocalSearchBootstrapResult?) -> Unit
    ) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        val closed = AtomicBoolean(false)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                try {
                    onResult(selectSearchBootstrapResult(extractCandidates(visionText, bitmap.width, bitmap.height)))
                } finally {
                    closeOnce(recognizer, closed)
                }
            }
            .addOnFailureListener {
                try {
                    onResult(null)
                } finally {
                    closeOnce(recognizer, closed)
                }
            }
    }

    fun selectSearchBootstrapResult(
        candidates: List<SearchOcrCandidate>
    ): LocalSearchBootstrapResult? {
        return candidates.asSequence()
            .mapNotNull { candidate -> scoreCandidate(candidate) }
            .maxByOrNull { it.score }
            ?.toResult()
    }

    internal fun extractCandidates(
        visionText: Text,
        imageWidth: Int,
        imageHeight: Int
    ): List<SearchOcrCandidate> {
        val candidates = mutableListOf<SearchOcrCandidate>()
        for (block in visionText.textBlocks) {
            candidates += block.toCandidate(imageWidth, imageHeight)
            for (line in block.lines) {
                candidates += line.toCandidate(imageWidth, imageHeight)
                for (element in line.elements) {
                    candidates += element.toCandidate(imageWidth, imageHeight)
                }
            }
        }
        return candidates
    }

    private fun scoreCandidate(candidate: SearchOcrCandidate): ScoredCandidate? {
        val normalizedText = normalizeText(candidate.text)
        if (normalizedText.isBlank()) return null
        if (hasUnsafeTerms(normalizedText)) return null
        if (!hasSaneBounds(candidate)) return null
        if (!isTopScreenCandidate(candidate)) return null

        val phraseMatch = matchedPhrase(normalizedText) ?: return null
        val confidence = when (phraseMatch) {
            "search for products", "search in" -> SearchConfidenceCategory.HIGH
            "search" -> SearchConfidenceCategory.MEDIUM
            else -> SearchConfidenceCategory.LOW
        }

        val phraseRank = when (phraseMatch) {
            "search for products" -> 3000
            "search in" -> 2900
            "search" -> 2800
            else -> 0
        }

        val positionRank = 1000 - candidate.bounds.centerY
        val areaRank = candidate.bounds.width * candidate.bounds.height

        return ScoredCandidate(
            result = LocalSearchBootstrapResult(
                centerX = candidate.bounds.centerX,
                centerY = candidate.bounds.centerY,
                matchedText = phraseMatch,
                confidenceCategory = confidence
            ),
            score = phraseRank * 1_000_000 + positionRank * 1_000 + areaRank
        )
    }

    private fun matchedPhrase(normalizedText: String): String? {
        if (normalizedText == "search for products") return "search for products"
        if (normalizedText.startsWith("search for products ")) return "search for products"
        if (normalizedText == "search in") return "search in"
        if (normalizedText.startsWith("search in ")) return "search in"
        if (normalizedText == "search") return "search"
        if (normalizedText.startsWith("search ")) return "search"
        return null
    }

    private fun hasUnsafeTerms(normalizedText: String): Boolean {
        return unsafeTerms.any { term -> normalizedText.contains(term) }
    }

    private fun hasSaneBounds(candidate: SearchOcrCandidate): Boolean {
        val bounds = candidate.bounds
        if (candidate.imageWidth <= 0 || candidate.imageHeight <= 0) return false
        if (bounds.left < 0 || bounds.top < 0) return false
        if (bounds.right > candidate.imageWidth || bounds.bottom > candidate.imageHeight) return false
        if (bounds.width <= 0 || bounds.height <= 0) return false

        val minWidth = maxOf(24, candidate.imageWidth / 30)
        val minHeight = maxOf(12, candidate.imageHeight / 80)
        val maxWidth = candidate.imageWidth * 95 / 100
        val maxHeight = candidate.imageHeight * 25 / 100
        val minArea = maxOf(120, candidate.imageWidth * candidate.imageHeight / 1000)

        return bounds.width >= minWidth &&
            bounds.height >= minHeight &&
            bounds.width <= maxWidth &&
            bounds.height <= maxHeight &&
            bounds.width * bounds.height >= minArea
    }

    private fun isTopScreenCandidate(candidate: SearchOcrCandidate): Boolean {
        val topBand = candidate.imageHeight * 40 / 100
        return candidate.bounds.top < topBand && candidate.bounds.centerY < topBand
    }

    private fun normalizeText(text: String): String {
        return text
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun Text.TextBlock.toCandidate(imageWidth: Int, imageHeight: Int): SearchOcrCandidate {
        val bounds = boundingBox ?: Rect()
        return SearchOcrCandidate(
            text = text,
            bounds = SearchBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
    }

    private fun Text.Line.toCandidate(imageWidth: Int, imageHeight: Int): SearchOcrCandidate {
        val bounds = boundingBox ?: Rect()
        return SearchOcrCandidate(
            text = text,
            bounds = SearchBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
    }

    private fun Text.Element.toCandidate(imageWidth: Int, imageHeight: Int): SearchOcrCandidate {
        val bounds = boundingBox ?: Rect()
        return SearchOcrCandidate(
            text = text,
            bounds = SearchBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
    }

    private fun closeOnce(recognizer: TextRecognizer, closed: AtomicBoolean) {
        if (closed.compareAndSet(false, true)) {
            recognizer.close()
        }
    }

    private data class ScoredCandidate(
        val result: LocalSearchBootstrapResult,
        val score: Int
    ) {
        fun toResult(): LocalSearchBootstrapResult = result
    }
}
