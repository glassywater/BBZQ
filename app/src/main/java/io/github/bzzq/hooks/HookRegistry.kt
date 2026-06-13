package io.github.bzzq.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import kotlin.io.use

object HookRegistry {
    private val targetPackageNames = setOf(
        "tv.danmaku.bili",
        "com.bilibili.app.in",
        "tv.danmaku.bilibilihd",
        "com.bilibili.app.blue",
    )

    private val hookFactories: List<(String) -> BaseHook> = listOf(
        ::PackageLoadLogHook,
        ::HostResponseHook,
        ::VideoFeatureUnlockHook,
        ::AutoLikeVideoDetailHook,
        ::BlockLiveReservationHook,
        ::LiveQualityHook,
        ::StoryVideoAdHook,
        ::MiniGameRewardAdHook,
        ::BiliEntryHook,
        ::MinePageEntryHook,
        ::AccessKeyCaptureHook,
        ::FreeCopyHook,
        ::SharePurifyHook,
        ::FullNumberFormatHook,
        ::UnlockCommentGifHook,
        ::SkipVideoAdHook,
    )

    fun handlePackageReady(
        xposed: XposedInterface,
        packageReady: PackageReadyParam,
        log: (String, Throwable?) -> Unit,
    ) {
        val packageName = packageReady.getPackageName()
        if (packageName !in targetPackageNames) return

        val matchingHooks = hookFactories.map { factory -> factory(packageName) }

        log("Installing ${matchingHooks.size} hook(s) for $packageName", null)
        HookContext(xposed, packageReady, log).use { context ->
            matchingHooks.forEach { hook ->
                runCatching { hook.install(context) }
                    .onFailure { log("Hook failed for $packageName", it) }
            }
            matchingHooks.forEach { hook ->
                runCatching { hook.lateInitHook() }
                    .onFailure { log("Late hook failed for $packageName", it) }
            }
        }
    }
}
