package com.moe.starflow.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/** Cover reads never allocate an entire original page. Each pass owns its input stream. */
internal object ImportCoverDecoder {
    fun decode(maxDimension: Int, open: () -> InputStream?): Bitmap? {
        require(maxDimension > 0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
            inSampleSize = sample
        }
        return open()?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}
