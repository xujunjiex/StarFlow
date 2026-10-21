package com.moe.starflow.utils

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogExporterTest {
    private lateinit var context: Context
    private lateinit var provider: DownloadProvider

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        LogCollector.init(context)
        LogCollector.clear()
        provider = DownloadProvider(File(context.cacheDir, "exported-log.txt"))
        provider.attachInfo(context, ProviderInfo().apply { authority = "media"; exported = true })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @Test
    fun exportPublishesLatestUtf8SnapshotIncludingNativeCrashLines() {
        LogCollector.i("test", "日志窗口打开之后的新日志 😀")
        LogCollector.crashLogFile!!.appendText("NATIVE CRASH\n#00 test native frame\n")
        val result = LogExporter.export(context)
        assertEquals(provider.target, result.uri)
        assertTrue(result.location.startsWith("Download/StarFlow/starflow_log_"))
        assertTrue(provider.file.readText().contains("日志窗口打开之后的新日志 😀"))
        assertTrue(provider.file.readText().contains("#00 test native frame"))
        assertEquals("text/plain", provider.created.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals(1, provider.created.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertTrue(provider.published)
        assertFalse(provider.deleted)
    }

    @Test
    fun failedCreateRecordsExceptionAndDestination() {
        provider.failCreate = true
        assertThrows(IOException::class.java) { LogExporter.export(context) }
        val error = LogCollector.getAllLogs().last { it.level == "E" }
        assertTrue(error.message.contains("stage=create"))
        assertTrue(error.message.contains("destination=Download/StarFlow/"))
        assertTrue(error.format().contains("MediaStore insert returned null"))
        assertFalse(provider.published)
    }

    @Test
    fun failedWriteRemovesPartialFileAndPersistsFullError() {
        provider.failOpen = true
        assertThrows(FileNotFoundException::class.java) { LogExporter.export(context) }
        val logs = LogCollector.getFormattedLogs()
        assertTrue(logs.contains("stage=write"))
        assertTrue(logs.contains("FileNotFoundException: permission denied for exported-log.txt"))
        assertTrue(LogCollector.crashLogFile!!.readText().contains("permission denied for exported-log.txt"))
        assertTrue(provider.deleted)
        assertFalse(provider.published)
    }

    @Test
    fun failedPublishIsNotReportedAsSuccessAndRemovesPendingFile() {
        provider.failPublish = true
        assertThrows(IOException::class.java) { LogExporter.export(context) }
        assertTrue(LogCollector.getFormattedLogs().contains("stage=publish"))
        assertTrue(provider.deleted)
        assertFalse(provider.published)
    }

    private class DownloadProvider(val file: File) : ContentProvider() {
        val target = Uri.parse("content://media/external/downloads/42")
        lateinit var created: ContentValues
        var failCreate = false
        var failOpen = false
        var failPublish = false
        var published = false
        var deleted = false
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            created = ContentValues(values!!)
            return if (failCreate) null else target
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failOpen) throw FileNotFoundException("permission denied for exported-log.txt")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            if (failPublish) return 0
            published = values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted = true
            file.delete()
            return 1
        }
        override fun getType(uri: Uri) = "text/plain"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    }
}
