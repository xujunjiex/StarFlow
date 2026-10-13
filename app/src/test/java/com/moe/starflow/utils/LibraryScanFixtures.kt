package com.moe.starflow.utils

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LibraryScanFixtures {
    fun png(): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888)
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    fun zip(file: File, vararg entries: Pair<String, ByteArray>) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    fun epub(file: File) = zip(
        file,
        "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""".toByteArray(),
        "book.opf" to """<package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Illustrated book</dc:title></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>""".toByteArray(),
        "chapter.xhtml" to """<html><body><p>Chapter body with enough text.</p><img src="picture.png"/></body></html>""".toByteArray(),
        "picture.png" to png(),
    )
}
