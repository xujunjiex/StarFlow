package com.moe.starflow.library

import android.content.Context
import android.provider.DocumentsContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Roots the system deletes on uninstall must never become a custom library. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryLocationSafetyTest {
    @Test fun appPrivateExternalStorageTreesAreUnsafe() {
        listOf(
            "primary:Android/data",
            "primary:Android/data/com.moe.starflow/files",
            "1A2B-3C4D:Android/obb/com.example",
            "primary:Android/media/com.example",
            "primary:/android/DATA/com.example",
        ).forEach { assertNotNull(it, LibraryLocationSafety.unsafeDocumentReason(it)) }
    }

    @Test fun publicTreesAndLookalikesAreSafe() {
        listOf(
            "primary:Documents/StarFlow",
            "primary:Download/Android/data-backup",
            "primary:Documents/Android/data",
            "primary:Android/database",
            "primary:Android/media2",
            "primary:Android",
            "no-colon",
            "",
        ).forEach { assertNull(it, LibraryLocationSafety.unsafeDocumentReason(it)) }
    }

    @Test fun selectionRejectsAnAppPrivateTree() = runBlocking {
        val app: Context = RuntimeEnvironment.getApplication()
        val tree = DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Android/data/com.moe.starflow/files",
        )
        val error = runCatching { LibrarySessions.select(app, tree) }.exceptionOrNull()
        assertTrue("expected LibraryLocationUnsafeException, got $error", error is LibraryLocationUnsafeException)
    }
}
