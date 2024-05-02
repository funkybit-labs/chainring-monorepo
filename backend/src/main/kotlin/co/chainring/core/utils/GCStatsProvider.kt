package co.chainring.core.utils

import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.management.ManagementFactory
import java.lang.management.MemoryUsage
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes

class GCStatsProvider {
    private val logger = KotlinLogging.logger {}
    private var gcLastValues: MutableMap<String, Pair<Long, Long>> = mutableMapOf()

    private val statsWriter = thread(start = false, name = "gc-stats-writer") {
        while (true) {
            while (true) {
                generateGcAndMemoryStats().also {
                    logger.debug { it }
                }
                Thread.sleep(1.minutes.inWholeMilliseconds)
            }
        }
    }

    private fun generateGcAndMemoryStats(): String {
        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
        val memoryBean = ManagementFactory.getMemoryMXBean()

        return buildString {
            appendLine("=== GC and Memory Stats ===")

            gcBeans.forEach { bean ->
                val previousValues = gcLastValues.getOrDefault(bean.name, Pair(0, 0))
                gcLastValues[bean.name] = Pair(bean.collectionCount, bean.collectionTime)

                appendLine("GC Name: ${bean.name}")
                appendLine("Collection Count: ${bean.collectionCount - previousValues.first} (${bean.collectionCount} from JVM start)")
                appendLine("Collection Time: ${bean.collectionTime - previousValues.second}ms (${bean.collectionTime}ms from JVM start)")
                appendLine("Memory Pools: ${bean.memoryPoolNames.joinToString(", ")}")
                appendLine("")
            }

            val heapUsage: MemoryUsage = memoryBean.heapMemoryUsage
            val nonHeapUsage: MemoryUsage = memoryBean.nonHeapMemoryUsage

            appendLine("Heap Memory Usage:")
            appendLine("  Init: ${heapUsage.init / 1_000_000} MB")
            appendLine("  Used: ${heapUsage.used / 1_000_000} MB")
            appendLine("  Committed: ${heapUsage.committed / 1_000_000} MB")
            appendLine("  Max: ${heapUsage.max / 1_000_000} MB")
            appendLine("Non-Heap Memory Usage:")
            appendLine("  Init: ${nonHeapUsage.init / 1_000_000} MB")
            appendLine("  Used: ${nonHeapUsage.used / 1_000_000} MB")
            appendLine("  Committed: ${nonHeapUsage.committed / 1_000_000} MB")
            appendLine("  Max: ${nonHeapUsage.max / 1_000_000} MB")
        }
    }

    fun start() {
        if (!statsWriter.isAlive) {
            statsWriter.start()
        }
    }

    fun stop() {
        statsWriter.interrupt()
        statsWriter.join()
    }
}
