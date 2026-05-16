package com.example.beta.automation

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

data class PackOption(
    val unitGrams: Int? = null,
    val unitMl: Int? = null,
    val unitCount: Int? = null,
    val priceMinor: Int? = null
)

enum class RoundingPolicy {
    ROUND_UP_25,
    ROUND_DOWN
}

data class PackPlan(
    val skuIndex: Int,
    val packsToAdd: Int,
    val deliveredQuantity: Quantity,
    val deviationPct: Float,
    val rationale: String
)

object PackSolver {
    fun solve(
        desired: Quantity,
        available: List<PackOption>,
        policy: RoundingPolicy = RoundingPolicy.ROUND_UP_25
    ): PackPlan? {
        val desiredValue = quantityValue(desired) ?: return null
        if (desiredValue <= 0 || available.isEmpty()) return null

        val candidates = available.mapIndexedNotNull { index, option ->
            val unit = optionValueFor(desired, option) ?: return@mapIndexedNotNull null
            if (unit <= 0) return@mapIndexedNotNull null
            val upPacks = ceil(desiredValue.toDouble() / unit.toDouble()).toInt().coerceAtLeast(1)
            val downPacks = floor(desiredValue.toDouble() / unit.toDouble()).toInt().coerceAtLeast(1)
            val packCount = if (policy == RoundingPolicy.ROUND_DOWN) downPacks else upPacks
            val delivered = unit * packCount
            Candidate(
                skuIndex = index,
                packsToAdd = packCount,
                deliveredValue = delivered,
                deviationPct = ((delivered - desiredValue).toFloat() / desiredValue.toFloat()) * 100f,
                deviationAbs = abs(delivered - desiredValue),
                priceMinor = option.priceMinor ?: Int.MAX_VALUE,
                unit = unit
            )
        }
        if (candidates.isEmpty()) return null

        val best = candidates.sortedWith(
            compareBy<Candidate> { it.deviationAbs }
                .thenBy { it.packsToAdd }
                .thenBy { it.priceMinor }
        ).first()

        return PackPlan(
            skuIndex = best.skuIndex,
            packsToAdd = best.packsToAdd,
            deliveredQuantity = quantityFromValue(desired, best.deliveredValue),
            deviationPct = best.deviationPct,
            rationale = rationale(desiredValue, best.unit, best.packsToAdd, best.deliveredValue, best.deviationPct)
        )
    }

    private data class Candidate(
        val skuIndex: Int,
        val packsToAdd: Int,
        val deliveredValue: Int,
        val deviationPct: Float,
        val deviationAbs: Int,
        val priceMinor: Int,
        val unit: Int
    )

    private fun quantityValue(quantity: Quantity): Int? = when (quantity) {
        is Quantity.Count -> quantity.n
        is Quantity.Weight -> quantity.grams
        is Quantity.Volume -> quantity.ml
        Quantity.Default -> null
    }

    private fun optionValueFor(quantity: Quantity, option: PackOption): Int? = when (quantity) {
        is Quantity.Count -> option.unitCount ?: 1
        is Quantity.Weight -> option.unitGrams
        is Quantity.Volume -> option.unitMl
        Quantity.Default -> null
    }

    private fun quantityFromValue(shape: Quantity, value: Int): Quantity = when (shape) {
        is Quantity.Count -> Quantity.Count(value)
        is Quantity.Weight -> Quantity.Weight(value)
        is Quantity.Volume -> Quantity.Volume(value)
        Quantity.Default -> Quantity.Count(value)
    }

    private fun rationale(desired: Int, unit: Int, packs: Int, delivered: Int, deviationPct: Float): String {
        val sign = if (deviationPct >= 0f) "+" else ""
        return "$desired requested; ${unit}-unit packs available; ordering $packs pack(s) (=$delivered, $sign${"%.1f".format(deviationPct)}%)"
    }
}
