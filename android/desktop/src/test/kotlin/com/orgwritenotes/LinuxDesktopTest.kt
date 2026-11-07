package com.orgwritenotes

import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import com.orgwritenotes.ink.LinuxStylusBridge
import com.orgwritenotes.ink.WriteNativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class LinuxDesktopTest {

    @Test
    fun testNativeEngineAndJniBitmapRenderOnLinux() {
        assertTrue(
            "liborgwritenotes_jni.so should load on Linux Desktop",
            WriteNativeBridge.isNativeLoaded
        )

        val handle = WriteNativeBridge.nativeCreateSession(800, 600, 0, 40)
        assertTrue("NativeCanvasSession handle should be non-zero", handle != 0L)

        try {
            WriteNativeBridge.nativeSetPen(
                handle = handle,
                colorArgb = 0xFF1565C0.toInt(),
                strokeWidth = 3.2f,
                pressureSensitive = true,
                tipType = 1,
                snapToGrid = false,
                lineDrawing = false,
                chiselAngle = 45f
            )

            // Simulate a Wacom EMR pressure + tilt stroke across the canvas
            WriteNativeBridge.nativeBeginStroke(handle, 100f, 120f, 0.35f, 12f, -8f, 1000.0)
            WriteNativeBridge.nativeMoveStroke(handle, 180f, 140f, 0.65f, 14f, -6f, 1016.0)
            WriteNativeBridge.nativeMoveStroke(handle, 260f, 165f, 0.85f, 15f, -5f, 1032.0)
            val outDims = IntArray(6)
            val pathXml = WriteNativeBridge.nativeEndStroke(handle, outDims)
            assertTrue("nativeEndStroke should produce non-empty SVG path XML", pathXml.isNotEmpty())

            // Export .svgz and verify gzipped SVG content
            val svgzBytes = WriteNativeBridge.nativeExportSvgz(handle)
            assertNotNull("nativeExportSvgz should return non-null bytes", svgzBytes)
            assertTrue("svgzBytes should be non-empty", svgzBytes!!.isNotEmpty())

            val svgXml = GZIPInputStream(ByteArrayInputStream(svgzBytes))
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            assertTrue("Exported SVG XML should contain write-doc", svgXml.contains("write-doc"))
            assertTrue("Exported SVG XML should contain drawn stroke path", svgXml.contains("<path"))

            // Render to Desktop Bitmap via JNI lockDesktopBitmapAndRender
            val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
            val genBefore = bmp.generation
            val rendered = WriteNativeBridge.nativeRenderToBitmap(handle, bmp)
            assertTrue("nativeRenderToBitmap should succeed on Linux Desktop Bitmap", rendered)
            assertTrue("Bitmap generation counter should increment after JNI render", bmp.generation > genBefore)

            // Test toComposeImageBitmap rendering
            val img1 = bmp.toComposeImageBitmap()
            assertNotNull(img1)
            assertEquals("ImageBitmap width should match Bitmap width", bmp.width, img1.width)
            assertEquals("ImageBitmap height should match Bitmap height", bmp.height, img1.height)

            // Count non-zero alpha pixels in rgbaPixels
            var nonZeroPixels = 0
            for (i in bmp.rgbaPixels.indices step 4) {
                if (bmp.rgbaPixels[i + 3].toInt() != 0) nonZeroPixels++
            }
            println("Non-zero alpha pixels: $nonZeroPixels out of ${bmp.width * bmp.height}")
            assertTrue("Should have rendered paper/strokes with non-zero alpha", nonZeroPixels > 0)

            // Verify undo & redo
            assertTrue("Should be able to undo stroke", WriteNativeBridge.nativeCanUndo(handle))
            val undoDims = IntArray(2)
            assertTrue("nativeUndo should return true", WriteNativeBridge.nativeUndo(handle, undoDims))
            assertTrue("Should be able to redo stroke", WriteNativeBridge.nativeCanRedo(handle))
            assertTrue("nativeRedo should return true", WriteNativeBridge.nativeRedo(handle, undoDims))
        } finally {
            WriteNativeBridge.nativeDestroySession(handle)
        }
    }

    @Test
    fun testLinuxStylusBridgeHardwareEventFusion() {
        assertTrue(
            "LinuxStylusBridge should initialize with JNI library",
            LinuxStylusBridge.ensureInitialized()
        )

        // 1. Inject simulated Wacom EMR Stylus tip down with pressure 0.78 and tilt (18 deg, -12 deg)
        val btnMask = MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_STYLUS_PRIMARY
        LinuxStylusBridge.nativeInjectTestEvent(
            toolType = MotionEvent.TOOL_TYPE_STYLUS,
            x = 320f,
            y = 240f,
            pressure = 0.78f,
            tiltX = 18.0f,
            tiltY = -12.0f,
            buttonMask = btnMask,
            timestampMs = 1016.0
        )

        val motionEvent = LinuxStylusBridge.buildMotionEvent(
            action = MotionEvent.ACTION_MOVE,
            downTime = 1000L,
            eventTime = 1016L,
            pointers = listOf(
                LinuxStylusBridge.RawPointerInput(
                    id = 0,
                    x = 320f,
                    y = 240f,
                    pressure = 0.5f,
                    composePointerType = LinuxStylusBridge.PointerKind.STYLUS
                )
            )
        )
        assertEquals(MotionEvent.TOOL_TYPE_STYLUS, motionEvent.getToolType(0))
        assertEquals(0.78f, motionEvent.getPressure(0), 0.001f)
        assertTrue("Tilt radians should be > 0", motionEvent.getAxisValue(MotionEvent.AXIS_TILT, 0) > 0.1f)
        assertTrue(
            "Primary barrel button should map to BUTTON_STYLUS_PRIMARY",
            (motionEvent.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        )

        // 2. Inject simulated Wacom EMR Eraser tail tip
        LinuxStylusBridge.nativeInjectTestEvent(
            toolType = MotionEvent.TOOL_TYPE_ERASER,
            x = 150f,
            y = 150f,
            pressure = 0.62f,
            tiltX = 0f,
            tiltY = 0f,
            buttonMask = MotionEvent.BUTTON_PRIMARY,
            timestampMs = 2000.0
        )
        val eraserEv = LinuxStylusBridge.buildMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            downTime = 2000L,
            eventTime = 2000L,
            pointers = listOf(
                LinuxStylusBridge.RawPointerInput(id = 0, x = 150f, y = 150f)
            )
        )
        assertEquals(MotionEvent.TOOL_TYPE_ERASER, eraserEv.getToolType(0))
        assertEquals(0.62f, eraserEv.getPressure(0), 0.001f)
    }

    @Test
    fun testCliArgumentParsing() {
        val popupCfg = parseLinuxCliArgs(
            arrayOf("--sketch", "/tmp/test-note.svgz", "--node", "file:/tmp/notes.org::id:abc", "--eink")
        )
        assertTrue(popupCfg.isPopupSketchMode)
        assertEquals("/tmp/test-note.svgz", popupCfg.sketchPath)
        assertEquals("file:/tmp/notes.org::id:abc", popupCfg.nodeId)
        assertTrue(popupCfg.einkMode)

        val uriCfg = parseLinuxCliArgs(
            arrayOf("org-writenotes://connect?url=ws%3A%2F%2F192.168.1.10%3A9876&token=secret123")
        )
        assertFalse(uriCfg.isPopupSketchMode)
        assertEquals("ws://192.168.1.10:9876", uriCfg.wsUrl)
        assertEquals("secret123", uriCfg.token)
    }

    @Test
    fun testDesktopOfflineStoreRoundTrip() {
        val store = com.orgwritenotes.sync.OfflineStore(Context.instance, "test_linux_store.db")
        store.putKv("test_key", "hello_linux")
        assertEquals("hello_linux", store.getKv("test_key"))

        val q = listOf("ink.commit" to "{\"fileName\":\"a.svgz\"}", "node.toggleTodo" to "{\"line\":4}")
        store.replaceQueue(q)
        assertEquals(q, store.loadQueue())
    }
}
