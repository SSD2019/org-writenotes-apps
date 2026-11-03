package com.orgwritenotes

import com.orgwritenotes.ink.OcrSmartInsert
import com.orgwritenotes.ink.SvgLayers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrSmartInsertTest {
    @Test
    fun parsesCheckboxes() {
        assertEquals(OcrSmartInsert.Checkbox(false, "buy milk"), OcrSmartInsert.parseCheckbox("[ ] buy milk"))
        assertEquals(OcrSmartInsert.Checkbox(false, "buy milk"), OcrSmartInsert.parseCheckbox("[] buy milk"))
        assertEquals(OcrSmartInsert.Checkbox(true, "done"), OcrSmartInsert.parseCheckbox("[x] done"))
        assertEquals(OcrSmartInsert.Checkbox(true, "done"), OcrSmartInsert.parseCheckbox("☑ done"))
        assertNull(OcrSmartInsert.parseCheckbox("[ ]"))
        assertNull(OcrSmartInsert.parseCheckbox("plain text"))
    }

    @Test
    fun detectsMath() {
        assertTrue(OcrSmartInsert.looksLikeMath("x^2 + 3 = 7"))
        assertTrue(OcrSmartInsert.looksLikeMath("a/b"))
        assertFalse(OcrSmartInsert.looksLikeMath("Meeting notes about the quarterly roadmap"))
        assertFalse(OcrSmartInsert.looksLikeMath(""))
    }

    @Test
    fun convertsToLatex() {
        assertEquals("x^{2} + 1", OcrSmartInsert.toLatex("x^2 + 1"))
        assertEquals("\\frac{a}{b}", OcrSmartInsert.toLatex("a/b"))
        assertEquals("\\frac{a+b}{c}", OcrSmartInsert.toLatex("(a+b)/c"))
        assertEquals("\\sqrt{x}", OcrSmartInsert.toLatex("sqrt(x)"))
        assertEquals("a \\le b", OcrSmartInsert.toLatex("a <= b").trim())
        assertEquals("2 \\cdot 3", OcrSmartInsert.toLatex("2*3"))
        assertEquals("\\(x^{n}\\)", OcrSmartInsert.toLatexParagraph("x^n"))
        assertTrue(OcrSmartInsert.toLatex("2 pi r").contains("\\pi"))
    }
}

class SvgLayersTest {
    private val svg = """<svg><g>
        <path d="M0 0" stroke="#000" stroke-opacity="1"/>
        <path d="M1 1" stroke="#ff0" stroke-opacity="0.4"/>
        <path d="M2 2" stroke="#ff0" stroke-opacity="0.4" ></path>
        </g></svg>"""

    @Test
    fun countsHighlights() {
        assertEquals(2, SvgLayers.countHighlights(svg))
    }

    @Test
    fun hideAndShowRoundTrips() {
        val hidden = SvgLayers.setHighlightsHidden(svg, true)
        assertEquals(2, Regex("display=\"none\"").findAll(hidden).count())
        assertTrue(SvgLayers.highlightsHidden(hidden))
        assertFalse(hidden.substringBefore("M1 1").contains("display="))
        val shown = SvgLayers.setHighlightsHidden(hidden, false)
        assertFalse(shown.contains("display="))
        assertFalse(SvgLayers.highlightsHidden(shown))
        // Hiding twice must not duplicate the attribute.
        assertEquals(hidden, SvgLayers.setHighlightsHidden(hidden, true))
    }

    @Test
    fun gzipBase64RoundTrip() {
        val b64 = SvgLayers.gzipToBase64(svg)
        assertNotNull(b64)
        assertEquals(svg, SvgLayers.gunzipBase64(b64))
        val toggled = SvgLayers.gunzipBase64(SvgLayers.toggleHighlightsBase64(b64, true))
        assertTrue(SvgLayers.highlightsHidden(toggled))
    }
}

