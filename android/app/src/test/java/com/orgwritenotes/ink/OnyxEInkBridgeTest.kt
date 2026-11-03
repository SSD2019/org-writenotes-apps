package com.orgwritenotes.ink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class OnyxEInkBridgeTest {

    @Test
    fun `isEInkDevice evaluates safely without throwing on non-Onyx JVM environment`() {
        // On a standard JVM / non-Onyx device without com.onyx.android.sdk classes,
        // isEInkDevice should safely evaluate to false.
        assertFalse(OnyxEInkBridge.isEInkDevice)
    }

    @Test
    fun `WritePageSetupState defaults remain valid`() {
        val state = WritePageSetupState()
        assertNotNull(state)
    }
}

