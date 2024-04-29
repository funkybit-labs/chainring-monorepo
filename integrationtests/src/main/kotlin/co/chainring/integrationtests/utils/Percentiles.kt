package co.chainring.integrationtests.utils

import kotlin.math.ceil

fun percentile(latencies: List<Long>, percentile: Double): Long {
    if (latencies.isEmpty()) throw IllegalArgumentException("Latencies cannot be empty")

    return latencies.sorted().let { sortedLatencies ->
        val index = ceil(percentile / 100.0 * sortedLatencies.size).toInt()
        sortedLatencies[index - 1]
    }
}

fun percentiles(latencies: List<Long>, percentiles: List<Double> = listOf(50.0, 66.0, 75.0, 80.0, 90.0, 95.0, 98.0, 99.0, 100.0)): List<Pair<Double, Long>> {
    if (latencies.isEmpty()) throw IllegalArgumentException("Latencies cannot be empty")
    if (percentiles.isEmpty()) throw IllegalArgumentException("Percentiles cannot be empty")

    return latencies.sorted().let { sortedLatencies ->
        percentiles.map { percentile ->
            val index = ceil(percentile / 100.0 * sortedLatencies.size).toInt()
            percentile to sortedLatencies[index - 1]
        }
    }
}
