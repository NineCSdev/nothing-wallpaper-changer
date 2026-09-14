package com.ninecsdev.wallpaperchanger.data.backup

/** How many source lookups either half of a backup keeps in flight */
internal const val FINGERPRINT_CONCURRENCY = 10

/** Buffer size both halves copy image bytes through. */
internal const val COPY_BUFFER_BYTES = 64 * 1024
