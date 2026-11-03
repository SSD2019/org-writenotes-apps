package com.orgwritenotes

import com.orgwritenotes.ink.AudioSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioSessionTest {
    private val s = AudioSession("a.svgz", "/x.m4a", 0L, 60_000L, listOf(1_000L, 5_000L, 9_000L))

    @Test
    fun nextAndPreviousStroke() {
        assertEquals(1_000L, s.nextStroke(0))
        assertEquals(5_000L, s.nextStroke(1_000))
        assertEquals(9_000L, s.nextStroke(5_000))
        assertNull(s.nextStroke(9_000))
        assertEquals(5_000L, s.previousStroke(9_000))
        assertEquals(0L, s.previousStroke(500))
    }
}

