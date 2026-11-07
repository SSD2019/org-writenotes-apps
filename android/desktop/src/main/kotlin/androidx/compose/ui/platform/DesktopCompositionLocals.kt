@file:Suppress("ktlint")

package androidx.compose.ui.platform

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf

val LocalContext = staticCompositionLocalOf<Context> { Context.instance }
val LocalConfiguration = staticCompositionLocalOf<Configuration> { Configuration() }

