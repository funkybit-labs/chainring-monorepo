package xyz.funkybit.core.utils

import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.Response
import xyz.funkybit.apps.api.middleware.ServerSpans
import xyz.funkybit.apps.api.middleware.Span
import xyz.funkybit.apps.api.middleware.Tracer
import kotlin.text.StringBuilder

interface TraceRecorder {
    enum class Op {
        GetConfiguration,
        GetAccountConfiguration,
        MarkSymbolAsAdded,
        AuthorizeWallet,
        CreateOrder,
        CancelOrder,
        GetOrder,
        GetOrderBook,
        GetLimits,
        ListOrders,
        CancelOpenOrders,
        BatchOrders,
        ListTrades,
        GetBalances,
        GetWithdrawal,
        ListWithdrawals,
        GetDeposit,
        ListDeposits,
        CreateWithdrawal,
        CreateDeposit,
        WS,
        FaucetDrip,
        CreateSymbol,
        ListSymbols,
        PatchSymbol,
        CreateMarket,
        ListMarkets,
        PatchMarket,
        ListAdmins,
        AddAdmin,
        RemoveAdmin,
        SetFeeRates,
        GetLastPrice,
        TestnetChallengeEnroll,
        SetNickName,
        GetLeaderboard,
    }

    data class Trace(
        val spans: List<Span>,
        val amzTraceId: String? = null,
    )

    data class PendingSpan(
        val name: String,
        val startedAt: Long,
    )

    fun record(op: Op, spans: List<Span>, amzTraceId: String?)
    fun record(op: Op, request: () -> Response): Response

    fun startWSRecording(id: String, spanName: String)
    fun finishWSRecording(id: String, spanName: String)

    fun generateStatsAndFlush(header: String): String

    companion object {
        val noOp = NoOpTraceRecorder()
        val full = FullTraceRecorder()
    }
}

class NoOpTraceRecorder : TraceRecorder {
    override fun record(op: TraceRecorder.Op, spans: List<Span>, amzTraceId: String?) {}
    override fun record(op: TraceRecorder.Op, request: () -> Response): Response {
        return request()
    }

    override fun startWSRecording(id: String, spanName: String) {}

    override fun finishWSRecording(id: String, spanName: String) {}
    override fun generateStatsAndFlush(header: String): String = ""
}

object ClientSpans {
    val apiClient = "apiClient"
}
object WSSpans {
    val orderCreated = "orderCreated"
    val orderFilled = "orderFilled"
    val tradeCreated = "tradeCreated"
    val tradeSettled = "tradeSettled"
}

class FullTraceRecorder : TraceRecorder {
    private val logger = KotlinLogging.logger {}
    private var tracesByOp: MutableMap<TraceRecorder.Op, MutableList<TraceRecorder.Trace>> = mutableMapOf()
    private var pendingEvents: MutableMap<String, TraceRecorder.PendingSpan> = mutableMapOf()

    override fun startWSRecording(id: String, spanName: String) {
        val nanoTime = System.nanoTime()
        pendingEvents.getOrPut(id + spanName) {
            TraceRecorder.PendingSpan(spanName, nanoTime)
        }
    }

    override fun finishWSRecording(id: String, spanName: String) {
        val nanoTime = System.nanoTime()
        pendingEvents.remove(id + spanName)?.let { pendingSpan ->
            record(
                op = TraceRecorder.Op.WS,
                spans = listOf(Span(pendingSpan.name, pendingSpan.startedAt, nanoTime - pendingSpan.startedAt)),
                amzTraceId = null,
            )
        }
    }

    override fun record(op: TraceRecorder.Op, spans: List<Span>, amzTraceId: String?) {
        tracesByOp.getOrPut(op) { mutableListOf() }.apply {
            this.add(TraceRecorder.Trace(spans, amzTraceId))
        }
    }

    override fun record(op: TraceRecorder.Op, request: () -> Response): Response {
        System.nanoTime().let { start ->
            return request().also { response ->
                val duration = System.nanoTime() - start

                val amznTraceId = response.headers["X-Amzn-Trace-Id"]
                val serverSpans = response.headers["Server-Timing"]?.let { Tracer.deserialize(it) } ?: emptyList()
                val clientSpan = Span(ClientSpans.apiClient, null, duration)

                record(op, serverSpans + clientSpan, amznTraceId)
            }
        }
    }

    private val percentiles = listOf(50.0, 66.0, 75.0, 80.0, 90.0, 95.0, 98.0, 99.0, 100.0)
    private val spansOrder = listOf(
        ClientSpans.apiClient,
        ServerSpans.app,
        ServerSpans.sqrClt,
        ServerSpans.gtw,
        ServerSpans.sqr,
        WSSpans.orderCreated,
        WSSpans.orderFilled,
        WSSpans.tradeCreated,
        WSSpans.tradeSettled,
    ).withIndex().associate { it.value to it.index }
    private val padding = 25

    override fun generateStatsAndFlush(header: String): String {
        val traces = tracesByOp
        tracesByOp = mutableMapOf()

        val output = buildString {
            appendLine()
            val headerString = "========= $header ========="
            appendLine(headerString)
            printSpanStats(this, traces)
            appendLine("".padEnd(headerString.length, '='))
        }

        return output
    }

    private fun printSpanStats(sb: StringBuilder, traces: MutableMap<TraceRecorder.Op, MutableList<TraceRecorder.Trace>>) {
        traces.entries.toList().sortedBy { it.key }.forEach { (op, traces: MutableList<TraceRecorder.Trace>) ->
            sb.appendLine()
            sb.appendLine("Stats for: ${op.name} (${traces.size} records)")

            val latencies: Map<String, List<Long>> = traces.map { it.spans }.flatten().groupBy(
                keySelector = { span -> span.name },
                valueTransform = { span -> span.duration },
            )

            val sortedSpans = latencies.keys.sortedWith { a, b ->
                (spansOrder[a] ?: Int.MAX_VALUE).compareTo(spansOrder[b] ?: Int.MAX_VALUE)
            }

            sortedSpans.forEach { spanName ->
                val samplesCount = (latencies[spanName]?.size ?: 0).let { count ->
                    if (traces.size != count) " ($count)" else ""
                }
                sb.append((spanName + samplesCount).padEnd(padding))
            }
            sb.appendLine()
            percentiles.forEach { p ->
                sortedSpans.forEach { span ->
                    val value = latencies[span]?.let { humanReadableNanoseconds(percentile(it, p)) } ?: "NA"
                    sb.append(" $p%: $value".padEnd(padding))
                }
                sb.appendLine()
            }
        }
    }
}
