package io.github.bqj6666.ohsync.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Executable

internal const val TAG = "OHSyncHook"

/**
 * LSPosed 模块入口。类名必须与 META-INF/xposed/java_init.list 一致，改名会加载失败。
 *
 * XposedModule 只有无参构造器（模块 id 由 module.prop 提供），
 * 所以这里不带参数，只做编排：捕获数据库 -> 扫表 -> 建映射 -> 推送。
 */
class OHSyncEntry : XposedModule(), XposedModuleInterface {

    private val coordinator = HookCoordinator(this)

    /** 阶段一：App 创建之前只装 hook，赶在 OPPO 自己开库之前。 */
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE) return
        coordinator.onPackageLoaded(param.defaultClassLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // 作用域已由 module.prop 的 staticScope + scope.list 限定，这里再挡一道，
        // 防止用户手动改作用域把模块塞进别的 App。
        if (param.packageName != TARGET_PACKAGE) return
        Log.i(TAG, "package ready: ${param.packageName}")
        coordinator.onPackageReady(param.classLoader)
    }

    companion object {
        const val TARGET_PACKAGE = "com.heytap.health"
    }
}

/**
 * 在方法上挂一个「只观察不改变行为」的 hook。
 * 异常一律吞掉并记日志：hook 崩了绝不能连带把 OPPO 健康带崩。
 */
internal fun XposedInterface.observeHook(exec: Executable, id: String, block: (List<Any?>) -> Unit) {
    try {
        hook(exec).setId("ohsync:$id")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        block(chain.args)
                    } catch (t: Throwable) {
                        Log.e(TAG, "observe $id failed", t)
                    }
                    return chain.proceed()
                }
            })
    } catch (t: Throwable) {
        Log.e(TAG, "cannot hook $id", t)
    }
}

/**
 * 捕获方法**返回值**的 hook。
 *
 * 与 observeHook 的区别：observeHook 只能看入参，看不到返回的数据库实例，
 * 而我们要的正是那个返回值本身。
 */
internal fun XposedInterface.captureReturn(exec: Executable, id: String, onReturn: (Any?) -> Unit) {
    try {
        hook(exec).setId("ohsync:$id")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    try {
                        onReturn(result)
                    } catch (t: Throwable) {
                        Log.e(TAG, "captureReturn $id failed", t)
                    }
                    return result
                }
            })
    } catch (t: Throwable) {
        Log.e(TAG, "cannot hook return $id", t)
    }
}
