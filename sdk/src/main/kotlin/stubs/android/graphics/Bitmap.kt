package android.graphics

/*
 * 编译期桩：真实的 Bitmap 由 Android 运行时提供。
 * 插件能调用的只有下面这几个成员（都是 Android 上真实存在的同名方法），
 * 想在 SDK 里加新成员，请确认它在 android.graphics.Bitmap 上也存在。
 * 本文件被 sdk/build.gradle 的 jar 任务排除，不会出现在任何发布物里。
 */
open class Bitmap {

    open val width: Int = 0

    open val height: Int = 0

    open fun getPixel(x: Int, y: Int): Int = 0

    open fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) = Unit

    open fun isRecycled(): Boolean = false

    open fun recycle() = Unit
}
