package com.example.beta.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PackSolverTest {
    @Test
    fun solve_exactWeightPack() {
        val plan = PackSolver.solve(
            desired = Quantity.Weight(500),
            available = listOf(PackOption(unitGrams = 200), PackOption(unitGrams = 250), PackOption(unitGrams = 500))
        )

        assertNotNull(plan)
        assertEquals(2, plan!!.skuIndex)
        assertEquals(1, plan.packsToAdd)
        assertEquals(Quantity.Weight(500), plan.deliveredQuantity)
    }

    @Test
    fun solve_combinesWeightPacks() {
        val plan = PackSolver.solve(
            desired = Quantity.Weight(500),
            available = listOf(PackOption(unitGrams = 200), PackOption(unitGrams = 250))
        )

        assertNotNull(plan)
        assertEquals(1, plan!!.skuIndex)
        assertEquals(2, plan.packsToAdd)
        assertEquals(Quantity.Weight(500), plan.deliveredQuantity)
    }

    @Test
    fun solve_roundsWeightUp() {
        val plan = PackSolver.solve(
            desired = Quantity.Weight(500),
            available = listOf(PackOption(unitGrams = 200))
        )

        assertNotNull(plan)
        assertEquals(3, plan!!.packsToAdd)
        assertEquals(Quantity.Weight(600), plan.deliveredQuantity)
    }

    @Test
    fun solve_roundsWeightDown() {
        val plan = PackSolver.solve(
            desired = Quantity.Weight(500),
            available = listOf(PackOption(unitGrams = 200)),
            policy = RoundingPolicy.ROUND_DOWN
        )

        assertNotNull(plan)
        assertEquals(2, plan!!.packsToAdd)
        assertEquals(Quantity.Weight(400), plan.deliveredQuantity)
    }

    @Test
    fun solve_countPacks() {
        val plan = PackSolver.solve(
            desired = Quantity.Count(6),
            available = listOf(PackOption(unitCount = 4), PackOption(unitCount = 6))
        )

        assertNotNull(plan)
        assertEquals(1, plan!!.skuIndex)
        assertEquals(1, plan.packsToAdd)
        assertEquals(Quantity.Count(6), plan.deliveredQuantity)
    }

    @Test
    fun solve_volumePacks() {
        val plan = PackSolver.solve(
            desired = Quantity.Volume(1000),
            available = listOf(PackOption(unitMl = 500), PackOption(unitMl = 1000))
        )

        assertNotNull(plan)
        assertEquals(1, plan!!.skuIndex)
        assertEquals(Quantity.Volume(1000), plan.deliveredQuantity)
    }
}
