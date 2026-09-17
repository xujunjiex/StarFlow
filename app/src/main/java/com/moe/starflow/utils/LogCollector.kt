package com.moe.starflow.utils
import com.moe.starflow.translate.widget.*

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 统一日志收集器（内存缓冲 + 文件持久化，固定 [MAX_ENTRIES] 条滚动）
 *
 * 日志文件 `getExternalFilesDir/logs/starflow.log` 持久化最近 [MAX_ENTRIES] 条日志
 * （所有级别）：追加写入，超过 300 条自动换出最旧的；**闪退/进程死亡后文件仍保留**，
 * 重开 app 时 init 载入缓冲，日志查看器能看到上次（含多次）崩溃的记录。用户可手动清理。
 *
 * - 内存缓冲：日志查看器实时查看最近 300 条（进程死亡即清空）
 * - 文件落盘：所有级别追加写入，超 300 行丢最旧；native 崩溃块由 hymt2_bridge 信号
 *   处理器追加写入同一文件（滚动由 Java 侧维护，处理器内不做读文件解析）
 * - 启动时把文件内容（最近 300 行）载入内存缓冲，崩溃后重开 app 查看器也能看到
 *
 * ⚠️ 必须在 Application.onCreate 调用 [init] 后才能落盘（否则只有内存缓冲）。
 */
object LogCollector {

    // 日志 tag 常量
    const val TAG_OCR = "OCR"
    const val TAG_DETECTION = "DetectionBridge"

    /** 日志文件名（统一） */
    const val LOG_FILE_NAME = "starflow.log"

    /** 日志条数上限：内存缓冲 + 文件行数，超过自动换出最旧（丢最旧保留最新） */
    private const val MAX_ENTRIES = 300

    /** 旧版全量日志残留检测阈值（300 条日志不可能超过此大小，超了判定为旧版 appendText 遗留） */
    private const val MAX_ERROR_REPORT_BYTES = 256 * 1024L

    /**
     * 文件滚动检查的松弛量：**不要每写一行就扫一遍文件**。
     * [trimFileToTail] 要 readLines + writeText 整个文件，而翻译一页会打几十行日志 ——
     * 每行都裁剪 = 每次都全量读写一遍 40~60KB 文件（还在调用线程上），纯粹白烧 I/O。
     * 攒到 MAX_ENTRIES + 这个数再裁，文件行数上限依旧只在 MAX_ENTRIES + SLACK 量级。
     */
    private const val TRIM_SLACK_LINES = 50

    /** 崩溃块头部保留行数（banner / sig= / mem: / diag: / 前若干帧） */
    private const val CRASH_HEAD_LINES = 120

    /** 日志文件目录（init 后可用） */
    @Volatile
    private var logDir: File? = null

    /** 当前日志文件（追加写入） */
    @Volatile
    private var logFile: File? = null

    private val buffer = CopyOnWriteArrayList<LogEntry>()

    /** 文件当前行数（只在 synchronized(file) 内读写；-1 = 未知，裁剪时重新数） */
    @Volatile
    private var fileLineCount = -1

    /**
     * 初始化文件落盘。幂等：重复调用只刷新路径。
     * @param context 应用上下文（取 applicationContext）
     */
    @Synchronized
    fun init(context: Context) {
        try {
            val dir = File(context.applicationContext.getExternalFilesDir(null), "logs")
            if (!dir.exists()) dir.mkdirs()
            logDir = dir
            logFile = File(dir, LOG_FILE_NAME)
            val file = logFile
            if (file != null && file.exists() && file.length() > MAX_ERROR_REPORT_BYTES) {
                file.writeText("")  // 旧版 MB 级全量日志残留清理（300 条日志不会这么大）
            }
            // 把文件内容（上次会话，含崩溃）载入缓冲，闪退后查看器仍能看到
            loadTailFromFile()
        } catch (_: Exception) {
        }
    }

    /**
     * 启动时把文件尾部日志载入内存缓冲。
     * 文件最多 [MAX_ENTRIES] 行，上次会话/崩溃的内容都在里面。
     */
    private fun loadTailFromFile() {
        val file = logFile ?: return
        if (!file.exists() || file.length() == 0L) return
        try {
            var lastLevel = "I"
            var lastTag = "History"
            val allLines = file.readText().split('\n')
            fileLineCount = allLines.size
            val lines = allLines.takeLast(MAX_ENTRIES)
            for (line in lines) {
                if (line.isBlank()) continue
                val parsed = parseFileLine(line, lastLevel, lastTag)
                lastLevel = parsed.level
                lastTag = parsed.tag
                buffer.add(parsed)
            }
            while (buffer.size > MAX_ENTRIES) buffer.removeAt(0)
        } catch (_: Exception) {
        }
    }

    // 文件行格式：[HH:mm:ss.SSS] L/TAG: msg
    private val FILE_LINE_REGEX = Regex("""^\[(\d{2}:\d{2}:\d{2}\.\d{3})] ([VDIWE])/([^:]+): (.*)$""")

    /**
     * 把文件里的日志行解析回 [LogEntry]。带标准前缀的按级别还原（E 级继续红色高亮）；
     * 无前缀的行（多行堆栈的后续行）**继承最近一条的级别/tag**，让 E 级报错堆栈整体保持红色；
     * 无法识别的崩溃特征行（native 崩溃块）标 E 级醒目。
     *
     * @param lastLevel/lastTag 上一条已解析行的级别/tag，用于无前缀堆栈行的继承
     */
    private fun parseFileLine(line: String, lastLevel: String, lastTag: String): LogEntry {
        val m = FILE_LINE_REGEX.matchEntire(line)
        if (m != null) {
            val level = m.groupValues[2]
            val tag = m.groupValues[3]
            val ts = try {
                SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                    .parse(m.groupValues[1])?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
            return LogEntry(level, tag, m.groupValues[4], null, ts)
        }
        if (line.contains("NATIVE CRASH") || line.contains("END CRASH") ||
            line.startsWith("sig=") || line.startsWith("#")) {
            return LogEntry("E", "NativeCrash", line)
        }
        // 无前缀行（如 Java 异常堆栈的 \tat ... 后续行）：继承上一条级别，E 级堆栈保持红色
        if (lastLevel == "E") {
            return LogEntry("E", lastTag, line)
        }
        return LogEntry("I", "History", line)
    }

    /** 统一日志文件路径（Java + native 共用） */
    val logFilePath: String?
        get() = logFile?.absolutePath

    /** 统一日志文件（给 AboutMe 崩溃日志入口展示用） */
    val crashLogFile: File?
        get() = logFile

    /** 读取文件落盘的全部日志（native 崩溃 backtrace 也在这里） */
    fun readLogFile(): String {
        return try {
            logFile?.takeIf { it.exists() }?.readText() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    data class LogEntry(
        val level: String,  // D, I, W, E, V
        val tag: String,
        val message: String,
        val throwable: Throwable? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun format(): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                .format(java.util.Date(timestamp))
            val sb = StringBuilder("[$time] $level/$tag: $message")
            if (throwable != null) {
                sb.append("\n").append(Log.getStackTraceString(throwable))
            }
            return sb.toString()
        }
    }

    fun v(tag: String, msg: String): Int {
        addEntry("V", tag, msg)
        return Log.v(tag, msg)
    }

    fun d(tag: String, msg: String): Int {
        addEntry("D", tag, msg)
        return Log.d(tag, msg)
    }

    fun i(tag: String, msg: String): Int {
        addEntry("I", tag, msg)
        return Log.i(tag, msg)
    }

    fun w(tag: String, msg: String): Int {
        addEntry("W", tag, msg)
        return Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable?): Int {
        addEntry("W", tag, msg, tr)
        return Log.w(tag, msg, tr)
    }

    fun e(tag: String, msg: String): Int {
        addEntry("E", tag, msg)
        return Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable?): Int {
        addEntry("E", tag, msg, tr)
        return Log.e(tag, msg, tr)
    }

    private fun addEntry(level: String, tag: String, msg: String, tr: Throwable? = null) {
        val entry = LogEntry(level, tag, msg, tr)
        buffer.add(entry)
        while (buffer.size > MAX_ENTRIES) {
            buffer.removeAt(0)
        }
        // 文件持久化（所有级别）：追加写入，超 MAX_ENTRIES 行滚动丢最旧。
        // 闪退/进程死亡后文件仍在，重开 app 时 init 载入缓冲。
        try {
            val file = logFile
            if (file != null) {
                synchronized(file) {
                    file.appendText(entry.format() + "\n")
                    // 攒够再裁，别每行都扫一遍整个文件（见 TRIM_SLACK_LINES）
                    if (fileLineCount < 0) fileLineCount = countLines(file)
                    fileLineCount++
                    if (fileLineCount > MAX_ENTRIES + TRIM_SLACK_LINES) trimFileToTail(file)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 文件滚动：行数超 [MAX_ENTRIES] 时丢最旧、保留最新。
     *
     * **崩溃块保护**：文件含 NATIVE CRASH 块时，保留「**崩溃块头部**（banner / sig / mem / diag /
     * 前若干帧，共 [CRASH_HEAD_LINES] 行）+ 最新日志」，中间用一行省略标记连起来。
     * 只保留尾部（takeLast）会从崩溃块头部一行行啃掉 —— 先丢 banner，最后整块消失，
     * 闪退后导出日志再也查不到崩溃原因（本方法存在的意义）。
     *
     * ⚠️ 每次调用都要 readLines + writeText 整个文件，**不要每写一行日志就调**：
     * 由 [addEntry] 按 [TRIM_SLACK_LINES] 攒批触发。
     */
    private fun trimFileToTail(file: File) {
        if (file.length() == 0L) {
            fileLineCount = 0
            return
        }
        try {
            val lines = file.readLines()
            if (lines.size <= MAX_ENTRIES) {
                fileLineCount = lines.size
                return
            }
            val crashStart = lines.indexOfFirst { it.contains("NATIVE CRASH") }
            val kept: List<String> = if (crashStart >= 0) {
                // ⚠️ 崩溃块**头部**必须留住：banner / sig= / mem: / diag: / 前若干帧是唯一诊断入口。
                // 单纯 takeLast(MAX_ENTRIES) 会从头部一行行啃（先啃掉 banner，最后整块消失）——
                // 这正是"崩溃块不会被滚动挤出"那句承诺被打破的方式。
                val headEnd = minOf(lines.size, crashStart + CRASH_HEAD_LINES)
                val head = lines.subList(crashStart, headEnd)
                val tailKeep = (MAX_ENTRIES - head.size).coerceAtLeast(0)
                val tailStart = maxOf(headEnd, lines.size - tailKeep)
                if (tailStart >= lines.size) head
                else head + "… ${tailStart - headEnd} lines omitted (log rolled) …" +
                    lines.subList(tailStart, lines.size)
            } else {
                lines.takeLast(MAX_ENTRIES)
            }
            file.writeText(kept.joinToString("\n") + "\n")
            fileLineCount = kept.size
        } catch (_: Exception) {
        }
    }

    /** 数文件行数（init 已记过；只在计数器未知时兜底调用）。 */
    private fun countLines(file: File): Int = try {
        file.readLines().size
    } catch (_: Exception) {
        -1
    }

    /**
     * 获取所有日志（从旧到新）
     */
    fun getAllLogs(): List<LogEntry> {
        return buffer.toList().sortedBy { it.timestamp }
    }

    /**
     * 获取格式化的日志文本
     */
    fun getFormattedLogs(): String {
        return getAllLogs().joinToString("\n") { it.format() }
    }

    /**
     * 清空日志（内存缓冲 + 文件）。用户手动清理入口。
     */
    fun clear() {
        buffer.clear()
        try {
            logFile?.writeText("")
        } catch (_: Exception) {
        }
    }
}
