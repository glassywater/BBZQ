package io.github.bzzq.hooks

import android.content.SharedPreferences
import io.github.bzzq.ModuleSettings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Field

/**
 * Hook to unlock video features such as trial quality and VIP-only streams.
 * Re-implemented based on the functionality of the TQSA project.
 */
class VideoFeatureUnlockHook(
    override val targetPackageName: String,
) : AppHook {

    override fun install(xposed: XposedInterface, packageReady: PackageReadyParam, log: (String, Throwable?) -> Unit) {
        val classLoader = packageReady.getClassLoader()
        val prefs = xposed.getRemotePreferences(ModuleSettings.PREFS_NAME)

        // 1. Enable trial quality in SceneControl
        hookSetBoolean(xposed, classLoader, SCENE_CONTROL_CLASS, SET_IS_NEED_TRIAL, true, prefs, log)
        hookGetBoolean(xposed, classLoader, SCENE_CONTROL_CLASS, GET_IS_NEED_TRIAL, true, prefs, log)

        // 2. Enable trial quality in VideoVod
        hookSetBoolean(xposed, classLoader, VIDEO_VOD_CLASS, SET_IS_NEED_TRIAL, true, prefs, log)
        hookGetBoolean(xposed, classLoader, VIDEO_VOD_CLASS, GET_IS_NEED_TRIAL, true, prefs, log)

        // 3. Bypass VIP requirements in StreamInfo (various versions)
        STREAM_INFO_CLASSES.forEach { className ->
            hookRedirectFieldToMethod(xposed, classLoader, className, GET_VIP_FREE, NEED_VIP_FIELD, prefs, log)
            hookGetBoolean(xposed, classLoader, className, GET_NEED_VIP, false, prefs, log)
        }
    }

    private fun hookGetBoolean(
        xposed: XposedInterface,
        cl: ClassLoader,
        className: String,
        methodName: String,
        fixedValue: Boolean,
        prefs: SharedPreferences,
        log: (String, Throwable?) -> Unit
    ) {
        runCatching {
            val clazz = Class.forName(className, false, cl)
            val method = clazz.getDeclaredMethod(methodName)
            xposed.hook(method).intercept { chain ->
                if (ModuleSettings.isUnlockVideoFeaturesEnabled(prefs)) fixedValue else chain.proceed()
            }
            log("Installed constant return hook: $className.$methodName() -> $fixedValue", null)
        }.onFailure {
            // Silently fail if class or method is not found
        }
    }

    private fun hookSetBoolean(
        xposed: XposedInterface,
        cl: ClassLoader,
        className: String,
        methodName: String,
        forcedValue: Boolean,
        prefs: SharedPreferences,
        log: (String, Throwable?) -> Unit
    ) {
        runCatching {
            val clazz = Class.forName(className, false, cl)
            val method = clazz.getDeclaredMethod(methodName, Boolean::class.javaPrimitiveType)
            xposed.hook(method).intercept { chain ->
                if (ModuleSettings.isUnlockVideoFeaturesEnabled(prefs)) {
                    chain.proceed(arrayOf(forcedValue))
                } else {
                    chain.proceed()
                }
            }
            log("Installed argument force hook: $className.$methodName($forcedValue)", null)
        }.onFailure {
            // Silently fail
        }
    }

    private fun hookRedirectFieldToMethod(
        xposed: XposedInterface,
        cl: ClassLoader,
        className: String,
        methodName: String,
        fieldName: String,
        prefs: SharedPreferences,
        log: (String, Throwable?) -> Unit
    ) {
        runCatching {
            val clazz = Class.forName(className, false, cl)
            val method = clazz.getDeclaredMethod(methodName)
            val field = findField(clazz, fieldName)?.apply { isAccessible = true } ?: return@runCatching
            xposed.hook(method).intercept { chain ->
                val instance = chain.thisObject
                if (ModuleSettings.isUnlockVideoFeaturesEnabled(prefs) && instance != null) {
                    field.get(instance)
                } else {
                    chain.proceed()
                }
            }
            log("Installed field redirection hook: $className.$methodName() -> field $fieldName", null)
        }.onFailure {
            // Silently fail
        }
    }

    private fun findField(clazz: Class<*>, fieldName: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching {
                return current.getDeclaredField(fieldName)
            }
            current = current.superclass
        }
        return null
    }

    private companion object {
        private const val SCENE_CONTROL_CLASS = "com.bapis.bilibili.pgc.gateway.player.v2.SceneControl"
        private const val VIDEO_VOD_CLASS = "com.bapis.bilibili.playershared.VideoVod"
        private val STREAM_INFO_CLASSES = listOf(
            "com.bapis.bilibili.app.playurl.v1.StreamInfo",
            "com.bapis.bilibili.playershared.StreamInfo"
        )

        private const val SET_IS_NEED_TRIAL = "setIsNeedTrial"
        private const val GET_IS_NEED_TRIAL = "getIsNeedTrial"
        private const val GET_VIP_FREE = "getVipFree"
        private const val GET_NEED_VIP = "getNeedVip"
        private const val NEED_VIP_FIELD = "needVip_"
    }
}
