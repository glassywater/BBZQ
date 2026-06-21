package io.github.bbzq.feats

import android.content.Context
import android.content.SharedPreferences
import io.github.bbzq.ModuleSettingsBridge
import io.github.bbzq.feats.hook.BottomBarHook
import io.github.bbzq.feats.hook.AutoLikeHook
import io.github.bbzq.feats.hook.AccessKeyHook
import io.github.bbzq.feats.hook.ChronosPromotionHook
import io.github.bbzq.feats.hook.DownloadThreadHook
import io.github.bbzq.feats.hook.TeenagersModeHook
import io.github.bbzq.feats.hook.TryFreeQualityHook
import io.github.bbzq.feats.hook.FreeCopyHook
import io.github.bbzq.feats.hook.HomeRecommendAdHook
import io.github.bbzq.feats.hook.HomeRecommendAutoRefreshHook
import io.github.bbzq.feats.hook.HomeComponentHideHook
import io.github.bbzq.feats.hook.RewardAdHook
import io.github.bbzq.feats.hook.SettingHook
import io.github.bbzq.feats.hook.ShareHook
import io.github.bbzq.feats.hook.SkipVideoAdHook
import io.github.bbzq.feats.hook.SkipVideoAdProgressHook
import io.github.bbzq.feats.hook.SplashAdHook
import io.github.bbzq.feats.hook.StoryPlayerAdHook
import io.github.bbzq.feats.hook.VideoCommentHook
import io.github.bbzq.feats.hook.VideoDetailBannerAdHook
import io.github.bbzq.feats.hook.MineProfileHook
import io.github.libxposed.api.XposedInterface

object RoamingRuntime {
    fun start(
        xposed: XposedInterface,
        packageName: String,
        processName: String,
        application: Context,
        classLoader: ClassLoader,
        log: (String, Throwable?) -> Unit,
    ) {
        val env = RoamingEnv(
            xposed = xposed,
            packageName = packageName,
            processName = processName,
            hostContext = application.applicationContext ?: application,
            classLoader = classLoader,
            logger = log,
        )

        env.log("BBZQ runtime starting for $packageName/$processName")
        ModuleSettingsBridge.attach(env.hostContext, xposed)

        val hooks = when {
            processName.endsWith(":web") -> listOf(
                ::ShareHook,
                ::RewardAdHook,
            )

            processName.endsWith(":download") -> listOf(
                ::DownloadThreadHook,
            )

            else -> listOf(
                ::SettingHook,
                ::SplashAdHook,
                ::ShareHook,
                ::FreeCopyHook,
                ::BottomBarHook,
                ::HomeRecommendAdHook,
                ::HomeRecommendAutoRefreshHook,
                ::HomeComponentHideHook,
                ::StoryPlayerAdHook,
                ::VideoDetailBannerAdHook,
                ::TryFreeQualityHook,
                ::ChronosPromotionHook,
                ::SkipVideoAdHook,
                ::SkipVideoAdProgressHook,
                ::RewardAdHook,
                ::AutoLikeHook,
                ::AccessKeyHook,
                ::TeenagersModeHook,
                ::VideoCommentHook,
                ::MineProfileHook,
            )
        }

        hooks.forEach { factory ->
            val hook = factory(env)
            runCatching { hook.startHook() }
                .onFailure { env.log("Hook failed: ${hook.javaClass.simpleName}", it) }
        }

        env.log("BBZQ runtime installed ${hooks.size} hook(s)")
    }
}

class RoamingEnv(
    val xposed: XposedInterface,
    val packageName: String,
    val processName: String,
    val hostContext: Context,
    val classLoader: ClassLoader,
    private val logger: (String, Throwable?) -> Unit,
) {
    val prefs: SharedPreferences
        get() = ModuleSettingsBridge.instance

    fun log(message: String, throwable: Throwable? = null) {
        logger(message, throwable)
    }

    companion object
}

abstract class BaseRoamingHook(
    protected val env: RoamingEnv,
) {
    protected val xposed: XposedInterface
        get() = env.xposed

    protected val classLoader: ClassLoader
        get() = env.classLoader

    protected val prefs: SharedPreferences
        get() = env.prefs

    protected fun log(message: String, throwable: Throwable? = null) {
        env.log(message, throwable)
    }

    abstract fun startHook()
}

