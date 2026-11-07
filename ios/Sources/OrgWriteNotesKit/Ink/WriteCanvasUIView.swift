import Foundation
import CoreGraphics

#if canImport(UIKit)
import UIKit
import SwiftUI

/// High-frequency 120Hz ProMotion + 240Hz Apple Pencil canvas view backed by the C++ Write engine.
public final class WriteCanvasUIView: UIView, UIPencilInteractionDelegate, UIGestureRecognizerDelegate {
    public let session: WriteSessionBridge
    public let toolState = ApplePencilToolStateMachine()

    public var stylusOnlyMode: Bool = true
    public var penColorArgb: UInt32 = 0xFF1E293B { didSet { syncPenToNative() } }
    public var penStrokeWidth: Float = 2.8 { didSet { syncPenToNative() } }
    public var penTipType: WritePenTipType = .round { didSet { syncPenToNative() } }
    public var snapToGrid: Bool = false { didSet { syncPenToNative() } }
    public var lineDrawing: Bool = false { didSet { syncPenToNative() } }
    public var chiselAngle: Float = 45.0 { didSet { syncPenToNative() } }
    public var eraserRadius: Float = 16.0

    public var onStrokeCommitted: ((Data) -> Void)?
    public var onLiveStrokePreview: ((Data) -> Void)?
    public var onToolChanged: ((WriteToolMode) -> Void)?
    public var onRadialMenuRequested: ((CGPoint) -> Void)?
    public var onCanvasSizeChanged: ((CGSize) -> Void)?

    // Rendering layers
    private let pageImageLayer = CALayer()
    private let wetInkLayer = CAShapeLayer()
    private let predictedInkLayer = CAShapeLayer()
    private let selectionOverlayLayer = CAShapeLayer()
    private let hoverCursorLayer = CAShapeLayer()

    // Active input tracking
    private var activeTouch: UITouch?
    private var wetInkPoints: [CGPoint] = []
    private var lassoPoints: [CGPoint] = []
    private var gestureStartPoint: CGPoint = .zero
    private var lastTouchPoint: CGPoint = .zero
    private var shapeHoldTimer: Timer?
    private var hasSnappedCurrentStroke: Bool = false
    private var lastLivePreviewTime: TimeInterval = 0

    // Pan & Zoom state
    public private(set) var zoomScale: CGFloat = 1.0
    public private(set) var panOffset: CGPoint = .zero

    public init(session: WriteSessionBridge) {
        self.session = session
        super.init(frame: CGRect(x: 0, y: 0, width: session.width, height: session.height))
        setupLayers()
        setupInteractionsAndGestures()
        syncPenToNative()
        refreshFullCanvas()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setupLayers() {
        isMultipleTouchEnabled = true
        backgroundColor = UIColor(red: 0.98, green: 0.98, blue: 0.97, alpha: 1.0)
        layer.masksToBounds = true

        pageImageLayer.contentsGravity = .resize
        pageImageLayer.magnificationFilter = .trilinear
        layer.addSublayer(pageImageLayer)

        wetInkLayer.fillColor = nil
        wetInkLayer.lineCap = .round
        wetInkLayer.lineJoin = .round
        layer.addSublayer(wetInkLayer)

        predictedInkLayer.fillColor = nil
        predictedInkLayer.lineCap = .round
        predictedInkLayer.lineJoin = .round
        predictedInkLayer.opacity = 0.65
        layer.addSublayer(predictedInkLayer)

        selectionOverlayLayer.fillColor = UIColor.systemBlue.withAlphaComponent(0.08).cgColor
        selectionOverlayLayer.strokeColor = UIColor.systemBlue.cgColor
        selectionOverlayLayer.lineWidth = 1.5
        selectionOverlayLayer.lineDashPattern = [6, 4]
        layer.addSublayer(selectionOverlayLayer)

        hoverCursorLayer.fillColor = nil
        hoverCursorLayer.strokeColor = UIColor.systemIndigo.withAlphaComponent(0.75).cgColor
        hoverCursorLayer.lineWidth = 1.5
        hoverCursorLayer.isHidden = true
        layer.addSublayer(hoverCursorLayer)
    }

    private func setupInteractionsAndGestures() {
        // Apple Pencil 2nd Gen Double-Tap & Apple Pencil Pro Squeeze
        let pencilInteraction = UIPencilInteraction()
        pencilInteraction.delegate = self
        addInteraction(pencilInteraction)

        // Apple Pencil Hover (M2 / M4 iPad Pro & iPad Air)
        let hoverGesture = UIHoverGestureRecognizer(target: self, action: #selector(handleHover(_:)))
        addGestureRecognizer(hoverGesture)

        // Finger pan (1 or 2 fingers when stylusOnlyMode is active)
        let panGesture = UIPanGestureRecognizer(target: self, action: #selector(handlePanGesture(_:)))
        panGesture.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
        panGesture.minimumNumberOfTouches = 1
        panGesture.maximumNumberOfTouches = 2
        panGesture.delegate = self
        addGestureRecognizer(panGesture)

        // Two-finger pinch zoom
        let pinchGesture = UIPinchGestureRecognizer(target: self, action: #selector(handlePinchGesture(_:)))
        pinchGesture.delegate = self
        addGestureRecognizer(pinchGesture)

        // Two-finger tap -> Undo
        let twoFingerTap = UITapGestureRecognizer(target: self, action: #selector(handleTwoFingerUndo(_:)))
        twoFingerTap.numberOfTouchesRequired = 2
        twoFingerTap.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
        addGestureRecognizer(twoFingerTap)

        // Three-finger tap -> Redo
        let threeFingerTap = UITapGestureRecognizer(target: self, action: #selector(handleThreeFingerRedo(_:)))
        threeFingerTap.numberOfTouchesRequired = 3
        threeFingerTap.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
        addGestureRecognizer(threeFingerTap)
    }

    public func syncPenToNative() {
        session.setPen(
            colorArgb: penColorArgb,
            strokeWidth: penStrokeWidth,
            pressureSensitive: true,
            tipType: penTipType,
            snapToGrid: snapToGrid,
            lineDrawing: lineDrawing,
            chiselAngle: chiselAngle
        )
        wetInkLayer.strokeColor = Self.uiColor(fromArgb: penColorArgb).cgColor
        wetInkLayer.lineWidth = CGFloat(penStrokeWidth)
        predictedInkLayer.strokeColor = Self.uiColor(fromArgb: penColorArgb).cgColor
        predictedInkLayer.lineWidth = CGFloat(penStrokeWidth)
    }

    public func refreshFullCanvas() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        if let cgImg = session.makeCGImage(renderFirst: true) {
            pageImageLayer.frame = CGRect(
                x: panOffset.x,
                y: panOffset.y,
                width: CGFloat(session.width) * zoomScale,
                height: CGFloat(session.height) * zoomScale
            )
            pageImageLayer.contents = cgImg
        }
        CATransaction.commit()
    }

    // MARK: - Coordinate Conversion

    private func viewToPagePoint(_ pt: CGPoint) -> CGPoint {
        return CGPoint(
            x: (pt.x - panOffset.x) / max(0.1, zoomScale),
            y: (pt.y - panOffset.y) / max(0.1, zoomScale)
        )
    }

    private func sample(from touch: UITouch) -> PencilSample {
        let loc = viewToPagePoint(touch.location(in: self))
        let pressure: Float
        let tiltX: Float
        let tiltY: Float
        var rollDeg: Float = chiselAngle

        if touch.type == .pencil || touch.type == .stylus {
            pressure = ApplePencilPhysics.normalizePressure(
                force: touch.force,
                maxForce: touch.maximumPossibleForce
            )
            let az = touch.azimuthUnitVector(in: self)
            let tilt = ApplePencilPhysics.computeTiltDegrees(
                altitudeRad: touch.altitudeAngle,
                azimuthDx: az.dx,
                azimuthDy: az.dy
            )
            tiltX = tilt.tiltX
            tiltY = tilt.tiltY
            if #available(iOS 17.5, *) {
                rollDeg = ApplePencilPhysics.normalizeRollAngleDegrees(rollRadians: touch.rollAngle)
                if penTipType == .chisel && abs(rollDeg - chiselAngle) > 1.5 {
                    chiselAngle = rollDeg
                }
            }
        } else {
            pressure = 0.55
            tiltX = 0
            tiltY = 0
        }

        return PencilSample(
            x: Float(loc.x),
            y: Float(loc.y),
            pressure: pressure,
            tiltX: tiltX,
            tiltY: tiltY,
            rollAngleDeg: rollDeg,
            timestampMs: touch.timestamp * 1000.0
        )
    }

    // MARK: - Touch Handling (240Hz Coalesced + Predicted Wet Ink)

    override public func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = selectDrawingTouch(from: touches) else { return }
        activeTouch = touch
        hoverCursorLayer.isHidden = true
        hasSnappedCurrentStroke = false

        let pagePt = viewToPagePoint(touch.location(in: self))
        gestureStartPoint = pagePt
        lastTouchPoint = pagePt
        wetInkPoints = [touch.location(in: self)]

        switch toolState.currentTool {
        case .pen:
            let s = sample(from: touch)
            session.beginStroke(s)
            scheduleShapeHoldTimer(at: pagePt)
        case .eraserStroke:
            _ = session.eraseSegment(from: pagePt, to: pagePt, radius: eraserRadius)
            refreshFullCanvas()
        case .eraserRuledLine:
            _ = session.eraseRuledLine(atY: Float(pagePt.y))
            refreshFullCanvas()
        case .eraserFreeSegment:
            _ = session.eraseFreeSegment(from: pagePt, to: pagePt, radius: eraserRadius)
            refreshFullCanvas()
        case .selectLasso:
            lassoPoints = [pagePt]
        default:
            break
        }
    }

    override public func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let active = activeTouch, touches.contains(active) else { return }
        let pagePt = viewToPagePoint(active.location(in: self))

        switch toolState.currentTool {
        case .pen:
            let coalesced = event?.coalescedTouches(for: active) ?? [active]
            let samples = coalesced.map { sample(from: $0) }
            session.moveStrokeBatch(samples)

            for c in coalesced {
                wetInkPoints.append(c.location(in: self))
            }
            let predicted = (event?.predictedTouches(for: active) ?? []).map { $0.location(in: self) }
            updateWetAndPredictedPaths(predicted: predicted)

            if hypot(pagePt.x - lastTouchPoint.x, pagePt.y - lastTouchPoint.y) > 4.5 {
                lastTouchPoint = pagePt
                scheduleShapeHoldTimer(at: pagePt)
            }

            let now = CACurrentMediaTime()
            if now - lastLivePreviewTime >= 0.14 {
                lastLivePreviewTime = now
                if let previewCb = onLiveStrokePreview {
                    previewCb(session.exportSvgzData())
                }
            }

        case .eraserStroke:
            if session.eraseSegment(from: lastTouchPoint, to: pagePt, radius: eraserRadius) != nil {
                refreshFullCanvas()
            }
            lastTouchPoint = pagePt

        case .eraserRuledLine:
            if session.eraseRuledLine(atY: Float(pagePt.y)) != nil {
                refreshFullCanvas()
            }
            lastTouchPoint = pagePt

        case .eraserFreeSegment:
            if session.eraseFreeSegment(from: lastTouchPoint, to: pagePt, radius: eraserRadius) != nil {
                refreshFullCanvas()
            }
            lastTouchPoint = pagePt

        case .selectRect:
            let rect = CGRect(
                x: min(gestureStartPoint.x, pagePt.x),
                y: min(gestureStartPoint.y, pagePt.y),
                width: abs(pagePt.x - gestureStartPoint.x),
                height: abs(pagePt.y - gestureStartPoint.y)
            )
            selectionOverlayLayer.path = UIBezierPath(rect: rect).cgPath

        case .selectLasso:
            lassoPoints.append(pagePt)
            let path = UIBezierPath()
            if let first = lassoPoints.first {
                path.move(to: first)
                for p in lassoPoints.dropFirst() { path.addLine(to: p) }
            }
            selectionOverlayLayer.path = path.cgPath

        default:
            break
        }
    }

    override public func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let active = activeTouch, touches.contains(active) else { return }
        shapeHoldTimer?.invalidate()
        shapeHoldTimer = nil
        let pagePt = viewToPagePoint(active.location(in: self))

        switch toolState.currentTool {
        case .pen:
            let s = sample(from: active)
            session.moveStroke(s)
            let result = session.endStroke()
            clearWetInkLayers()
            refreshFullCanvas()
            onCanvasSizeChanged?(CGSize(width: result.width, height: result.height))
            onStrokeCommitted?(session.exportSvgzData())

        case .eraserStroke, .eraserRuledLine, .eraserFreeSegment:
            onStrokeCommitted?(session.exportSvgzData())

        case .selectRect:
            let rect = CGRect(
                x: min(gestureStartPoint.x, pagePt.x),
                y: min(gestureStartPoint.y, pagePt.y),
                width: abs(pagePt.x - gestureStartPoint.x),
                height: abs(pagePt.y - gestureStartPoint.y)
            )
            let sel = session.selectRect(rect)
            selectionOverlayLayer.path = sel.count > 0 ? UIBezierPath(rect: sel.bounds).cgPath : nil

        case .selectLasso:
            let sel = session.selectLasso(lassoPoints)
            selectionOverlayLayer.path = sel.count > 0 ? UIBezierPath(rect: sel.bounds).cgPath : nil

        case .selectRuled:
            let sel = session.selectRuled(startY: Float(gestureStartPoint.y), endY: Float(pagePt.y))
            selectionOverlayLayer.path = sel.count > 0 ? UIBezierPath(rect: sel.bounds).cgPath : nil

        case .insertSpaceVertical:
            let deltaY = Float(pagePt.y - gestureStartPoint.y)
            if session.insertVerticalSpace(yThreshold: Float(gestureStartPoint.y), deltaY: deltaY) {
                refreshFullCanvas()
                onStrokeCommitted?(session.exportSvgzData())
            }

        case .insertSpaceHorizontal:
            let deltaX = Float(pagePt.x - gestureStartPoint.x)
            if session.insertHorizontalSpace(xThreshold: Float(gestureStartPoint.x), deltaX: deltaX) {
                refreshFullCanvas()
                onStrokeCommitted?(session.exportSvgzData())
            }

        case .insertSpaceRuled:
            if session.insertRuledSpace(start: gestureStartPoint, end: pagePt, enableReflow: true) {
                refreshFullCanvas()
                onStrokeCommitted?(session.exportSvgzData())
            }

        case .pan:
            break
        }

        activeTouch = nil
    }

    override public func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let active = activeTouch, touches.contains(active) else { return }
        shapeHoldTimer?.invalidate()
        shapeHoldTimer = nil
        if toolState.currentTool == .pen {
            _ = session.endStroke()
        }
        clearWetInkLayers()
        refreshFullCanvas()
        activeTouch = nil
    }

    private func selectDrawingTouch(from touches: Set<UITouch>) -> UITouch? {
        if let pencil = touches.first(where: { $0.type == .pencil || $0.type == .stylus }) {
            return pencil
        }
        if !stylusOnlyMode {
            return touches.first
        }
        return nil
    }

    private func scheduleShapeHoldTimer(at point: CGPoint) {
        shapeHoldTimer?.invalidate()
        guard !hasSnappedCurrentStroke else { return }
        shapeHoldTimer = Timer.scheduledTimer(withTimeInterval: 0.42, repeats: false) { [weak self] _ in
            guard let self = self, self.activeTouch != nil else { return }
            let snapped = self.session.snapActiveStrokeToShape()
            if snapped.shapeType > 0, !snapped.points.isEmpty {
                self.hasSnappedCurrentStroke = true
                let feedback = UIImpactFeedbackGenerator(style: .medium)
                feedback.impactOccurred()
                self.wetInkPoints = snapped.points.map {
                    CGPoint(
                        x: $0.x * self.zoomScale + self.panOffset.x,
                        y: $0.y * self.zoomScale + self.panOffset.y
                    )
                }
                self.updateWetAndPredictedPaths(predicted: [])
            }
        }
    }

    private func updateWetAndPredictedPaths(predicted: [CGPoint]) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)

        let wetPath = UIBezierPath()
        if let first = wetInkPoints.first {
            wetPath.move(to: first)
            for p in wetInkPoints.dropFirst() {
                wetPath.addLine(to: p)
            }
        }
        wetInkLayer.path = wetPath.cgPath

        if !predicted.isEmpty, let lastWet = wetInkPoints.last {
            let predPath = UIBezierPath()
            predPath.move(to: lastWet)
            for p in predicted {
                predPath.addLine(to: p)
            }
            predictedInkLayer.path = predPath.cgPath
        } else {
            predictedInkLayer.path = nil
        }

        CATransaction.commit()
    }

    private func clearWetInkLayers() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        wetInkLayer.path = nil
        predictedInkLayer.path = nil
        wetInkPoints.removeAll(keepingCapacity: true)
        CATransaction.commit()
    }

    // MARK: - Apple Pencil Hover, Double-Tap & Pencil Pro Squeeze

    @objc private func handleHover(_ recognizer: UIHoverGestureRecognizer) {
        switch recognizer.state {
        case .began, .changed:
            let loc = recognizer.location(in: self)
            var radius = CGFloat(max(4.0, penStrokeWidth * 1.5))
            if #available(iOS 16.1, *) {
                let z = recognizer.zOffset // 0.0 (touching) .. 1.0 (far hover)
                radius *= (1.0 + z * 1.4)
            }
            let rect = CGRect(x: loc.x - radius, y: loc.y - radius, width: radius * 2, height: radius * 2)
            hoverCursorLayer.path = UIBezierPath(ovalIn: rect).cgPath
            hoverCursorLayer.isHidden = false
        default:
            hoverCursorLayer.isHidden = true
        }
    }

    public func pencilInteractionDidTap(_ interaction: UIPencilInteraction) {
        let action: ApplePencilShortcutAction
        switch UIPencilInteraction.preferredTapAction {
        case .switchEraser:
            action = .togglePenEraser
        case .switchPrevious:
            action = .switchPreviousTool
        case .showColorPalette, .showInkAttributes:
            action = .showColorPalette
        default:
            action = .togglePenEraser
        }
        let newTool = toolState.handleShortcut(action)
        onToolChanged?(newTool)
    }

    @available(iOS 17.5, *)
    public func pencilInteraction(_ interaction: UIPencilInteraction, didReceiveSqueeze squeeze: UIPencilInteraction.Squeeze) {
        guard squeeze.phase == .ended else { return }
        let anchor = squeeze.hoverPose?.location ?? CGPoint(x: bounds.midX, y: bounds.midY)
        _ = toolState.handleShortcut(.showRadialMenu(at: anchor))
        let generator = UIImpactFeedbackGenerator(style: .light)
        generator.impactOccurred()
        onRadialMenuRequested?(anchor)
    }

    // MARK: - Multi-Touch Finger Gestures (Pan, Pinch-Zoom, 2-Finger Undo, 3-Finger Redo)

    @objc private func handlePanGesture(_ recognizer: UIPanGestureRecognizer) {
        guard activeTouch == nil else { return }
        let delta = recognizer.translation(in: self)
        recognizer.setTranslation(.zero, in: self)
        panOffset = CGPoint(x: panOffset.x + delta.x, y: panOffset.y + delta.y)
        refreshFullCanvas()
    }

    @objc private func handlePinchGesture(_ recognizer: UIPinchGestureRecognizer) {
        guard activeTouch == nil else { return }
        let nextZoom = max(0.4, min(4.0, zoomScale * recognizer.scale))
        zoomScale = nextZoom
        recognizer.scale = 1.0
        refreshFullCanvas()
    }

    @objc private func handleTwoFingerUndo(_ recognizer: UITapGestureRecognizer) {
        if session.undo() {
            refreshFullCanvas()
            onStrokeCommitted?(session.exportSvgzData())
        }
    }

    @objc private func handleThreeFingerRedo(_ recognizer: UITapGestureRecognizer) {
        if session.redo() {
            refreshFullCanvas()
            onStrokeCommitted?(session.exportSvgzData())
        }
    }

    private static func uiColor(fromArgb argb: UInt32) -> UIColor {
        let a = CGFloat((argb >> 24) & 0xFF) / 255.0
        let r = CGFloat((argb >> 16) & 0xFF) / 255.0
        let g = CGFloat((argb >> 8) & 0xFF) / 255.0
        let b = CGFloat(argb & 0xFF) / 255.0
        return UIColor(red: r, green: g, blue: b, alpha: a)
    }
}

/// SwiftUI wrapper around `WriteCanvasUIView` for inline and full-screen zoomed ink blocks on iPadOS.
public struct WriteCanvasSwiftUIView: UIViewRepresentable {
    public let session: WriteSessionBridge
    @Binding public var toolMode: WriteToolMode
    @Binding public var penColorArgb: UInt32
    @Binding public var penWidth: Float
    @Binding public var penTipType: WritePenTipType
    @Binding public var stylusOnlyMode: Bool
    public var onCommit: (Data) -> Void
    public var onLivePreview: ((Data) -> Void)?
    public var onRadialMenu: ((CGPoint) -> Void)?

    public init(
        session: WriteSessionBridge,
        toolMode: Binding<WriteToolMode>,
        penColorArgb: Binding<UInt32>,
        penWidth: Binding<Float>,
        penTipType: Binding<WritePenTipType> = .constant(.round),
        stylusOnlyMode: Binding<Bool> = .constant(true),
        onCommit: @escaping (Data) -> Void,
        onLivePreview: ((Data) -> Void)? = nil,
        onRadialMenu: ((CGPoint) -> Void)? = nil
    ) {
        self.session = session
        self._toolMode = toolMode
        self._penColorArgb = penColorArgb
        self._penWidth = penWidth
        self._penTipType = penTipType
        self._stylusOnlyMode = stylusOnlyMode
        self.onCommit = onCommit
        self.onLivePreview = onLivePreview
        self.onRadialMenu = onRadialMenu
    }

    public func makeUIView(context: Context) -> WriteCanvasUIView {
        let view = WriteCanvasUIView(session: session)
        view.toolState.selectTool(toolMode)
        view.penColorArgb = penColorArgb
        view.penStrokeWidth = penWidth
        view.penTipType = penTipType
        view.stylusOnlyMode = stylusOnlyMode
        view.onStrokeCommitted = onCommit
        view.onLiveStrokePreview = onLivePreview
        view.onRadialMenuRequested = onRadialMenu
        view.onToolChanged = { newTool in
            DispatchQueue.main.async {
                self.toolMode = newTool
            }
        }
        return view
    }

    public func updateUIView(_ uiView: WriteCanvasUIView, context: Context) {
        if uiView.toolState.currentTool != toolMode {
            uiView.toolState.selectTool(toolMode)
        }
        if uiView.penColorArgb != penColorArgb {
            uiView.penColorArgb = penColorArgb
        }
        if uiView.penStrokeWidth != penWidth {
            uiView.penStrokeWidth = penWidth
        }
        if uiView.penTipType != penTipType {
            uiView.penTipType = penTipType
        }
        uiView.stylusOnlyMode = stylusOnlyMode
    }
}
#endif

