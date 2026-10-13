package com.moe.starflow.library

import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/** Only journal/asset integrity is hashed. Original books are never content-fingerprinted. */
internal object LibraryCodec {
    fun encode(value: Any?, location: String): Any = when (value) {
        null -> JSONObject.NULL
        is String -> if (value.startsWith("$location:") && value.substringAfterLast(':').matches(UUID_PATTERN))
            JSONObject().put("bookKey", value.substringAfterLast(':')).put("kind", value.removePrefix("$location:").substringBefore(':', "")) else value
        is Number, is Boolean -> value
        is IntArray -> JSONArray(value.toList())
        is Iterable<*> -> JSONArray().also { array -> value.forEach { array.put(encode(it, location)) } }
        else -> JSONObject().also { json -> fields(value.javaClass).forEach { field ->
            field.isAccessible = true
            json.put(field.name, encode(field.get(value), location))
        } }
    }
    fun decode(value: Any?, type: Type, location: String): Any? {
        if (value == null || value === JSONObject.NULL) return null
        if (type is ParameterizedType) {
            val array = value as JSONArray
            return (0 until array.length()).map { decode(array.get(it), type.actualTypeArguments[0], location) }
        }
        val cls = type as Class<*>
        if (cls == String::class.java) return if (value is JSONObject && value.has("bookKey"))
            "$location:${value.optString("kind").let { if (it.isEmpty()) "" else "$it:" }}${value.getString("bookKey")}" else value.toString()
        if (cls == java.lang.Long.TYPE || cls == java.lang.Long::class.java) return (value as Number).toLong()
        if (cls == java.lang.Integer.TYPE || cls == java.lang.Integer::class.java) return (value as Number).toInt()
        if (cls == java.lang.Boolean.TYPE || cls == java.lang.Boolean::class.java) return value as Boolean
        if (cls == IntArray::class.java) return (value as JSONArray).let { a -> IntArray(a.length()) { a.getInt(it) } }
        val json = value as JSONObject
        // ART does not guarantee declaredFields order. Journal constructor arguments
        // must follow the portable schema, independent of JVM/Dex field layout.
        val members = constructorFields(cls).map { cls.getDeclaredField(it) }
        val constructor = cls.declaredConstructors.single { !it.isSynthetic && it.parameterCount == members.size }
        return constructor.newInstance(*members.map { decode(json.opt(it.name), it.genericType, location) }.toTypedArray())
    }
    private fun constructorFields(cls: Class<*>): List<String> = when (cls) {
        com.moe.starflow.data.ImportedPageTranslation::class.java -> listOf(
            "mangaId", "pageIndex", "state", "sourceText", "translatedText", "bubbleRects",
            "failCode", "failMessage", "updatedAtMs", "mangaKey", "translatorName", "sourceLang", "targetLang", "ocrModel")
        com.moe.starflow.data.ImportedPageSr::class.java -> listOf(
            "mangaId", "pageIndex", "state", "modelName", "srcWidth", "srcHeight", "srcBytes",
            "outWidth", "outHeight", "outBytes", "failCode", "failMessage", "startedAtMs", "finishedAtMs",
            "updatedAtMs", "mangaKey", "sourcePageKey", "executionJson")
        com.moe.starflow.data.NovelParagraphTranslation::class.java -> listOf(
            "novelId", "novelKey", "chapterIndex", "paraIndex", "sourceText", "translatedText", "state",
            "failCode", "translatorName", "sourceLang", "targetLang", "splitVersion", "updatedAt", "imagePayload")
        else -> throw IllegalArgumentException("Unsupported library record: ${cls.name}")
    }
    private fun fields(cls: Class<*>) = cls.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.sortedBy { it.name }
    val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    fun digest(input: java.io.InputStream): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun digest(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
