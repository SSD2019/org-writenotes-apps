@file:Suppress("ktlint")

package androidx.compose.ui.graphics

import android.graphics.Bitmap

fun Bitmap.asImageBitmap(): ImageBitmap = this.toComposeImageBitmap()

