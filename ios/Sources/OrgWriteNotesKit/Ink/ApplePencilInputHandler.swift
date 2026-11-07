import Foundation
import CoreGraphics

/// Pure math & state machine for translating Apple Pencil (1st Gen, 2nd Gen, USB-C, and Pencil Pro)
/// events into Stylus Labs Write C++ engine calls.
public struct ApplePencilPhysics {

    /// Converts UIKit `altitudeAngle` (0 = flat, pi/2 = perpendicular) and `azimuthUnitVector`
    /// into `(tiltX, tiltY)` in degrees `[-90, 90]` for the C++ Write engine.
    public static func computeTiltDegrees(
        altitudeRad: CGFloat,
        azimuthDx: CGFloat,
        azimuthDy: CGFloat
    ) -> (tiltX: Float, tiltY: Float) {
        let clampedAlt = max(0.0, min(CGFloat.pi / 2.0, altitudeRad))
        let zenithDeg = Float((CGFloat.pi / 2.0 - clampedAlt) * 180.0 / CGFloat.pi)
        let tx = max(-90.0, min(90.0, zenithDeg * Float(azimuthDx)))
        let ty = max(-90.0, min(90.0, zenithDeg * Float(azimuthDy)))
        return (tx, ty)
    }

    /// Normalizes UIKit `UITouch.force` and `maximumPossibleForce` into `[0.02, 1.0]`.
    /// Handles Apple Pencil USB-C (where `maximumPossibleForce == 0`) with a smooth fallback.
    public static func normalizePressure(force: CGFloat, maxForce: CGFloat) -> Float {
        guard maxForce > 0.01, force > 0.0 else {
            return 0.55 // Apple Pencil USB-C or non-force fallback
        }
        // Apple Pencil reports maxForce ~ 4.16-5.0; comfortable writing is 0..65% of maxForce
        let comfortableMax = max(0.5, maxForce * 0.65)
        let norm = Float(force / comfortableMax)
        return max(0.02, min(1.0, norm))
    }

    /// Converts Apple Pencil Pro `rollAngle` (radians, iPadOS 17.5+) into chisel nib angle in degrees `[0, 180)`.
    public static func normalizeRollAngleDegrees(rollRadians: CGFloat, defaultAngle: Float = 45.0) -> Float {
        let deg = Float(rollRadians * 180.0 / CGFloat.pi).truncatingRemainder(dividingBy: 180.0)
        return deg < 0 ? (deg + 180.0) : deg
    }
}

/// Actions triggered by Apple Pencil 2nd Gen Double-Tap or Apple Pencil Pro Squeeze.
public enum ApplePencilShortcutAction: Equatable, Sendable {
    case togglePenEraser
    case switchPreviousTool
    case showColorPalette
    case showRadialMenu(at: CGPoint?)
}

/// Manages tool switching and state for Apple Pencil double-tap & squeeze gestures.
public final class ApplePencilToolStateMachine {
    public private(set) var currentTool: WriteToolMode = .pen
    public private(set) var previousTool: WriteToolMode = .eraserStroke
    public private(set) var isRadialMenuVisible: Bool = false
    public private(set) var radialMenuAnchor: CGPoint = .zero

    public init(initialTool: WriteToolMode = .pen) {
        self.currentTool = initialTool
    }

    public func selectTool(_ tool: WriteToolMode) {
        if tool != currentTool {
            previousTool = currentTool
            currentTool = tool
        }
        isRadialMenuVisible = false
    }

    @discardableResult
    public func handleShortcut(_ action: ApplePencilShortcutAction) -> WriteToolMode {
        switch action {
        case .togglePenEraser:
            if currentTool == .eraserStroke || currentTool == .eraserRuledLine || currentTool == .eraserFreeSegment {
                selectTool(.pen)
            } else {
                selectTool(.eraserStroke)
            }
        case .switchPreviousTool:
            let target = previousTool
            selectTool(target)
        case .showColorPalette:
            isRadialMenuVisible.toggle()
        case .showRadialMenu(let pt):
            if let pt = pt {
                radialMenuAnchor = pt
            }
            isRadialMenuVisible.toggle()
        }
        return currentTool
    }
}

