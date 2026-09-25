package com.moe.starflow.download
import com.moe.starflow.translate.widget.*

import com.moe.starflow.utils.LogCollector
import java.io.File
import java.security.MessageDigest

object ChecksumHelper {
    private const val TAG = "ChecksumHelper"

    fun calculateMD5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifyChecksum(file: File, expectedMd5: String): Boolean =
        verifyChecksum(file, listOf(expectedMd5))

    /**
     * 多候选校验：MD5 **只算一遍**，命中任意一个候选即算通过。
     *
     * 用于「同一个文件有多个合法 MD5」的场景 —— 本地 GGUF 既可能是官方原件、也可能是设备端
     * 重打标后的版本（张量类型 42→43，见 patches/README.md），两者都不该被当成损坏。
     * 空候选列表视为通过：调用方没有任何校验依据时不能删文件。
     */
    fun verifyChecksum(file: File, expectedMd5List: List<String>): Boolean {
        val candidates = expectedMd5List.filter { it.isNotEmpty() }
        if (candidates.isEmpty()) return true
        val actual = try {
            calculateMD5(file)
        } catch (e: Exception) {
            LogCollector.e(TAG, "MD5 verify failed: ${file.name}", e)
            return false
        }
        return candidates.any { it.equals(actual, ignoreCase = true) }
    }
}

enum class VerifyResult { COMPLETE, MISSING, DAMAGED }
