@file:Suppress("ktlint")

package android.content.res

class Configuration(
    @JvmField var screenWidthDp: Int = 1280,
    @JvmField var screenHeightDp: Int = 800,
    @JvmField var densityDpi: Int = 160,
    @JvmField var orientation: Int = ORIENTATION_LANDSCAPE
) {
    companion object {
        const val ORIENTATION_UNDEFINED = 0
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
    }
}
