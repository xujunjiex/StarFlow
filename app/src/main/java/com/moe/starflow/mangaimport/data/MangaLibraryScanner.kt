package com.moe.starflow.mangaimport.data

import android.content.Context
import com.moe.starflow.library.LibraryScanner

/** Explicit scans commit each completed book independently. */
object MangaLibraryScanner {
    data class Result(val added: Int, val failures: List<String>)
    suspend fun scan(context: Context): Result = LibraryScanner.scan(context, "manga").let { Result(it.added, it.failures) }
}
