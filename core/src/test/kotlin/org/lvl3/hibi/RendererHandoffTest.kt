package org.lvl3.hibi

import org.junit.Assert.*
import org.junit.Test
import org.lvl3.hibi.model.RendererHandoff

class RendererHandoffTest {
    @Test fun delayedOutgoingSnapshotPrecedesNewInitializationAndRejectsOldOwnership() {
        var finish: (() -> Unit)? = null; var completion: (() -> Unit)? = null
        var words = "A"
        val old = Any(); val next = Any()
        val handoff = RendererHandoff<Any> { owner, done -> assertSame(old, owner); completion = done; finish = { words = "AB"; done() } }
        handoff.attach(old) {}
        var initialized: String? = null
        handoff.release(old)
        handoff.attach(next) { initialized = words }
        assertTrue(handoff.waiting); assertNull(initialized); assertTrue(handoff.isCurrent(old))
        finish!!()
        assertEquals("AB", initialized); assertTrue(handoff.isCurrent(next)); assertFalse(handoff.isCurrent(old))
        words = "ABC"; completion!!()
        assertEquals("ABC", words); assertSame(next, handoff.current)
    }

    @Test fun anotherRotationSkipsTheUninitializedIntermediateRenderer() {
        var finish: (() -> Unit)? = null; var reads = 0
        val handoff = RendererHandoff<Any> { _, done -> reads++; finish = done }
        val first = Any(); val intermediate = Any(); val last = Any()
        handoff.attach(first) {}
        handoff.attach(intermediate) { fail("Retired intermediate renderer must never initialize") }
        handoff.release(first); handoff.release(intermediate)
        var lastStarted = false
        handoff.attach(last) { lastStarted = true }
        assertEquals(1, reads); assertFalse(lastStarted)
        finish!!()
        assertTrue(lastStarted); assertSame(last, handoff.current)
    }

    @Test fun closingPendingSuccessorDoesNotReopenItAfterTheSnapshot() {
        var finish: (() -> Unit)? = null
        val handoff = RendererHandoff<Any> { _, done -> finish = done }
        val first = Any(); val next = Any()
        handoff.attach(first) {}
        handoff.attach(next) { fail("Closed renderer must not activate") }
        handoff.release(next); finish!!()
        assertNull(handoff.current); assertFalse(handoff.waiting)
    }
}
