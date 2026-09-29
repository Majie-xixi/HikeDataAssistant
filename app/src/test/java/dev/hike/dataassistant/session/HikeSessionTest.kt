package dev.hike.dataassistant.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeMonotonicClock(var now: Long = 0L) : MonotonicClock {
    override fun nowMs(): Long = now
}

class HikeSessionTest {

    private val clock = FakeMonotonicClock()
    private val session = HikeSession(clock)

    @Test
    fun `start begins recording and time accumulates`() {
        clock.now = 1_000
        session.start()
        assertEquals(SessionState.RECORDING, session.state)
        clock.now = 3_000
        assertEquals(2_000L, session.activeDurationMs())
        assertEquals(2_000L, session.elapsedDurationMs())
    }

    @Test
    fun `pause freezes active but elapsed keeps growing`() {
        clock.now = 0
        session.start()
        clock.now = 10_000
        session.pause()
        assertEquals(SessionState.PAUSED, session.state)
        clock.now = 25_000
        assertEquals(10_000L, session.activeDurationMs())
        assertEquals(25_000L, session.elapsedDurationMs())
    }

    @Test
    fun `resume accumulates active across pause`() {
        clock.now = 0
        session.start()
        clock.now = 10_000
        session.pause()
        clock.now = 20_000
        session.resume()
        clock.now = 25_000
        assertEquals(15_000L, session.activeDurationMs())
        assertEquals(25_000L, session.elapsedDurationMs())
    }

    @Test
    fun `multiple pause resume cycles sum active segments`() {
        clock.now = 0
        session.start()
        clock.now = 10_000
        session.pause()
        clock.now = 12_000
        session.resume()
        clock.now = 20_000
        session.pause()
        clock.now = 30_000
        session.resume()
        clock.now = 35_000
        assertEquals(23_000L, session.activeDurationMs())
        assertEquals(35_000L, session.elapsedDurationMs())
    }

    @Test
    fun `stop freezes both durations`() {
        clock.now = 0
        session.start()
        clock.now = 8_000
        session.stop()
        assertEquals(SessionState.STOPPED, session.state)
        clock.now = 100_000
        assertEquals(8_000L, session.activeDurationMs())
        assertEquals(8_000L, session.elapsedDurationMs())
    }

    @Test
    fun `idle session reports null durations`() {
        assertNull(session.activeDurationMs())
        assertNull(session.elapsedDurationMs())
        assertEquals(SessionState.IDLE, session.state)
    }

    @Test(expected = IllegalStateException::class)
    fun `pause before start is rejected`() {
        session.pause()
    }

    @Test(expected = IllegalStateException::class)
    fun `resume while recording is rejected`() {
        clock.now = 0
        session.start()
        session.resume()
    }

    @Test(expected = IllegalStateException::class)
    fun `start after stop without reset is rejected`() {
        clock.now = 0
        session.start()
        clock.now = 1_000
        session.stop()
        session.start()
    }

    @Test
    fun `reset returns to idle for a new session`() {
        clock.now = 0
        session.start()
        clock.now = 1_000
        session.stop()
        session.reset()
        assertEquals(SessionState.IDLE, session.state)
        assertNull(session.activeDurationMs())
        clock.now = 5_000
        session.start()
        assertEquals(0L, session.activeDurationMs())
    }

    @Test
    fun `isRecordingAt reflects pause intervals`() {
        clock.now = 0
        session.start()
        clock.now = 10_000
        session.pause()
        clock.now = 20_000
        session.resume()
        clock.now = 30_000
        session.stop()
        assertTrue(session.isRecordingAt(5_000))
        assertFalse(session.isRecordingAt(15_000))
        assertTrue(session.isRecordingAt(25_000))
        assertTrue(session.isRecordingAt(29_999))
        assertFalse(session.isRecordingAt(30_000))
    }

    @Test
    fun `pause segments expose wall-clock intervals for persistence`() {
        clock.now = 0
        session.start()
        clock.now = 10_000
        session.pause()
        clock.now = 20_000
        session.resume()
        clock.now = 30_000
        val activeWindows = session.activeWindowsMs()
        assertEquals(listOf(0L to 10_000L, 20_000L to 30_000L), activeWindows)
    }
}
