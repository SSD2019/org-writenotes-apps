package com.orgwritenotes.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchMergeTest {
    private fun doc(w: Int, h: Int, vararg strokes: String) = """
        <svg id="write-document" xmlns="http://www.w3.org/2000/svg">
        <svg class="write-page" x="10" y="0" width="${w}px" height="${h}px">
          <g class="write-content write-v3" xruling="0" yruling="40">
            <g class="ruleline"><rect class="pagerect" x="0" y="0" width="$w" height="$h" /></g>
        ${strokes.joinToString("\n")}
          </g>
        </svg>
        <defs id="write-defs"><g id="write-pages"><use href="#page_001"/></g></defs>
        </svg>
    """.trimIndent()

    private fun path(id: String, d: String) = """<path id="$id" class="write-round-pen" d="$d" />"""

    @Test
    fun unionKeepsStrokesFromBothCopiesOnce() {
        val emacs = doc(1200, 600, path("s_1", "M1 1 L2 2"), path("s_2", "M5 5 L6 6"))
        val tablet = doc(1200, 600, path("s_1", "M1 1 L2 2"), path("s_2", "M9 9 L8 8"))
        val r = SketchMerge.mergeSvg(emacs, tablet)
        assertNotNull(r)
        r!!
        assertEquals(1, r.addedStrokes)
        assertEquals(1, Regex("M1 1 L2 2").findAll(r.svg).count())
        assertTrue(r.svg.contains("M5 5 L6 6"))
        assertTrue(r.svg.contains("""id="m1_s_2" class="write-round-pen" d="M9 9 L8 8""""))
        // Added strokes land inside write-content, before the page closes.
        assertTrue(r.svg.indexOf("M9 9") < r.svg.indexOf("</svg>"))
    }

    @Test
    fun movedStrokeIsNotDuplicated() {
        val emacs = doc(1200, 600, """<path id="s_1" __timestamp="0x1" d="M1 1" />""")
        val tablet = doc(1200, 600, """<path id="s_1" __timestamp="0x1" d="M40 40" />""", """<path id="s_2" __timestamp="0x1" d="M7 7" />""")
        val r = SketchMerge.mergeSvg(emacs, tablet)!!
        assertTrue(!r.svg.contains("M40 40"))
        // Same timestamp but a different id (a duplicate) is a separate stroke.
        assertTrue(r.svg.contains("M7 7"))
    }

    @Test
    fun largerPageIsTheBase() {
        val small = doc(1200, 600, path("s_1", "M1 1"))
        val tall = doc(1200, 1800, path("s_1", "M3 3"))
        val r = SketchMerge.mergeSvg(small, tall)!!
        assertTrue(r.svg.contains("height=\"1800px\""))
        assertTrue(r.svg.contains("M1 1") && r.svg.contains("M3 3"))
    }

    @Test
    fun gzipRoundTrip() {
        val a = doc(800, 400, path("s_1", "M1 1"))
        val b = doc(800, 400, path("s_1", "M2 2"))
        val merged = SketchMerge.mergeSvgz(SketchMerge.gzip(a), SketchMerge.gzip(b))!!
        val text = SketchMerge.gunzip(merged)!!
        assertTrue(text.contains("M1 1") && text.contains("M2 2"))
    }
}
