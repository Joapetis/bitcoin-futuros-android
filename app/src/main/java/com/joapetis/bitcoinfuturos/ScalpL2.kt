package com.joapetis.bitcoinfuturos

import java.util.TreeMap
import kotlin.math.min

data class BookMetrics(
    val bid: Double,
    val ask: Double,
    val spreadBps: Double,
    val imbalance: Double,
    val microprice: Double,
    val micropriceEdgeBps: Double
)

object L2Metrics {
    fun compute(bids: TreeMap<Double, Double>, asks: TreeMap<Double, Double>, levels: Int = 10): BookMetrics? {
        if (bids.isEmpty() || asks.isEmpty()) return null
        val bid = bids.firstKey(); val ask = asks.firstKey()
        if (bid <= 0.0 || ask <= bid) return null
        var bidWeighted = 0.0; var askWeighted = 0.0; var i = 0
        for (entry in bids.entries) { if (i >= levels) break; bidWeighted += entry.value / (1.0 + i); i++ }
        i = 0
        for (entry in asks.entries) { if (i >= levels) break; askWeighted += entry.value / (1.0 + i); i++ }
        val total = bidWeighted + askWeighted
        val imbalance = if (total > 0.0) (bidWeighted - askWeighted) / total else 0.0
        val bestBidQty = bids[bid] ?: 0.0; val bestAskQty = asks[ask] ?: 0.0
        val mid = (bid + ask) / 2.0
        val micro = if (bestBidQty + bestAskQty > 0.0) (ask * bestBidQty + bid * bestAskQty) / (bestBidQty + bestAskQty) else mid
        return BookMetrics(bid, ask, (ask - bid) / mid * 10_000.0, imbalance, micro, (micro - mid) / mid * 10_000.0)
    }
}

data class SizedPosition(
    val qty: Double,
    val notional: Double,
    val effectiveLeverage: Double,
    val riskAmount: Double,
    val liquidationDistanceRate: Double
)

object CompoundSizing {
    const val MAINTENANCE_MARGIN_RATE = 0.005
    private const val USABLE_MARGIN = 0.95
    private const val STOP_TO_LIQUIDATION_MAX = 0.60

    fun size(
        balance: Double,
        price: Double,
        maxLeverage: Int,
        stopRate: Double,
        riskFraction: Double,
        roundTripCostRate: Double
    ): SizedPosition? {
        if (balance <= 0.0 || price <= 0.0 || stopRate <= 0.0 || riskFraction <= 0.0 || maxLeverage < 1) return null
        val lossRateAtStop = stopRate + roundTripCostRate
        val riskAmount = balance * riskFraction
        var notional = riskAmount / lossRateAtStop
        notional = min(notional, balance * maxLeverage * USABLE_MARGIN)
        val maxLeverageForStop = 1.0 / (stopRate / STOP_TO_LIQUIDATION_MAX + MAINTENANCE_MARGIN_RATE)
        notional = min(notional, balance * maxLeverageForStop)
        if (notional <= 0.0 || !notional.isFinite()) return null
        val effectiveLeverage = notional / balance
        val liquidationDistance = 1.0 / effectiveLeverage - MAINTENANCE_MARGIN_RATE
        if (liquidationDistance <= stopRate) return null
        return SizedPosition(notional / price, notional, effectiveLeverage, riskAmount, liquidationDistance)
    }
}
