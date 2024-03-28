package co.chainring.sequencer.core

import net.openhft.chronicle.core.OS
import net.openhft.chronicle.queue.ChronicleQueue

val inputQueue = ChronicleQueue.singleBuilder(OS.getTarget() + "/input").build()
val outputQueue = ChronicleQueue.singleBuilder(OS.getTarget() + "/output").build()
val sequencedQueue = ChronicleQueue.singleBuilder(OS.getTarget() + "/sequenced").build()
