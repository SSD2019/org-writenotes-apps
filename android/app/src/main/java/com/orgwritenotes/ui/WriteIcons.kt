package com.orgwritenotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.orgwritenotes.ink.OnyxEInkBridge

// Vector icon set taken from Stylus Labs Write.

/**
 * Vector icons extracted directly from Stylus Labs Write (`android/cpp/Write/scribbleres/icons/`).
 */
enum class WriteIconAsset(
    val viewBoxW: Float,
    val viewBoxH: Float,
    val pathData: List<String>,
    val isLassoIcon: Boolean = false,
    val isExpandDownIcon: Boolean = false,
    val isExpandRightIcon: Boolean = false
) {
    BACK(
        96f, 96f,
        listOf("M46.031,79.76 L51.531,74.258 L29.162,51.885 L81.73,51.885 L81.73,44.11 L29.162,44.11 L51.531,21.739 L46.031,16.24 L14.27,47.998 Z")
    ),
    PAN(
        32f, 32f,
        listOf("M27 9.5V24c0 2.2-1.8 4-4 4h-7.3c-1.08 0-2.1-.43-2.85-1.19L5 18.83s1.26-1.23 1.3-1.25c.22-.19.49-.29.79-.29.22 0 .42.06.6.16.04.01 4.31 2.46 4.31 2.46V8c0-.83.67-1.5 1.5-1.5S15 7.17 15 8v7h1V5.5c0-.83.67-1.5 1.5-1.5S19 4.67 19 5.5V15h1V6.5c0-.83.67-1.5 1.5-1.5s1.5.67 1.5 1.5V15h1V9.5c0-.83.67-1.5 1.5-1.5s1.5.67 1.5 1.5z")
    ),
    DRAW(
        24f, 24f,
        listOf("M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25z M20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z")
    ),
    SET_PEN(
        24f, 24f,
        listOf(
            "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25z M20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
            "M16.5 15.5 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0"
        )
    ),
    ERASE_STROKE(
        96f, 96f,
        listOf("M 84,27 V 12 H 69 l -44.24,44.24 15,15 z M 13.16,67.84 c -1.56,1.56 -1.56,4.08 0,5.64 l 9.36,9.36 c 1.56,1.56 4.08,1.56 5.64,0 l 7.32,-7.32 -15,-15 z")
    ),
    ERASE_RULED(
        96f, 96f,
        listOf(
            "M 84,27 V 12 H 69 l -44.24,44.24 15,15 z M 13.16,67.84 c -1.56,1.56 -1.56,4.08 0,5.64 l 9.36,9.36 c 1.56,1.56 4.08,1.56 5.64,0 l 7.32,-7.32 -15,-15 z",
            "M14.38,37.7 H81.6 V41.7 H14.38 Z",
            "M14.38,53.7 H81.6 V57.7 H14.38 Z"
        )
    ),
    SELECT_RECT(
        96f, 96f,
        listOf("M68.71,84h-3.771v-4h3.771V84z M61.18,84h-3.77v-4h3.77V84z M53.65,84h-3.771v-4h3.771V84z M38.59,84h-3.77v-4h3.77V84z M31.061,84H27.29v-4h3.771V84z M45,69H27V51h18V69z M12,12h4v4h-4V12z M12,27.29h4v3.77h-4V27.29z M12,42.35h4v3.77h-4V42.35z M12,57.41h4v3.77h-4V57.41z M12,72.471h4v3.771h-4V72.471z M12,80h4v4h-4V80z M45,45H27V27h18V45z M27.29,12h3.771v4H27.29V12z M42.35,12h3.771v4H42.35V12z M57.41,12h3.77v4h-3.77V12z M51,27h18v18H51V27z M51,51h18v18H51V51z M80,12h4v4h-4V12z M80,27.29h4v3.77h-4V27.29z M80,42.35h4v3.77h-4V42.35z M80,57.41h4v3.77h-4V57.41z M80,72.471h4v3.771h-4V72.471z M80,80h4v4h-4V80z")
    ),
    SELECT_LASSO(
        96f, 96f,
        listOf(
            "M27,51 h18 v18 h-18 z",
            "M27,27 h18 v18 h-18 z",
            "M51,27 h18 v18 h-18 z",
            "M51,51 h18 v18 h-18 z"
        ),
        isLassoIcon = true
    ),
    SELECT_RULED(
        96f, 96f,
        listOf(
            "M18.5,39.7 H77.5 V43.7 H18.5 Z",
            "M18.5,51.7 H77.5 V55.7 H18.5 Z",
            "M18.5,63.7 H58.0 V67.7 H18.5 Z",
            "M41.8,27.7 H77.5 V31.7 H41.8 Z"
        )
    ),
    INSERT_SPACE(
        96f, 96f,
        listOf(
            "M14.381,75.493v6.014h67.238l-0.016-6.014H14.381z M47.716,69.654L31.349,47.329h10.209V26.493h12.213v20.836h10.313L47.716,69.654z",
            "M14.381,12.993 L14.381,19.007 L81.619,19.007 L81.604,12.993 Z"
        )
    ),
    UNDO(
        24f, 24f,
        listOf("M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z")
    ),
    REDO(
        96f, 96f,
        listOf("M43.148,40c-22.086,0-27.373,15.467-28.59,21.645l15.229,8.24c0-2.519,0.866-6.097,2.274-8.24c2.521-3.85,6.832-5.674,11.03-5.674h7.897v22.043l30.45-30.026L50.99,17.986V40H43.148")
    ),
    EXPAND_DOWN(
        96f, 96f,
        listOf(
            "M24.75,45.76 h24.56 v2 h-24.56 z",
            "M24.75,39.75 h14.45 v2 h-14.45 z",
            "M24.75,33.75 h18.78 v2 h-18.78 z",
            "M24.75,27.75 h26.00 v2 h-26.00 z"
        ),
        isExpandDownIcon = true
    ),
    EXPAND_RIGHT(
        96f, 96f,
        listOf(
            "M24.75,45.76 h24.56 v2 h-24.56 z",
            "M24.75,39.75 h14.45 v2 h-14.45 z",
            "M24.75,33.75 h18.78 v2 h-18.78 z",
            "M24.75,27.75 h26.00 v2 h-26.00 z"
        ),
        isExpandRightIcon = true
    ),
    PAGE_SETUP(
        96f, 96f,
        listOf("M61.599,15.805H21.74v64.39h52.52v-64.39H61.599z M68.378,69.199H27.624v-3.672h40.754V69.199z M68.378,59.652H27.462v-3.834h40.916V59.652z M68.541,49.945H27.624v-3.999h40.917V49.945z M68.541,39.911H27.624v-3.668h40.917V39.911z M68.541,30.203H27.624v-3.672h40.917V30.203z")
    ),
    DUPLICATE(
        28f, 28f,
        listOf("M5 7H3v16c0 1.1.9 2 2 2h16v-2H5V7zm18-4H9c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H9V5h14v14z")
    ),
    DELETE(
        96f, 96f,
        listOf(
            "M68.917,41.842c-0.046-0.281-0.218-0.557-0.476-0.82c-2.079,2.112-10.444,2.462-20.445,2.462s-18.358-0.35-20.446-2.462c-0.257,0.263-0.417,0.539-0.476,0.82L28.7,75.518c0.145,2.615,3.139,6.568,19.292,6.568c16.155,0,19.151-3.953,19.292-6.568L68.917,41.842z",
            "M57.95,24.386v-7.875c0-1.422-1.164-2.597-2.595-2.597H39.769c-1.421,0-2.59,1.175-2.59,2.597v7.965c-7.706,0.874-12.981,2.644-12.981,4.694v3.893c0,0.799,0.809,1.559,2.26,2.244c3.801,1.786,12.017,3.024,21.538,3.024c9.528,0,17.741-1.238,21.547-3.024c1.442-0.685,2.259-1.445,2.259-2.244v-3.893C71.802,27.048,66.12,25.221,57.95,24.386z"
        )
    ),
    ZOOM(
        24f, 24f,
        listOf("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z")
    ),
    FULLSCREEN(
        24f, 24f,
        listOf("M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z")
    ),
    PIN(
        96f, 96f,
        listOf("M64,16 L32,16 L32,24 L38,24 L38,48 L24,60 L24,66 L44,66 L44,86 L52,86 L52,66 L72,66 L72,60 L58,48 L58,24 L64,24 Z")
    ),
    MENU(
        24f, 24f,
        listOf("M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z")
    ),
    IMAGE(
        24f, 24f,
        listOf("M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z")
    ),
    CLOSE(
        24f, 24f,
        listOf("M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z")
    );
}

@Composable
fun WriteSvgIcon(
    asset: WriteIconAsset,
    tint: Color = if (OnyxEInkBridge.isEInkDevice) Color.Black else Color(0xFFF1F5F9),
    size: Dp = 20.dp,
    contentDescription: String? = null
) {
    val parsedPaths = remember(asset) {
        asset.pathData.map { d -> PathParser().parsePathString(d).toPath() }
    }
    val a11y = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier.clearAndSetSemantics { }
    }
    Canvas(modifier = Modifier.size(size).then(a11y)) {
        val sx = this.size.width / asset.viewBoxW
        val sy = this.size.height / asset.viewBoxH
        withTransform({
            scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero)
        }) {
            parsedPaths.forEach { p ->
                drawPath(path = p, color = tint)
            }
            if (asset.isLassoIcon) {
                drawCircle(
                    color = tint,
                    radius = 34.377f,
                    center = Offset(48f, 48f),
                    style = Stroke(
                        width = 4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)
                    )
                )
            }
            if (asset.isExpandDownIcon) {
                drawRect(
                    color = tint,
                    topLeft = Offset(16.75f, 11.74f),
                    size = Size(42.0f, 72.9f),
                    style = Stroke(width = 4f)
                )
                drawLine(
                    color = tint,
                    start = Offset(18f, 58.75f),
                    end = Offset(56.8f, 58.75f),
                    strokeWidth = 4f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f)
                )
            }
            if (asset.isExpandRightIcon) {
                drawRect(
                    color = tint,
                    topLeft = Offset(16.75f, 11.74f),
                    size = Size(62.4f, 47.0f),
                    style = Stroke(width = 4f)
                )
                drawLine(
                    color = tint,
                    start = Offset(58.75f, 13.5f),
                    end = Offset(58.75f, 56.87f),
                    strokeWidth = 4f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f)
                )
            }
        }
    }
}
