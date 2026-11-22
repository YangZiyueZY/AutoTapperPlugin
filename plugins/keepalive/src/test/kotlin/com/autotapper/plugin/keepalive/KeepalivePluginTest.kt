package com.autotapper.plugin.keepalive

import com.autotapper.testkit.FakeContext
import com.example.autotapper.plugin.PluginApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** keepalive 骨架插件测试：无步骤类型时也要保证身份与 API 版本不跑偏。 */
class KeepalivePluginTest {

    @Test(timeout = 10_000)
    fun 插件身份与契约版本正确() {
        val plugin = KeepalivePlugin()
        assertEquals("keepalive", plugin.id)
        assertEquals(PluginApi.VERSION, plugin.apiVersion)
        assertTrue(plugin.name.isNotBlank())
        assertTrue(plugin.minHostVersionCode >= 1)
    }

    @Test(timeout = 10_000)
    fun 骨架阶段没有步骤类型也不改变宿主行为() {
        val plugin = KeepalivePlugin()
        assertTrue(plugin.stepTypes().isEmpty())
        assertTrue(plugin.extensions().isEmpty())
        // 假宿主可正常构造：插件不依赖任何能力就能被装载
        val ctx = FakeContext()
        assertEquals("fake-plugin", ctx.pluginId)
    }
}
