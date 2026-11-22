package org.json

/*
 * 编译期桩：真实的 org.json 由 Android 运行时提供（Android 内置 JSON 实现）。
 * 这里只声明插件可能用到的成员，签名与 Android SDK 完全一致 —— 签名不一致的话，
 * 插件编译能过但运行时 NoSuchMethodError，那是最难查的一类问题。
 *
 * 日常开发加成员的原则：只加 Android 上真实存在的重载，不要造便利方法。
 */
class JSONArray {

    constructor()

    constructor(json: String)

    fun put(value: Any?): JSONArray = this

    fun put(index: Int, value: Any?): JSONArray = this

    fun length(): Int = 0

    fun opt(index: Int): Any? = null

    fun optJSONObject(index: Int): JSONObject? = null

    fun optJSONArray(index: Int): JSONArray? = null

    fun optString(index: Int): String = ""

    fun optString(index: Int, fallback: String): String = fallback

    fun optInt(index: Int): Int = 0

    fun optInt(index: Int, fallback: Int): Int = fallback

    fun optLong(index: Int): Long = 0L

    fun optLong(index: Int, fallback: Long): Long = fallback

    fun optDouble(index: Int): Double = 0.0

    fun optDouble(index: Int, fallback: Double): Double = fallback

    fun optBoolean(index: Int): Boolean = false

    fun optBoolean(index: Int, fallback: Boolean): Boolean = fallback

    fun getJSONObject(index: Int): JSONObject = JSONObject()

    fun getJSONArray(index: Int): JSONArray = JSONArray()

    fun getString(index: Int): String = ""

    fun getInt(index: Int): Int = 0

    fun getLong(index: Int): Long = 0L

    fun getBoolean(index: Int): Boolean = false

    fun isNull(index: Int): Boolean = false

    fun remove(index: Int): Any? = null

    override fun toString(): String = "[]"

    fun toString(indentSpaces: Int): String = "[]"
}
