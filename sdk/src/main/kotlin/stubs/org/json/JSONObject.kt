package org.json

/*
 * 编译期桩：真实的 org.json 由 Android 运行时提供（Android 内置，不额外引第三方库）。
 * 只声明插件写 config 会用到的成员，签名与 Android SDK 一致。
 * 本文件被 sdk/build.gradle 的 jar 任务排除，不会出现在任何发布物里。
 */
open class JSONObject {

    constructor()

    constructor(json: String)

    fun put(name: String, value: Any?): JSONObject = this

    fun putOpt(name: String, value: Any?): JSONObject = this

    fun accumulate(name: String, value: Any?): JSONObject = this

    fun remove(name: String): Any? = null

    fun has(name: String): Boolean = false

    fun isNull(name: String): Boolean = false

    fun keys(): MutableIterator<String> = mutableListOf<String>().iterator()

    fun length(): Int = 0

    fun names(): JSONArray? = null

    fun opt(name: String): Any? = null

    fun optString(name: String): String = ""

    fun optString(name: String, fallback: String): String = fallback

    fun optInt(name: String): Int = 0

    fun optInt(name: String, fallback: Int): Int = fallback

    fun optLong(name: String): Long = 0L

    fun optLong(name: String, fallback: Long): Long = fallback

    fun optDouble(name: String): Double = 0.0

    fun optDouble(name: String, fallback: Double): Double = fallback

    fun optBoolean(name: String): Boolean = false

    fun optBoolean(name: String, fallback: Boolean): Boolean = fallback

    fun optJSONObject(name: String): JSONObject? = null

    fun optJSONArray(name: String): JSONArray? = null

    fun getString(name: String): String = ""

    fun getInt(name: String): Int = 0

    fun getLong(name: String): Long = 0L

    fun getBoolean(name: String): Boolean = false

    fun getJSONObject(name: String): JSONObject = JSONObject()

    fun getJSONArray(name: String): JSONArray = JSONArray()

    override fun toString(): String = "{}"

    fun toString(indentSpaces: Int): String = "{}"

    companion object {
        @JvmField
        val NULL: Any = Any()
    }
}
