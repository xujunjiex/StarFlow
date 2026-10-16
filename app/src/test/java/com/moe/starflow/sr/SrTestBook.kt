package com.moe.starflow.sr

import android.content.Context
import com.moe.starflow.library.LibrarySession
import com.moe.starflow.library.LibrarySessions
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/** Small persisted manga fixture so SrStore tests exercise the book-owned storage path. */
internal class SrTestBook(context: Context) {
    private val app = context.applicationContext
    val session: LibrarySession
    val id: Long
    var uuid: String = UUID.randomUUID().toString()
        private set

    val key: String get() = "${session.locationId}:manga:$uuid"
    val source: File get() = session.local("manga_import/$uuid/source.cbz")
    val srDir: File get() = session.local(".starflow/books/$uuid/sr")

    init {
        runBlocking { LibrarySessions.prepare(app) }
        session = LibrarySessions.current(app)
        id = session.nextId("manga")
        persistBook()
    }

    fun replaceIdentity(uuid: String = UUID.randomUUID().toString()) {
        session.removeBook("manga", id)
        session.flush()
        this.uuid = uuid
        persistBook()
    }

    private fun persistBook() {
        val sourceFile = source
        sourceFile.parentFile?.mkdirs()
        if (!sourceFile.isFile) sourceFile.writeBytes(byteArrayOf(1))
        ImportedMangaStore.add(session.context(), ImportedManga(
            id = id,
            title = "SR test book",
            localRoot = sourceFile.path,
            isArchive = true,
            coverPath = null,
            pageCount = 1,
            addedAt = System.currentTimeMillis(),
            bookUuid = uuid,
            libraryLocationId = session.locationId,
        ))
        session.flush()
    }
}
