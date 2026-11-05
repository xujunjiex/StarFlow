package com.moe.starflow.library

import android.content.Context
import com.moe.starflow.R
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.mangaimport.data.ImportManager
import com.moe.starflow.novel.data.NovelImportManager

/** The session owns live save status; outbox/cleanup rows remain the only durable retry queue. */
enum class LibraryErrorKind { FILES, ACCESS, IDENTITY, DATABASE, VERSION }
enum class LibraryIssueScope(val label: Int) {
    LIBRARY(R.string.library_issue_library), BOOK(R.string.library_issue_book),
    FILES(R.string.library_issue_files), NOTICE(R.string.library_issue_notice)
}
enum class LibraryBookOutcome(val label: Int) {
    NOT_ADDED(R.string.library_outcome_not_added), RETAINED(R.string.library_outcome_retained),
    FILES_PENDING(R.string.library_outcome_pending), NONE(R.string.library_outcome_none)
}
data class LibraryScanIssue(val scope: LibraryIssueScope, val stage: String, val path: String,
    val detail: String, val uuid: String? = null, val title: String? = null, val reason: String = "",
    val outcome: LibraryBookOutcome = LibraryBookOutcome.NONE) {
    fun text(context: Context): String = listOfNotNull(
        title?.takeIf(String::isNotBlank), path.takeIf(String::isNotBlank), uuid?.let { "UUID: $it" },
        "[$stage] ${reason.takeIf(String::isNotBlank)?.let { "$it: " }.orEmpty()}$detail",
        context.getString(outcome.label).takeIf(String::isNotBlank)
    ).joinToString("\n")
}
data class LibraryFileIssue(val token: String, val component: String, val uuid: String?, val path: String, val detail: String)
enum class LibraryFileStatus { SAVED, FAILED, DEFERRED }
data class LibraryFileResult(val token: String, val component: String, val uuid: String?, val path: String,
    val status: LibraryFileStatus, val detail: String = "")
data class LibraryFlushResult(val items: List<LibraryFileResult> = emptyList()) {
    val failures: List<LibraryFileIssue> get() = items.filter { it.status == LibraryFileStatus.FAILED }
        .map { LibraryFileIssue(it.token, it.component, it.uuid, it.path, it.detail) }
}

internal fun libraryArtifactUuid(path: String): String? = path.takeIf { it.startsWith(".starflow/books/") }
    ?.removePrefix(".starflow/books/")?.substringBefore('/')?.takeIf { LibraryCodec.UUID_PATTERN.matches(it) }
internal fun libraryErrorKind(error: Throwable): LibraryErrorKind {
    val seen = hashSetOf<Throwable>()
    var current: Throwable? = error
    while (current != null && seen.add(current) && seen.size <= 8) {
        when (current) {
            is LibraryIdentityException -> return LibraryErrorKind.IDENTITY
            is LibraryVersionException -> return LibraryErrorKind.VERSION
            is android.database.sqlite.SQLiteException -> return LibraryErrorKind.DATABASE
            is LibraryFileWriteException -> return LibraryErrorKind.FILES
        }
        // Provider/filesystem wrappers describe an access failure even if the remote
        // implementation itself happens to use SQLite. Only migration wraps local DB work.
        if (current is java.io.IOException && current !is LibraryMigrationException) return LibraryErrorKind.ACCESS
        current = current.cause
    }
    return LibraryErrorKind.ACCESS
}
/** Shared by import and scan diagnostics; preserve operation plus provider/filesystem root cause. */
internal fun libraryFailureDetail(error: Throwable): String {
    val seen = mutableSetOf<Throwable>()
    val messages = linkedSetOf<String>()
    var current: Throwable? = error
    while (current != null && seen.add(current) && seen.size <= 8 && messages.size < 3) {
        messages += listOfNotNull(current.javaClass.simpleName.takeIf(String::isNotBlank), current.localizedMessage?.trim())
            .joinToString(": ")
        current = current.cause
    }
    return messages.joinToString("\n").take(4096)
}

/** One instance belongs to one scan run and never holds UI references. */
internal class LibraryScanProblems(val context: Context, val session: LibrarySession, val kind: String, val runId: String) {
    private val entries = linkedMapOf<List<Any?>, LibraryScanIssue>()
    val issues get() = entries.values.toList()
    val failures get() = issues.filter { it.scope != LibraryIssueScope.NOTICE }.map { it.text(context) }
    fun add(issue: LibraryScanIssue, error: Throwable? = null) {
        val key = listOf(issue.scope, issue.uuid ?: issue.path, issue.stage, issue.reason, issue.detail)
        if (entries.putIfAbsent(key, issue) == null) {
            val message = "run=$runId location=${session.locationId} kind=$kind scope=${issue.scope} " +
                "stage=${issue.stage} uuid=${issue.uuid} path=${issue.path} title=${issue.title}: ${issue.detail}"
            if (issue.scope == LibraryIssueScope.NOTICE) LogCollector.i("LibraryScanner", message)
            else LogCollector.w("LibraryScanner", message, error)
        }
    }
    fun failed(stage: String, path: String, e: Exception, book: LibraryBook? = null,
        scope: LibraryIssueScope = LibraryIssueScope.BOOK, outcome: LibraryBookOutcome = LibraryBookOutcome.RETAINED,
        directory: Boolean = false, uuidHint: String? = null, titleHint: String? = null) {
        if (e is kotlinx.coroutines.CancellationException || e is android.database.sqlite.SQLiteException ||
            e is LibraryIdentityException || e is LibraryVersionException) throw e
        val reason = if (scope != LibraryIssueScope.BOOK || stage != context.getString(R.string.library_stage_inspect)) ""
            else if (kind == "novel") NovelImportManager.classify(e).name
            else if (directory) ImportManager.classifyDirectory(e).name else ImportManager.classifyArchive(e).name
        val title = book?.let { runCatching { org.json.JSONObject(it.body).optString("title") }.getOrNull() }
        add(LibraryScanIssue(scope, stage, path, libraryFailureDetail(e), book?.uuid ?: uuidHint, title ?: titleHint, reason, outcome), e)
    }
    fun files(result: LibraryFlushResult) {
        result.failures.filter { it.component != "metadata" }.forEach { file ->
            val book = file.uuid?.let { session.database.catalog().byUuid(it) }
            val title = book?.let { runCatching { org.json.JSONObject(it.body).optString("title") }.getOrNull() }
            add(LibraryScanIssue(LibraryIssueScope.FILES, context.getString(R.string.library_stage_save),
                file.path, file.detail, file.uuid, title, file.component, LibraryBookOutcome.FILES_PENDING))
        }
    }
}

internal fun LibraryScanState.details(context: Context): String = buildString {
    appendLine(context.getString(R.string.library_scan_summary, added,
        issues.count { it.scope != LibraryIssueScope.NOTICE }, issues.count { it.scope == LibraryIssueScope.NOTICE }))
    appendLine("Run: $runId")
    LibraryIssueScope.entries.forEach { scope ->
        val group = issues.filter { it.scope == scope }
        if (group.isNotEmpty()) {
            appendLine()
            appendLine(context.getString(scope.label) + " (${group.size})")
            appendLine(group.joinToString("\n\n") { issue ->
                if (scope == LibraryIssueScope.LIBRARY) libraryRecoveryDetails(context, issue.text(context),
                    LibraryErrorKind.entries.firstOrNull { it.name == issue.reason } ?: LibraryErrorKind.ACCESS)
                else issue.text(context)
            })
        }
    }
}

internal fun libraryRecoveryDetails(context: Context, detail: String, kind: LibraryErrorKind): String =
    detail + "\n\n" + context.getString(when (kind) {
        LibraryErrorKind.FILES -> R.string.library_help_files
        LibraryErrorKind.ACCESS -> R.string.library_help_access
        LibraryErrorKind.IDENTITY, LibraryErrorKind.DATABASE -> R.string.library_help_identity
        LibraryErrorKind.VERSION -> R.string.library_help_version
    })
