import XCTest
@testable import OrgWriteNotesKit

final class OrgWriteNotesIPadTests: XCTestCase {

    func testWriteSessionBridgeStrokeAndSvgzRoundtrip() {
        let session = WriteSessionBridge(width: 800, height: 480, xRuling: 0, yRuling: 40)
        XCTAssertEqual(session.width, 800)
        XCTAssertEqual(session.height, 480)
        XCTAssertEqual(session.strokeCount, 0)

        // Configure Chisel Nib with Apple Pencil Pro roll angle
        session.setPen(
            colorArgb: 0xFF1D4ED8,
            strokeWidth: 3.4,
            pressureSensitive: true,
            tipType: .chisel,
            snapToGrid: false,
            lineDrawing: false,
            chiselAngle: 62.0
        )

        session.beginStroke(PencilSample(x: 100, y: 120, pressure: 0.45, tiltX: 12, tiltY: -8, rollAngleDeg: 62, timestampMs: 0))
        session.moveStrokeBatch([
            PencilSample(x: 140, y: 135, pressure: 0.65, tiltX: 14, tiltY: -9, rollAngleDeg: 63, timestampMs: 4),
            PencilSample(x: 180, y: 150, pressure: 0.80, tiltX: 15, tiltY: -10, rollAngleDeg: 64, timestampMs: 8),
            PencilSample(x: 220, y: 165, pressure: 0.60, tiltX: 13, tiltY: -8, rollAngleDeg: 64, timestampMs: 12)
        ])
        let endInfo = session.endStroke()
        XCTAssertGreaterThan(endInfo.dirtyRect.width, 0)
        XCTAssertEqual(session.strokeCount, 1)
        XCTAssertTrue(session.canUndo)

        // Export .svgz and reload in a second session
        let svgz = session.exportSvgzData()
        XCTAssertGreaterThan(svgz.count, 32)
        XCTAssertEqual(svgz[0], 0x1F)
        XCTAssertEqual(svgz[1], 0x8B)

        let session2 = WriteSessionBridge(width: 400, height: 300)
        XCTAssertTrue(session2.loadSvgzData(svgz))
        XCTAssertEqual(session2.strokeCount, 1)
        XCTAssertTrue(session2.renderFullPage())
    }

    func testApplePencilPhysicsTiltPressureAndRoll() {
        // Perpendicular pencil (altitude = pi/2) -> 0 tilt
        let zeroTilt = ApplePencilPhysics.computeTiltDegrees(altitudeRad: .pi / 2.0, azimuthDx: 1.0, azimuthDy: 0.0)
        XCTAssertEqual(zeroTilt.tiltX, 0.0, accuracy: 0.01)
        XCTAssertEqual(zeroTilt.tiltY, 0.0, accuracy: 0.01)

        // 45-degree altitude along +X -> +45 deg tiltX
        let tilt45 = ApplePencilPhysics.computeTiltDegrees(altitudeRad: .pi / 4.0, azimuthDx: 1.0, azimuthDy: 0.0)
        XCTAssertEqual(tilt45.tiltX, 45.0, accuracy: 0.05)

        // Pressure normalization (Pencil 2nd Gen / Pro vs USB-C fallback)
        XCTAssertEqual(ApplePencilPhysics.normalizePressure(force: 0.0, maxForce: 0.0), 0.55, accuracy: 0.01)
        XCTAssertEqual(ApplePencilPhysics.normalizePressure(force: 3.25, maxForce: 5.0), 1.0, accuracy: 0.01)

        // Apple Pencil Pro barrel roll normalization to [0, 180)
        let rollDeg = ApplePencilPhysics.normalizeRollAngleDegrees(rollRadians: -.pi / 4.0)
        XCTAssertEqual(rollDeg, 135.0, accuracy: 0.05)
    }

    func testApplePencilDoubleTapAndSqueezeStateMachine() {
        let machine = ApplePencilToolStateMachine(initialTool: .pen)
        XCTAssertEqual(machine.currentTool, .pen)

        // Double-tap toggles pen <-> eraser
        XCTAssertEqual(machine.handleShortcut(.togglePenEraser), .eraserStroke)
        XCTAssertEqual(machine.handleShortcut(.togglePenEraser), .pen)

        // Apple Pencil Pro Squeeze toggles radial menu at hover pose
        _ = machine.handleShortcut(.showRadialMenu(at: CGPoint(x: 320, y: 240)))
        XCTAssertTrue(machine.isRadialMenuVisible)
        XCTAssertEqual(machine.radialMenuAnchor, CGPoint(x: 320, y: 240))
    }

    func testConnectionConfigAndOfflineQueue() throws {
        let cfg = ConnectionConfig.parse(urlOrString: "orgwritenotes://connect?host=100.64.1.12&port=9876&token=abc123xyz")
        XCTAssertEqual(cfg?.host, "100.64.1.12")
        XCTAssertEqual(cfg?.port, 9876)
        XCTAssertEqual(cfg?.token, "abc123xyz")

        let tmpDir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("wn-ios-test-\(UUID().uuidString)")
        let store = OfflineSyncStore(customDirectory: tmpDir)
        store.enqueueCommit(PendingInkCommit(
            nodeId: "node-1",
            fileName: "sketch1.svgz",
            svgzBase64: "H4sIAAAAAAAAA==",
            baseEtag: "etag-old",
            width: 1080,
            height: 420
        ))
        // Coalesce second commit to same sketch, preserving original baseEtag
        store.enqueueCommit(PendingInkCommit(
            nodeId: "node-1",
            fileName: "sketch1.svgz",
            svgzBase64: "H4sIBBBBBBBBB==",
            baseEtag: nil,
            width: 1080,
            height: 500
        ))
        XCTAssertEqual(store.pendingCount, 1)
        let flushed = store.dequeueAllCommits()
        XCTAssertEqual(flushed.count, 1)
        XCTAssertEqual(flushed[0].svgzBase64, "H4sIBBBBBBBBB==")
        XCTAssertEqual(flushed[0].baseEtag, "etag-old")
        XCTAssertEqual(store.pendingCount, 0)
    }
}

