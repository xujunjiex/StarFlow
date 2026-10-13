package com.moe.starflow.novel.data

import android.content.Context
import com.moe.starflow.library.LibraryScanner

/** Explicit scans commit each completed book independently. */
object NovelLibraryScanner {
    data class Result(val added: Int, val failures: List<String>)
    suspend fun scan(context: Context): Result = LibraryScanner.scan(context, "novel").let { Result(it.added, it.failures) }
}
