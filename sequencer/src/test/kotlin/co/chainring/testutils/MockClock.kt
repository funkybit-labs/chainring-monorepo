package co.chainring.testutils

import co.chainring.sequencer.core.Clock

class MockClock : Clock() {
    private var nanoTime: Long = System.nanoTime()
    private var currenTimeMillis: Long = System.currentTimeMillis()

    override fun nanoTime(): Long =
        nanoTime

    fun setNanoTime(value: Long) {
        nanoTime = value
    }

    override fun currentTimeMillis(): Long =
        currenTimeMillis

    fun setCurrentTimeMillis(value: Long) {
        currenTimeMillis = value
    }
}
