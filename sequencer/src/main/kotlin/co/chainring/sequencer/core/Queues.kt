package co.chainring.sequencer.core

import net.openhft.chronicle.core.OS
import net.openhft.chronicle.queue.ChronicleQueue

val queueHome: String = System.getenv("QUEUE_HOME") ?: OS.getTarget()

val inputQueue = ChronicleQueue.singleBuilder("$queueHome/input").build()
val outputQueue = ChronicleQueue.singleBuilder("$queueHome/output").build()
val sequencedQueue = ChronicleQueue.singleBuilder("$queueHome/sequenced").build()
