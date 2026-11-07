import Foundation
import CoreGraphics
import WriteEngineC

/// Ruling styles matching `orgwritenotes::TemplateRulingStyle` in C++.
public enum WriteRulingStyle: Int32, CaseIterable, Sendable {
    case standard       = 0
    case dotted         = 1
    case engineerGrid   = 2
    case isometric      = 3
    case musicStaff     = 4
    case cornellSummary = 5
    case customDesigner = 6
    case weeklyPlanner  = 7

    public var label: String {
        switch self {
        case .standard:       return "Ruled / Grid"
        case .dotted:         return "Dot Grid"
        case .engineerGrid:   return "Engineer Grid"
        case .isometric:      return "Isometric"
        case .musicStaff:     return "Music Staff"
        case .cornellSummary: return "Cornell Notes"
        case .customDesigner: return "Custom Template"
        case .weeklyPlanner:  return "Weekly Planner"
        }
    }
}

/// Pen tip styles supported by Stylus Labs Write engine.
public enum WritePenTipType: Int32, CaseIterable, Sendable {
    case flat   = 0
    case round  = 1
    case chisel = 2 // Supports Apple Pencil Pro rollAngle -> chiselAngle
}

/// Active stylus/touch tool on iPadOS.
public enum WriteToolMode: Equatable, Sendable {
    case pen
    case eraserStroke
    case eraserRuledLine
    case eraserFreeSegment
    case selectRect
    case selectLasso
    case selectRuled
    case insertSpaceVertical
    case insertSpaceHorizontal
    case insertSpaceRuled
    case pan
}

/// A single 240Hz sample from Apple Pencil (including coalesced/predicted samples).
public struct PencilSample: Sendable, Equatable {
    public var x: Float
    public var y: Float
    public var pressure: Float
    public var tiltX: Float
    public var tiltY: Float
    public var rollAngleDeg: Float
    public var timestampMs: Double

    public init(
        x: Float,
        y: Float,
        pressure: Float,
        tiltX: Float = 0,
        tiltY: Float = 0,
        rollAngleDeg: Float = 45,
        timestampMs: Double
    ) {
        self.x = x
        self.y = y
        self.pressure = pressure
        self.tiltX = tiltX
        self.tiltY = tiltY
        self.rollAngleDeg = rollAngleDeg
        self.timestampMs = timestampMs
    }
}

/// Page setup metadata returned by `wn_session_get_page_info`.
public struct WritePageInfo: Equatable, Sendable {
    public var width: Int32
    public var height: Int32
    public var xRuling: Int32
    public var yRuling: Int32
    public var marginLeft: Int32
    public var paperColorArgb: Int32
    public var ruleColorArgb: Int32
    public var rulingStyle: WriteRulingStyle
}

/// Thread-safe Swift wrapper around the C++ `NativeCanvasSession` (`wn_session_*` C ABI).
public final class WriteSessionBridge: @unchecked Sendable {
    public private(set) var handle: Int64
    private let lock = NSRecursiveLock()
    private var rgbaBuffer: [UInt8]
    public private(set) var width: Int
    public private(set) var height: Int

    public init(width: Int = 1080, height: Int = 540, xRuling: Int = 0, yRuling: Int = 40) {
        let w = max(64, width)
        let h = max(64, height)
        self.width = w
        self.height = h
        self.rgbaBuffer = [UInt8](repeating: 0, count: w * h * 4)
        self.handle = wn_session_create(Int32(w), Int32(h), Int32(xRuling), Int32(yRuling))
    }

    deinit {
        if handle != 0 {
            wn_session_destroy(handle)
            handle = 0
        }
    }

    private func ensureRgbaBufferCapacity(width w: Int, height h: Int) {
        let needed = max(1, w * h * 4)
        if rgbaBuffer.count != needed {
            rgbaBuffer = [UInt8](repeating: 0, count: needed)
        }
        self.width = w
        self.height = h
    }

    private func syncDimensionsFromNative() {
        var info = [Int32](repeating: 0, count: 8)
        if wn_session_get_page_info(handle, &info) {
            let nw = max(64, Int(info[0]))
            let nh = max(64, Int(info[1]))
            if nw != width || nh != height {
                ensureRgbaBufferCapacity(width: nw, height: nh)
            }
        }
    }

    // MARK: - SVG / SVGZ Serialization

    @discardableResult
    public func loadSvgzData(_ data: Data) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !data.isEmpty else { return false }
        let ok = data.withUnsafeBytes { rawPtr -> Bool in
            guard let base = rawPtr.baseAddress?.assumingMemoryBound(to: UInt8.self) else {
                return false
            }
            return wn_session_load_svgz(handle, base, Int32(data.count))
        }
        if ok {
            syncDimensionsFromNative()
        }
        return ok
    }

    public func exportSvgzData() -> Data {
        lock.lock()
        defer { lock.unlock() }
        let needed = Int(wn_session_export_svgz(handle, nil, 0))
        guard needed > 0 else { return Data() }
        var out = Data(count: needed)
        let written = out.withUnsafeMutableBytes { rawPtr -> Int32 in
            guard let base = rawPtr.baseAddress?.assumingMemoryBound(to: UInt8.self) else {
                return 0
            }
            return wn_session_export_svgz(handle, base, Int32(needed))
        }
        guard written > 0 else { return Data() }
        return out.prefix(Int(written))
    }

    // MARK: - Pen & Stroke Lifecycle (240Hz Coalesced & Roll Angle Support)

    public func setPen(
        colorArgb: UInt32,
        strokeWidth: Float,
        pressureSensitive: Bool = true,
        tipType: WritePenTipType = .round,
        snapToGrid: Bool = false,
        lineDrawing: Bool = false,
        chiselAngle: Float = 45.0
    ) {
        lock.lock()
        defer { lock.unlock() }
        wn_session_set_pen(
            handle,
            Int32(bitPattern: colorArgb),
            strokeWidth,
            pressureSensitive,
            tipType.rawValue,
            snapToGrid,
            lineDrawing,
            chiselAngle
        )
    }

    public func beginStroke(_ sample: PencilSample) {
        lock.lock()
        defer { lock.unlock() }
        wn_session_begin_stroke(
            handle,
            sample.x,
            sample.y,
            sample.pressure,
            sample.tiltX,
            sample.tiltY,
            sample.timestampMs
        )
    }

    public func moveStroke(_ sample: PencilSample) {
        lock.lock()
        defer { lock.unlock() }
        wn_session_move_stroke(
            handle,
            sample.x,
            sample.y,
            sample.pressure,
            sample.tiltX,
            sample.tiltY,
            sample.timestampMs
        )
    }

    /// Processes a batch of 240Hz coalesced Apple Pencil samples in a single C ABI call.
    public func moveStrokeBatch(_ samples: [PencilSample]) {
        guard !samples.isEmpty else { return }
        lock.lock()
        defer { lock.unlock() }
        var flat = [Float]()
        flat.reserveCapacity(samples.count * 6)
        for s in samples {
            flat.append(s.x)
            flat.append(s.y)
            flat.append(s.pressure)
            flat.append(s.tiltX)
            flat.append(s.tiltY)
            flat.append(Float(s.timestampMs))
        }
        flat.withUnsafeBufferPointer { ptr in
            wn_session_move_stroke_batch(handle, ptr.baseAddress, Int32(samples.count))
        }
    }

    /// Snaps the currently held stroke to a geometric shape (line, rectangle, circle, triangle, arrow).
    public func snapActiveStrokeToShape() -> (shapeType: Int, points: [CGPoint]) {
        lock.lock()
        defer { lock.unlock() }
        let maxPts = 128
        var floats = [Float](repeating: 0, count: maxPts * 2)
        var numPts: Int32 = 0
        let shapeType = Int(wn_session_snap_active_stroke_to_shape(handle, &floats, Int32(floats.count), &numPts))
        guard shapeType > 0, numPts > 0 else { return (0, []) }
        var pts: [CGPoint] = []
        pts.reserveCapacity(Int(numPts))
        for i in 0..<Int(numPts) {
            pts.append(CGPoint(x: CGFloat(floats[i * 2]), y: CGFloat(floats[i * 2 + 1])))
        }
        return (shapeType, pts)
    }

    /// Ends the active stroke and returns `(newWidth, newHeight, dirtyRect)`.
    @discardableResult
    public func endStroke() -> (width: Int, height: Int, dirtyRect: CGRect) {
        lock.lock()
        defer { lock.unlock() }
        var out6 = [Int32](repeating: 0, count: 6)
        wn_session_end_stroke(handle, &out6)
        let nw = max(64, Int(out6[0]))
        let nh = max(64, Int(out6[1]))
        if nw != width || nh != height {
            ensureRgbaBufferCapacity(width: nw, height: nh)
        }
        let dirty = CGRect(
            x: CGFloat(out6[2]),
            y: CGFloat(out6[3]),
            width: CGFloat(max(0, out6[4] - out6[2])),
            height: CGFloat(max(0, out6[5] - out6[3]))
        )
        return (nw, nh, dirty)
    }

    // MARK: - Erasers

    @discardableResult
    public func eraseSegment(from prev: CGPoint, to current: CGPoint, radius: Float = 14.0) -> CGRect? {
        lock.lock()
        defer { lock.unlock() }
        var dirty = [Float](repeating: 0, count: 4)
        let erased = wn_session_erase_segment(
            handle,
            Float(prev.x),
            Float(prev.y),
            Float(current.x),
            Float(current.y),
            radius,
            &dirty
        )
        guard erased else { return nil }
        return CGRect(
            x: CGFloat(dirty[0]),
            y: CGFloat(dirty[1]),
            width: CGFloat(max(0, dirty[2] - dirty[0])),
            height: CGFloat(max(0, dirty[3] - dirty[1]))
        )
    }

    @discardableResult
    public func eraseRuledLine(atY y: Float) -> CGRect? {
        lock.lock()
        defer { lock.unlock() }
        var dirty = [Float](repeating: 0, count: 4)
        guard wn_session_erase_ruled_line_at(handle, y, &dirty) else { return nil }
        return CGRect(
            x: CGFloat(dirty[0]),
            y: CGFloat(dirty[1]),
            width: CGFloat(max(0, dirty[2] - dirty[0])),
            height: CGFloat(max(0, dirty[3] - dirty[1]))
        )
    }

    @discardableResult
    public func eraseFreeSegment(from prev: CGPoint, to current: CGPoint, radius: Float = 14.0) -> CGRect? {
        lock.lock()
        defer { lock.unlock() }
        var dirty = [Float](repeating: 0, count: 4)
        let erased = wn_session_erase_free_segment(
            handle,
            Float(prev.x),
            Float(prev.y),
            Float(current.x),
            Float(current.y),
            radius,
            &dirty
        )
        guard erased else { return nil }
        return CGRect(
            x: CGFloat(dirty[0]),
            y: CGFloat(dirty[1]),
            width: CGFloat(max(0, dirty[2] - dirty[0])),
            height: CGFloat(max(0, dirty[3] - dirty[1]))
        )
    }

    // MARK: - Selection & Transforms

    public func selectRect(_ rect: CGRect) -> (count: Int, bounds: CGRect) {
        lock.lock()
        defer { lock.unlock() }
        var b = [Float](repeating: 0, count: 4)
        let count = Int(wn_session_select_rect(
            handle,
            Float(rect.minX),
            Float(rect.minY),
            Float(rect.maxX),
            Float(rect.maxY),
            &b
        ))
        return (count, Self.boundsToRect(b))
    }

    public func selectLasso(_ points: [CGPoint]) -> (count: Int, bounds: CGRect) {
        lock.lock()
        defer { lock.unlock() }
        guard points.count >= 3 else { return (0, .zero) }
        var flat = [Float]()
        flat.reserveCapacity(points.count * 2)
        for p in points {
            flat.append(Float(p.x))
            flat.append(Float(p.y))
        }
        var b = [Float](repeating: 0, count: 4)
        let count = flat.withUnsafeBufferPointer { ptr -> Int in
            Int(wn_session_select_lasso(handle, ptr.baseAddress, Int32(flat.count), &b))
        }
        return (count, Self.boundsToRect(b))
    }

    public func selectRuled(startY: Float, endY: Float) -> (count: Int, bounds: CGRect) {
        lock.lock()
        defer { lock.unlock() }
        var b = [Float](repeating: 0, count: 4)
        let count = Int(wn_session_select_ruled(handle, startY, endY, &b))
        return (count, Self.boundsToRect(b))
    }

    public func selectAll() -> (count: Int, bounds: CGRect) {
        lock.lock()
        defer { lock.unlock() }
        var b = [Float](repeating: 0, count: 4)
        let count = Int(wn_session_select_all(handle, &b))
        return (count, Self.boundsToRect(b))
    }

    public func clearSelection() {
        lock.lock()
        defer { lock.unlock() }
        wn_session_clear_selection(handle)
    }

    public var hasSelection: Bool {
        lock.lock()
        defer { lock.unlock() }
        return wn_session_has_selection(handle)
    }

    public func translateSelection(dx: Float, dy: Float) -> CGRect? {
        lock.lock()
        defer { lock.unlock() }
        var b = [Float](repeating: 0, count: 4)
        var dims = [Int32](repeating: 0, count: 2)
        guard wn_session_translate_selection(handle, dx, dy, &b, &dims) else { return nil }
        ensureRgbaBufferCapacity(width: Int(dims[0]), height: Int(dims[1]))
        return Self.boundsToRect(b)
    }

    public func scaleSelection(sx: Float, sy: Float, pivot: CGPoint, scaleWidths: Bool = true) -> CGRect? {
        lock.lock()
        defer { lock.unlock() }
        var b = [Float](repeating: 0, count: 4)
        var dims = [Int32](repeating: 0, count: 2)
        guard wn_session_scale_selection(handle, sx, sy, Float(pivot.x), Float(pivot.y), scaleWidths, &b, &dims) else {
            return nil
        }
        ensureRgbaBufferCapacity(width: Int(dims[0]), height: Int(dims[1]))
        return Self.boundsToRect(b)
    }

    public func rotateSelection(angleRad: Float, pivot: CGPoint) -> CGRect? {
        lock.lock()
        defer { lock.unlock() }
        var b = [Float](repeating: 0, count: 4)
        var dims = [Int32](repeating: 0, count: 2)
        guard wn_session_rotate_selection(handle, angleRad, Float(pivot.x), Float(pivot.y), &b, &dims) else {
            return nil
        }
        ensureRgbaBufferCapacity(width: Int(dims[0]), height: Int(dims[1]))
        return Self.boundsToRect(b)
    }

    @discardableResult
    public func deleteSelection() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return wn_session_delete_selection(handle)
    }

    @discardableResult
    public func duplicateSelection(offsetX: Float = 24, offsetY: Float = 24) -> CGRect? {
        lock.lock()
        defer { lock.unlock() }
        var b = [Float](repeating: 0, count: 4)
        var dims = [Int32](repeating: 0, count: 2)
        let count = wn_session_duplicate_selection(handle, offsetX, offsetY, &b, &dims)
        guard count > 0 else { return nil }
        ensureRgbaBufferCapacity(width: Int(dims[0]), height: Int(dims[1]))
        return Self.boundsToRect(b)
    }

    @discardableResult
    public func restyleSelection(colorArgb: UInt32, strokeWidth: Float) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return wn_session_restyle_selection(handle, Int32(bitPattern: colorArgb), strokeWidth)
    }

    public func copySelectionSvg() -> String {
        lock.lock()
        defer { lock.unlock() }
        let len = Int(wn_session_copy_selection_svg(handle, nil, 0))
        guard len > 0 else { return "" }
        var buf = [CChar](repeating: 0, count: len + 1)
        _ = wn_session_copy_selection_svg(handle, &buf, Int32(buf.count))
        return String(cString: buf)
    }

    @discardableResult
    public func pasteSelectionSvg(_ svg: String, offsetX: Float = 24, offsetY: Float = 24) -> CGRect? {
        lock.lock()
        defer { lock.unlock() }
        var b = [Float](repeating: 0, count: 4)
        var dims = [Int32](repeating: 0, count: 2)
        let count = svg.withCString { cStr in
            wn_session_paste_selection_svg(handle, cStr, offsetX, offsetY, &b, &dims)
        }
        guard count > 0 else { return nil }
        ensureRgbaBufferCapacity(width: Int(dims[0]), height: Int(dims[1]))
        return Self.boundsToRect(b)
    }

    // MARK: - Whitespace Expansion & Collapse

    @discardableResult
    public func insertVerticalSpace(yThreshold: Float, deltaY: Float, snapToRuling: Bool = true) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        var dims = [Int32](repeating: 0, count: 2)
        guard wn_session_insert_vertical_space(handle, yThreshold, deltaY, snapToRuling, &dims) else { return false }
        ensureRgbaBufferCapacity(width: Int(dims[0]), height: Int(dims[1]))
        return true
    }

    @discardableResult
    public func insertHorizontalSpace(xThreshold: Float, deltaX: Float, snapToRuling: Bool = true) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        var dims = [Int32](repeating: 0, count: 2)
        guard wn_session_insert_horizontal_space(handle, xThreshold, deltaX, snapToRuling, &dims) else { return false }
        ensureRgbaBufferCapacity(width: Int(dims[0]), height: Int(dims[1]))
        return true
    }

    @discardableResult
    public func insertRuledSpace(
        start: CGPoint,
        end: CGPoint,
        enableReflow: Bool = true
    ) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        var dims = [Int32](repeating: 0, count: 2)
        let ok = wn_session_insert_ruled_space(
            handle,
            Float(start.x),
            Float(start.y),
            Float(end.x),
            Float(end.y),
            enableReflow,
            &dims
        )
        if ok {
            ensureRgbaBufferCapacity(width: Int(dims[0]), height: Int(dims[1]))
        }
        return ok
    }

    // MARK: - Page Setup, Ruling, Templates & History

    @discardableResult
    public func setPageSetup(
        width: Int,
        height: Int,
        xRuling: Int,
        yRuling: Int,
        marginLeft: Int,
        paperColorArgb: UInt32,
        ruleColorArgb: UInt32,
        rulingStyle: WriteRulingStyle
    ) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        var dims4 = [Int32](repeating: 0, count: 4)
        let ok = wn_session_set_page_setup(
            handle,
            Int32(width),
            Int32(height),
            Int32(xRuling),
            Int32(yRuling),
            Int32(marginLeft),
            Int32(bitPattern: paperColorArgb),
            Int32(bitPattern: ruleColorArgb),
            rulingStyle.rawValue,
            &dims4
        )
        if ok {
            ensureRgbaBufferCapacity(width: Int(dims4[0]), height: Int(dims4[1]))
        }
        return ok
    }

    public func getPageInfo() -> WritePageInfo {
        lock.lock()
        defer { lock.unlock() }
        var info = [Int32](repeating: 0, count: 8)
        _ = wn_session_get_page_info(handle, &info)
        return WritePageInfo(
            width: info[0],
            height: info[1],
            xRuling: info[2],
            yRuling: info[3],
            marginLeft: info[4],
            paperColorArgb: info[5],
            ruleColorArgb: info[6],
            rulingStyle: WriteRulingStyle(rawValue: info[7]) ?? .standard
        )
    }

    public func setRenderTheme(isDark: Bool, darkBgArgb: UInt32 = 0xFF181825, darkFgArgb: UInt32 = 0xFFCDD6F4) {
        lock.lock()
        defer { lock.unlock() }
        wn_session_set_render_theme(handle, isDark, darkBgArgb, darkFgArgb)
    }

    @discardableResult
    public func undo() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        var dims = [Int32](repeating: 0, count: 2)
        guard wn_session_undo(handle, &dims) else { return false }
        ensureRgbaBufferCapacity(width: Int(dims[0]), height: Int(dims[1]))
        return true
    }

    @discardableResult
    public func redo() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        var dims = [Int32](repeating: 0, count: 2)
        guard wn_session_redo(handle, &dims) else { return false }
        ensureRgbaBufferCapacity(width: Int(dims[0]), height: Int(dims[1]))
        return true
    }

    public var canUndo: Bool {
        lock.lock()
        defer { lock.unlock() }
        return wn_session_can_undo(handle)
    }

    public var canRedo: Bool {
        lock.lock()
        defer { lock.unlock() }
        return wn_session_can_redo(handle)
    }

    public var strokeCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return Int(wn_session_stroke_count(handle))
    }

    // MARK: - Rendering to RGBA & CGImage

    @discardableResult
    public func renderFullPage(originX: Float = 0, originY: Float = 0) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        ensureRgbaBufferCapacity(width: width, height: height)
        return rgbaBuffer.withUnsafeMutableBufferPointer { ptr in
            wn_session_render_rgba(handle, ptr.baseAddress, Int32(width), Int32(height), originX, originY)
        }
    }

    @discardableResult
    public func renderDirtyRect(_ rect: CGRect) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        ensureRgbaBufferCapacity(width: width, height: height)
        return rgbaBuffer.withUnsafeMutableBufferPointer { ptr in
            wn_session_render_dirty_rgba(
                handle,
                ptr.baseAddress,
                Int32(width),
                Int32(height),
                Float(rect.minX),
                Float(rect.minY),
                Float(rect.maxX),
                Float(rect.maxY)
            )
        }
    }

    public func makeCGImage(renderFirst: Bool = true) -> CGImage? {
        lock.lock()
        defer { lock.unlock() }
        if renderFirst {
            _ = renderFullPage()
        }
        let w = width
        let h = height
        guard w > 0, h > 0, rgbaBuffer.count >= w * h * 4 else { return nil }
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
        let data = Data(rgbaBuffer) as CFData
        guard let provider = CGDataProvider(data: data) else { return nil }
        return CGImage(
            width: w,
            height: h,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: w * 4,
            space: colorSpace,
            bitmapInfo: bitmapInfo,
            provider: provider,
            decode: nil,
            shouldInterpolate: true,
            intent: .defaultIntent
        )
    }

    private static func boundsToRect(_ b: [Float]) -> CGRect {
        guard b.count >= 4 else { return .zero }
        return CGRect(
            x: CGFloat(b[0]),
            y: CGFloat(b[1]),
            width: CGFloat(max(0, b[2] - b[0])),
            height: CGFloat(max(0, b[3] - b[1]))
        )
    }
}

