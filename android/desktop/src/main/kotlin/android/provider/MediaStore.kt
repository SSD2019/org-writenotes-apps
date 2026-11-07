@file:Suppress("ktlint")

package android.provider

import android.net.Uri

object MediaStore {
    open class MediaColumns {
        companion object {
            const val DISPLAY_NAME = "name"
            const val MIME_TYPE = "mime_type"
            const val RELATIVE_PATH = "relative_path"
            const val IS_PENDING = "is_pending"
        }
    }

    object Images {
        object Media {
            @JvmField val EXTERNAL_CONTENT_URI: Uri = Uri.parse("media://images/external")
            const val DISPLAY_NAME = "name"
            const val MIME_TYPE = "mime_type"
            const val RELATIVE_PATH = "relative_path"
            const val IS_PENDING = "is_pending"
        }
    }

    object Downloads {
        @JvmField val EXTERNAL_CONTENT_URI: Uri = Uri.parse("media://downloads/external")
        const val DISPLAY_NAME = "name"
        const val MIME_TYPE = "mime_type"
        const val RELATIVE_PATH = "relative_path"
        const val IS_PENDING = "is_pending"
    }
}
