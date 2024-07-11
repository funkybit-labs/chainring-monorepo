package co.chainring.sequencer.core

open class Clock {
    open fun nanoTime(): Long =
        System.nanoTime()

    open fun currentTimeMillis(): Long =
        System.currentTimeMillis()
}
