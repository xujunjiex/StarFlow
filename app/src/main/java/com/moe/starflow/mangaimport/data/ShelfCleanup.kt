package com.moe.starflow.mangaimport.data

import android.content.Context
import com.moe.starflow.library.LibrarySessions

/** Read-only data for the deletion confirmation; LibraryDeletion owns destructive steps. */
object ShelfCleanup {
    suspend fun mangasWithTranslations(context: Context, mangas: List<ImportedManga>): List<ImportedManga> =
        mangas.filter { book ->
            val dao = LibrarySessions.forLocation(context, book.libraryLocationId).importedPageTranslationDao()
            dao.countFor(book.id, book.translationKey) > 0 || LibrarySessions.forLocation(context, book.libraryLocationId).database.catalog().resolutionCount(book.id, book.translationKey) > 0
        }
}
