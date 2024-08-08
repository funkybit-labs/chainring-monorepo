package xyz.funkybit.sequencer.core

import net.openhft.chronicle.core.OS
import net.openhft.chronicle.queue.ChronicleQueue

val queueHome: String = System.getenv("QUEUE_HOME") ?: OS.getTarget()

val inputQueue = ChronicleQueue.singleBuilder("$queueHome/input_queue").build()
val outputQueue = ChronicleQueue.singleBuilder("$queueHome/output_queue").build()
val sequencedQueue = ChronicleQueue.singleBuilder("$queueHome/sequenced_queue").build()
val checkpointsQueue = ChronicleQueue.singleBuilder("$queueHome/checkpoints_queue").build()
