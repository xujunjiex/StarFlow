package com.moe.starflow.mangaimport.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 导入失败原因的**分类**（`ImportManager.classifyArchive`）。
 *
 * 这是"用户看到哪句话"的唯一判据，而它长得像一段无脑 `when`，最容易在后续调整里被改坏 ——
 * 尤其 PDF 那两条：`PdfImportException` 继承 `IllegalStateException`，一旦分支顺序被挪到
 * `IllegalArgumentException` 之后（或有人"顺手合并"成一个分支），加密/损坏的 PDF 就会
 * 显示成「不是有效的 zip/cbz 压缩包」，用户拿着一个 PDF 被告知"不是压缩包"。
 */
class PdfImportFailureClassifyTest {

    @Test
    fun `encryptedPdfKeepsItsOwnReason`() {
        val reason = ImportManager.classifyArchive(
            PdfImportException(ImportFailureReason.PDF_ENCRYPTED, "password required")
        )
        assertEquals(ImportFailureReason.PDF_ENCRYPTED, reason)
    }

    @Test
    fun `brokenPdfKeepsItsOwnReason`() {
        val reason = ImportManager.classifyArchive(
            PdfImportException(ImportFailureReason.PDF_INVALID, "cannot create PdfRenderer")
        )
        assertEquals(ImportFailureReason.PDF_INVALID, reason)
    }

    @Test
    fun `pdfIoFailureFallsBackToUnreadable`() {
        val reason = ImportManager.classifyArchive(
            PdfImportException(ImportFailureReason.UNREADABLE, "file not found")
        )
        assertEquals(ImportFailureReason.UNREADABLE, reason)
    }

    @Test
    fun `archiveAndIoFailuresKeepTheirSpecificReasons`() {
        assertEquals(
            ImportFailureReason.ARCHIVE_INVALID,
            ImportManager.classifyArchive(java.util.zip.ZipException("not a zip")),
        )
        assertEquals(
            ImportFailureReason.NOT_ARCHIVE,
            ImportManager.classifyArchive(IllegalArgumentException("不支持的压缩包格式")),
        )
        assertEquals(
            ImportFailureReason.SOURCE_OPEN_FAILED,
            ImportManager.classifyArchive(java.io.FileNotFoundException("gone")),
        )
        assertEquals(
            ImportFailureReason.UNKNOWN,
            ImportManager.classifyArchive(RuntimeException("???")),
        )
    }
}
