package com.moe.starflow.sr

import android.content.Context
import com.moe.starflow.utils.LogCollector
import java.io.File
import java.util.UUID

/** App-specific storage shared by SR model files and developer diagnostics. */
object SrStoragePaths {
    const val ROOT_DIR_NAME = "sr"
    const val BENCH_LOG_NAME = "sr_ncnn_benchmark.txt"
    val BENCH_INPUT_NAMES = listOf("sr_bench_input.png", "sr_bench_input.webp", "sr_bench_input.jpg")

    private val migrationLock = Any()
    @Volatile private var legacyPathsMigrated = false

    /** Returns the SR root and moves benchmark files from the former sibling paths on first access. */
    fun modelDir(context: Context): File? {
        val externalFiles = context.applicationContext.getExternalFilesDir(null) ?: return null
        val root = File(externalFiles, ROOT_DIR_NAME)
        synchronized(migrationLock) {
            if (!legacyPathsMigrated) {
                root.mkdirs()
                moveLegacyFile(File(externalFiles, BENCH_LOG_NAME), File(root, BENCH_LOG_NAME))
                BENCH_INPUT_NAMES.forEach { name -> moveLegacyFile(File(externalFiles, name), File(root, name)) }
                legacyPathsMigrated = true
            }
        }
        return root
    }

    private fun moveLegacyFile(source: File, target: File) {
        if (!source.isFile) return
        if (target.isFile && target.length() == 0L) target.delete()
        val destination = if (!target.exists()) target else
            File(target.parentFile, "${target.name}.legacy-${UUID.randomUUID()}")
        if (!source.renameTo(destination)) LogCollector.w("SrStoragePaths", "旧 SR 文件迁移失败: ${source.absolutePath} -> ${destination.absolutePath}")
    }
}
