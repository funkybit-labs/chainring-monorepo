package xyz.funkybit.core.telemetry

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import org.jetbrains.exposed.sql.Key
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.sql.statements.StatementContext
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi

class StatementInterceptor : GlobalStatementInterceptor {
    private val tracer = openTelemetry.getTracer("exposed-instrumentation")
    private val spanKey = Key<Span>()

    override fun beforeExecution(transaction: Transaction, context: StatementContext) {
        // record sql span only when trace was already created.
        // this avoids unattached sql spans from background tasks when task is not instrumented
        if (Span.current().spanContext.isValid) {
            val span = tracer.spanBuilder("database.query")
                .setParent(Context.current().with(Span.current()))
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("db.statement", context.sql(transaction))
                .startSpan()

            transaction.putUserData(spanKey, span)
        }
    }

    override fun afterExecution(transaction: Transaction, contexts: List<StatementContext>, executedStatement: PreparedStatementApi) {
        transaction.getUserData(spanKey)?.end()
    }
}
