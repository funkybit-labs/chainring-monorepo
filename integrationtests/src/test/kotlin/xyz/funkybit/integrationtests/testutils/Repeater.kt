package xyz.funkybit.integrationtests.testutils

import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.notifyDbListener
import xyz.funkybit.core.repeater.REPEATER_APP_TASK_CTL_CHANNEL
import xyz.funkybit.core.repeater.tasks.REPEATER_APP_TASK_DONE_CHANNEL
import xyz.funkybit.core.utils.PgListener

fun triggerRepeaterTaskAndWaitForCompletion(taskName: String, taskArgs: List<String> = emptyList()) {
    var taskIsDone = false

    val pgListener = PgListener(
        TransactionManager.defaultDatabase!!,
        "test_repeater_app_task_listener",
        REPEATER_APP_TASK_DONE_CHANNEL,
        {},
    ) { notification ->
        if (notification.parameter == taskName) {
            taskIsDone = true
        }
    }
    try {
        pgListener.start()

        transaction {
            notifyDbListener(REPEATER_APP_TASK_CTL_CHANNEL, (listOf(taskName) + taskArgs).joinToString(":"))
        }

        waitFor { taskIsDone }
    } finally {
        pgListener.stop()
    }
}
