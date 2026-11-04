package com.orgwritenotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orgwritenotes.ink.OnyxEInkBridge
import com.orgwritenotes.ink.WritePageSetupState
import com.orgwritenotes.ink.WriteTemplateStyle

// Page setup modal: paper template, rulings, size and colors.

/**
 * Stylus Labs Write Ruling, Template Background & Custom Template Designer Dialog (`RulingDialog`).
 */
@Composable
fun WritePageSetupModal(
    currentSetup: WritePageSetupState,
    onDismiss: () -> Unit,
    onApply: ((xRuling: Int, yRuling: Int, marginLeft: Int, paperColorArgb: Int, ruleColorArgb: Int) -> Unit)? = null,
    onApplyFull: ((
        width: Int,
        height: Int,
        xRuling: Int,
        yRuling: Int,
        marginLeft: Int,
        paperColorArgb: Int,
        ruleColorArgb: Int,
        rulingStyle: WriteTemplateStyle
    ) -> Unit)? = null,
    onLockContentAsTemplate: (() -> Unit)? = null,
    onUnlockTemplateToContent: (() -> Unit)? = null,
    onClearCustomTemplate: (() -> Unit)? = null,
    onInsertImage: ((imageBytes: ByteArray, asBackgroundTemplate: Boolean) -> Unit)? = null,
    onSetTemplateOpacity: ((Float) -> Unit)? = null
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    var width by remember { mutableStateOf(currentSetup.width) }
    var height by remember { mutableStateOf(currentSetup.height) }
    var xRuling by remember { mutableStateOf(currentSetup.xRuling) }
    var yRuling by remember { mutableStateOf(currentSetup.yRuling) }
    var marginLeft by remember { mutableStateOf(currentSetup.marginLeft) }
    var paperColor by remember {
        mutableStateOf(if (isEInk) 0xFFFFFFFF.toInt() else currentSetup.paperColorArgb)
    }
    var ruleColor by remember { mutableStateOf(currentSetup.ruleColorArgb) }
    var rulingStyle by remember { mutableStateOf(WriteTemplateStyle.fromNativeCode(currentSetup.rulingStyle)) }

    val context = LocalContext.current
    var pendingImageAsBackground by remember { mutableStateOf(true) }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && onInsertImage != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    if (bytes.isNotEmpty()) {
                        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        if (pendingImageAsBackground && opts.outWidth > 0 && opts.outHeight > 0) {
                            width = opts.outWidth.coerceIn(800, 4800)
                            height = opts.outHeight.coerceIn(600, 8000)
                        }
                        onInsertImage(bytes, pendingImageAsBackground)
                        if (pendingImageAsBackground) {
                            rulingStyle = WriteTemplateStyle.CUSTOM_DESIGNER
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    // On E-Ink devices, dark paper presets (Blueprint, Blackboard) are omitted so the canvas is always black on light paper.
    val paperPresets = if (isEInk) {
        listOf(
            "Pure White" to 0xFFFFFFFF.toInt(),
            "Light Gray" to 0xFFF8FAFC.toInt()
        )
    } else {
        listOf(
            "Warm Cream" to 0xFFFFFDF8.toInt(),
            "Pure White" to 0xFFFFFFFF.toInt(),
            "Legal Pad" to 0xFFFFFBEB.toInt(),
            "Sepia" to 0xFFF5EFE6.toInt(),
            "Blueprint" to 0xFF0F2942.toInt(),
            "Blackboard" to 0xFF1E293B.toInt()
        )
    }

    val rulePresets = if (isEInk) {
        listOf(
            "Crisp Gray" to 0xFF64748B.toInt(),
            "Dark Slate" to 0xFF334155.toInt(),
            "Pure Black" to 0xFF000000.toInt(),
            "Light Gray" to 0xFF94A3B8.toInt()
        )
    } else {
        listOf(
            "Classic Blue" to 0xFF4A90E2.toInt(),
            "Light Gray" to 0xFF94A3B8.toInt(),
            "Graph Green" to 0xFF16A34A.toInt(),
            "Sepia Brown" to 0xFFA16207.toInt(),
            "Dark Slate" to 0xFF475569.toInt()
        )
    }

    val sizePresets = listOf(
        Triple("Current (${currentSetup.width}×${currentSetup.height})", currentSetup.width, currentSetup.height),
        Triple("Inline Card (1200×600)", 1200, 600),
        Triple("Letter (1224×1584)", 1224, 1584),
        Triple("Letter 2× High-Res (2448×3168)", 2448, 3168),
        Triple("Letter Landscape (1584×1224)", 1584, 1224),
        Triple("A4 (1190×1684)", 1190, 1684),
        Triple("A4 2× High-Res (2380×3368)", 2380, 3368),
        Triple("A4 Landscape (1684×1190)", 1684, 1190),
        Triple("Tablet High-Res (2560×1600)", 2560, 1600),
        Triple("Tablet Portrait (1600×2560)", 1600, 2560)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stylus Labs Write — Template, Page & Custom Designer") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 1. Template Style Selector
                Text("1. Canvas Template Background:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    TemplateChip(
                        label = "Blank",
                        selected = rulingStyle == WriteTemplateStyle.STANDARD && xRuling == 0 && yRuling == 0,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.STANDARD
                            xRuling = 0
                            yRuling = 0
                            marginLeft = 0
                        }
                    )
                    TemplateChip(
                        label = "Ruled Lines",
                        selected = rulingStyle == WriteTemplateStyle.STANDARD && xRuling == 0 && yRuling > 0 && marginLeft == 0,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.STANDARD
                            xRuling = 0
                            if (yRuling <= 0) yRuling = 40
                            marginLeft = 0
                        }
                    )
                    TemplateChip(
                        label = "Square Grid",
                        selected = rulingStyle == WriteTemplateStyle.STANDARD && xRuling > 0 && yRuling > 0,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.STANDARD
                            if (xRuling <= 0) xRuling = 40
                            if (yRuling <= 0) yRuling = 40
                            marginLeft = 0
                        }
                    )
                    TemplateChip(
                        label = "Dotted Grid",
                        selected = rulingStyle == WriteTemplateStyle.DOTTED,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.DOTTED
                            if (xRuling <= 0) xRuling = 40
                            if (yRuling <= 0) yRuling = 40
                        }
                    )
                    TemplateChip(
                        label = "Engineering 5×5",
                        selected = rulingStyle == WriteTemplateStyle.ENGINEER_GRID,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.ENGINEER_GRID
                            xRuling = 25
                            yRuling = 25
                            marginLeft = 60
                        }
                    )
                    TemplateChip(
                        label = "Isometric Grid",
                        selected = rulingStyle == WriteTemplateStyle.ISOMETRIC,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.ISOMETRIC
                            if (yRuling <= 0) yRuling = 40
                            marginLeft = 0
                        }
                    )
                    TemplateChip(
                        label = "Music Staff",
                        selected = rulingStyle == WriteTemplateStyle.MUSIC_STAFF,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.MUSIC_STAFF
                            yRuling = 14
                            marginLeft = 40
                        }
                    )
                    TemplateChip(
                        label = "Cornell Notes",
                        selected = rulingStyle == WriteTemplateStyle.CORNELL_SUMMARY,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.CORNELL_SUMMARY
                            xRuling = 0
                            if (yRuling <= 0) yRuling = 40
                            marginLeft = 200
                        }
                    )
                    TemplateChip(
                        label = "Weekly Planner",
                        selected = rulingStyle == WriteTemplateStyle.WEEKLY_PLANNER,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.WEEKLY_PLANNER
                            xRuling = 0
                            if (yRuling <= 0) yRuling = 32
                            marginLeft = 150
                        }
                    )
                    TemplateChip(
                        label = "Custom Designer",
                        selected = rulingStyle == WriteTemplateStyle.CUSTOM_DESIGNER,
                        onClick = {
                            rulingStyle = WriteTemplateStyle.CUSTOM_DESIGNER
                        }
                    )
                }

                // 2. Custom Designer & Image Template Tools
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isEInk) Color(0xFFF8FAFC) else Color(0xFF1E293B))
                        .border(1.dp, if (isEInk) Color.Black else Color(0xFF334155), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Custom Template & Image Designer (Stylus Labs Write `contentToRuling`):",
                        color = if (isEInk) Color.Black else Color(0xFF38BDF8),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Draw any layout, axes, form, or import an image, then lock it into the non-erasable background layer.",
                        color = if (isEInk) Color(0xFF334155) else Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        if (onInsertImage != null) {
                            OutlinedButton(
                                onClick = {
                                    pendingImageAsBackground = true
                                    imagePickerLauncher.launch("image/*")
                                }
                            ) {
                                Text("Set Custom Background Image", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    pendingImageAsBackground = false
                                    imagePickerLauncher.launch("image/*")
                                }
                            ) {
                                Text("Insert Image on Canvas", fontSize = 11.sp)
                            }
                        }
                        if (onLockContentAsTemplate != null) {
                            OutlinedButton(
                                onClick = {
                                    onLockContentAsTemplate()
                                    rulingStyle = WriteTemplateStyle.CUSTOM_DESIGNER
                                }
                            ) {
                                Text("Lock Drawing as Template", fontSize = 11.sp)
                            }
                        }
                        if (onUnlockTemplateToContent != null) {
                            OutlinedButton(onClick = onUnlockTemplateToContent) {
                                Text("Unlock Template to Edit", fontSize = 11.sp)
                            }
                        }
                        if (onClearCustomTemplate != null) {
                            OutlinedButton(
                                onClick = {
                                    onClearCustomTemplate()
                                    rulingStyle = WriteTemplateStyle.STANDARD
                                }
                            ) {
                                Text("Reset Custom Template", fontSize = 11.sp)
                            }
                        }
                    }
                    if (onSetTemplateOpacity != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = "Template Opacity:",
                                color = if (isEInk) Color(0xFF334155) else Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                            listOf("25%" to 0.25f, "50%" to 0.50f, "75%" to 0.75f, "100%" to 1.0f).forEach { (label, op) ->
                                OutlinedButton(
                                    onClick = { onSetTemplateOpacity(op) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(label, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }

                // 3. Spacing & Margin Fine Controls
                Text(
                    text = "Spacing & Margins: X=${xRuling}px • Y=${yRuling}px • Left Margin=${marginLeft}px • Page=${width}×${height}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (isEInk) Color.Black else Color(0xFF64748B)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    OutlinedButton(onClick = { yRuling = (yRuling - 20).coerceAtLeast(0) }) {
                        Text("Y −20", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { yRuling = (yRuling - 5).coerceAtLeast(0) }) {
                        Text("Y −5", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { yRuling = (yRuling + 5).coerceAtMost(320) }) {
                        Text("Y +5", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { yRuling = (yRuling + 20).coerceAtMost(320) }) {
                        Text("Y +20", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { xRuling = (xRuling - 5).coerceAtLeast(0) }) {
                        Text("X −5", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { xRuling = (xRuling + 5).coerceAtMost(320) }) {
                        Text("X +5", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { marginLeft = if (marginLeft == 0) 80 else if (marginLeft == 80) 180 else 0 }) {
                        Text("Margin (${marginLeft}px)", fontSize = 11.sp)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        "Fine (24px)" to 24,
                        "College (32px)" to 32,
                        "Standard (40px)" to 40,
                        "Wide (50px)" to 50,
                        "High-Res 2× (64px)" to 64,
                        "High-Res Grid (80px)" to 80
                    ).forEach { (label, step) ->
                        TemplateChip(
                            label = label,
                            selected = yRuling == step,
                            onClick = {
                                yRuling = step
                                if (xRuling > 0) xRuling = step
                            }
                        )
                    }
                }

                // 4. Paper Size Presets
                Text("2. Paper Dimensions:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    sizePresets.forEach { (label, w, h) ->
                        TemplateChip(
                            label = label,
                            selected = width == w && height == h,
                            onClick = {
                                width = w
                                height = h
                            }
                        )
                    }
                }

                // 5. Paper Background & Rule Line Color Presets
                Text("3. Paper Background Color:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    paperPresets.forEach { (name, argb) ->
                        val selected = paperColor == argb
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) (if (isEInk) Color.Black else Color(0xFF0284C7)) else Color(0xFF94A3B8),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    paperColor = argb
                                    if (!isEInk && (argb == 0xFF1E293B.toInt() || argb == 0xFF0F2942.toInt())) {
                                        ruleColor = 0xFF475569.toInt()
                                    } else if (ruleColor == 0xFF475569.toInt()) {
                                        ruleColor = if (isEInk) 0xFF64748B.toInt() else 0xFF4A90E2.toInt()
                                    }
                                }
                                .padding(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .border(1.dp, Color.Gray, CircleShape)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(name, fontSize = 10.sp)
                        }
                    }
                }

                Text("4. Rule / Grid Line Color:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    rulePresets.forEach { (name, argb) ->
                        val selected = ruleColor == argb
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) (if (isEInk) Color.Black else Color(0xFF0284C7)) else Color(0xFF94A3B8),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable { ruleColor = argb }
                                .padding(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .border(1.dp, Color.Gray, CircleShape)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(name, fontSize = 10.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (onApplyFull != null) {
                        onApplyFull(width, height, xRuling, yRuling, marginLeft, paperColor, ruleColor, rulingStyle)
                    } else if (onApply != null) {
                        onApply(xRuling, yRuling, marginLeft, paperColor, ruleColor)
                    }
                }
            ) {
                Text("Apply Template & Setup")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
internal fun TemplateChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val isEInk = OnyxEInkBridge.isEInkDevice
    Text(
        text = label,
        color = when {
            isEInk -> if (selected) Color.White else Color.Black
            selected -> Color.White
            else -> Color(0xFFCBD5E1)
        },
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    isEInk -> if (selected) Color.Black else Color.White
                    selected -> Color(0xFF0284C7)
                    else -> Color(0xFF1E293B)
                }
            )
            .border(
                width = 1.dp,
                color = when {
                    isEInk -> Color.Black
                    selected -> Color(0xFF38BDF8)
                    else -> Color(0xFF475569)
                },
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}
