package co.chainring.apps.api.middleware

object ServerSpans {
    val app = "app"
    val sqrCli = "sqrCli"
    val gtw = "gtw"
    val sqr = "sqr"
}

data class Span(
    val name: String,
    val start: Long?,
    val duration: Long,
)

object Tracer {
    private val traces = ThreadLocal.withInitial { mutableListOf<Span>() }

    fun <T> newSpan(name: String, body: () -> T): T {
        System.nanoTime().let { start ->
            return body().also {
                val duration = System.nanoTime() - start
                traces.get().add(Span(name, start, duration))
            }
        }
    }

    suspend fun <T> newCoroutineSpan(name: String, body: suspend () -> T): T {
        System.nanoTime().let { start ->
            return body().also {
                val duration = System.nanoTime() - start
                traces.get().add(Span(name, start, duration))
            }
        }
    }

    fun newSpan(name: String, duration: Long, start: Long? = null) {
        traces.get().add(Span(name, start, duration))
    }

    fun getDuration(name: String): Long? = traces.get().firstOrNull { it.name == name }?.duration

    fun serialize(): String = buildString {
        val traces: List<Span> = traces.get()

        traces.forEachIndexed { index, key ->
            if (index > 0) {
                append(", ")
            }
            append("${key.name};dur=${key.duration}")
            key.start?.let { append(";start=$it") }
        }
    }

    fun deserialize(value: String): List<Span> {
        if (value.isBlank()) return emptyList()

        return value.split(", ").map { spanString ->
            val fields = spanString.split(";")
            val spanName = fields[0]
            val duration = fields.firstOrNull { it.startsWith("dur=") }?.split("=")?.get(1)?.toLong()
                ?: throw IllegalArgumentException("Unexpected span duration")
            val start = fields.firstOrNull { it.startsWith("start=") }?.split("=")?.get(1)?.toLong()

            Span(spanName, start, duration)
        }
    }

    fun reset() {
        traces.get().clear()
    }
}
