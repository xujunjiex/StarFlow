package com.moe.starflow.mangaimport.data

import java.io.File

/**
 * 导入漫画的只读工具：自然排序、图片判定、页枚举、封面提取。
 * 不依赖 SAF uri，全部针对「复制进 app 目录后的 File」。
 */
object ArchivedMangaReader {

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTS
    }

    /** 自然排序：按字母 + 数字分段，数字段按数值比较（page2 < page10）。 */
    fun sortNaturally(names: List<String>): List<String> =
        names.sortedWith(naturalComparator())

    /** 供需要自定义排序之处复用的原语比较，等价于 sortNaturally 的排序依据。 */
    fun naturalComparator(): Comparator<String> = Comparator { a, b -> compareNaturally(a, b) }

    private fun compareNaturally(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ia = i
                while (ia < a.length && a[ia].isDigit()) ia++
                var jb = j
                while (jb < b.length && b[jb].isDigit()) jb++
                val na = a.substring(i, ia).toLongOrNull() ?: 0L
                val nb = b.substring(j, jb).toLongOrNull() ?: 0L
                if (na != nb) return na.compareTo(nb)
                if (ia - i != jb - j) return (ia - i).compareTo(jb - j)
                i = ia
                j = jb
            } else {
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    /** 枚举目录下所有图片文件名（含子目录相对路径，自然排序）。 */
    fun listImageFilesInDir(dir: File): List<String> {
        if (!dir.isDirectory) return emptyList()
        val out = mutableListOf<String>()
        dir.walkTopDown().forEach { f ->
            if (f.isFile && isImageFile(f.name)) {
                out.add(f.relativeTo(dir).path.replace('\\', '/'))
            }
        }
        return sortNaturally(out)
    }
}
