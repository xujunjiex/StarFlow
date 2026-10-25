package com.moe.starflow.sr

import android.system.Os
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Robolectric does not implement Os.rename; exercise the real filesystem commit in these tests. */
@Implements(Os::class)
class SrStoreTestOs {
    companion object {
        @JvmStatic
        @Implementation
        fun rename(oldPath: String, newPath: String) {
            Files.move(
                File(oldPath).toPath(), File(newPath).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
