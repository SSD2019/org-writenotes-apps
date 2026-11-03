package com.orgwritenotes

import com.orgwritenotes.sync.EmacsSyncClient
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D7: validates the shared fixture that the Elisp ERT suite also checks. */
class ProtocolGoldenTest {
    private fun fixture(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "test-fixtures/protocol-golden.json")
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        error("protocol-golden.json not found")
    }

    @Test
    fun fixtureProtocolVersionMatchesClient() {
        val versions = Regex("\"protocolVersion\"\\s*:\\s*(\\d+)").findAll(fixture()).map { it.groupValues[1].toInt() }.toList()
        assertTrue(versions.isNotEmpty())
        versions.forEach { assertEquals(EmacsSyncClient.PROTOCOL_VERSION, it) }
    }

    @Test
    fun fixtureContainsInkCommitFields() {
        val text = fixture()
        listOf("\"ink.commit\"", "\"baseEtag\"", "\"svgzBase64\"", "\"session.hello\"").forEach {
            assertTrue("missing $it", text.contains(it))
        }
    }
}

