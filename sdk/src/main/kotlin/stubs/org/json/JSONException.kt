package org.json

/*
 * 编译期桩：真实的 org.json 由 Android 运行时提供。
 * 本文件被 sdk/build.gradle 的 jar 任务排除，不会出现在任何发布物里。
 */
class JSONException(message: String?) : Exception(message)
