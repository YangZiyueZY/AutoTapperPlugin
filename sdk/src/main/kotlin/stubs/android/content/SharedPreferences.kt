package android.content

/*
 * 编译期桩：真实的 SharedPreferences 由 Android 运行时提供。
 * 只声明宿主 API 用到的成员，签名与 Android SDK 保持一致。
 * 本文件被 sdk/build.gradle 的 jar 任务排除，不会出现在任何发布物里。
 */
interface SharedPreferences {

    fun getAll(): MutableMap<String, *>

    fun getString(key: String, defValue: String?): String?

    fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>?

    fun getInt(key: String, defValue: Int): Int

    fun getLong(key: String, defValue: Long): Long

    fun getFloat(key: String, defValue: Float): Float

    fun getBoolean(key: String, defValue: Boolean): Boolean

    fun contains(key: String?): Boolean

    fun edit(): Editor

    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?)

    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?)

    interface Editor {
        fun putString(key: String, value: String?): Editor

        fun putStringSet(key: String, values: MutableSet<String>?): Editor

        fun putInt(key: String, value: Int): Editor

        fun putLong(key: String, value: Long): Editor

        fun putFloat(key: String, value: Float): Editor

        fun putBoolean(key: String, value: Boolean): Editor

        fun remove(key: String): Editor

        fun clear(): Editor

        fun commit(): Boolean

        fun apply()
    }

    interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?)
    }
}
