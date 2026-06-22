package io.github.bbzq.feats.hook

import android.view.View
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.from
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.getObjectField

class MineProfileHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName) return

        var count = 0
        count += hookVipEntrance()
        log("startHook: MineProfile, methods=$count")
    }

    private fun hookVipEntrance(): Int {
        val vipViewClass = resolveMineVipViewClass() ?: return 0
        val fragmentClass = resolveHomeUserCenterFragmentClass() ?: return 0
        val vipField = fragmentClass.declaredFields.firstOrNull { vipViewClass.isAssignableFrom(it.type) }
            ?.apply { isAccessible = true }
            ?: return 0
        val onResume = fragmentClass.declaredMethods.firstOrNull { it.name == "onResume" && it.parameterCount == 0 } ?: return 0

        env.hookBefore(onResume) { param ->
            runCatching {
                if (!ModuleSettings.isMineRemoveVipEnabled(prefs)) return@runCatching
                val fragment = param.thisObject ?: return@runCatching
                val vipView = vipField.get(fragment) as? View ?: return@runCatching
                vipView.visibility = if (ModuleSettings.isMineKeepVipSpaceEnabled(prefs)) {
                    View.INVISIBLE
                } else {
                    View.GONE
                }
            }.onFailure {
                log("MineProfile vip hook failed at ${onResume.declaringClass.name}.${onResume.name}", it)
            }
        }
        return 1
    }

    private fun resolveHomeUserCenterFragmentClass(): Class<*>? =
        HOME_USER_CENTER_FRAGMENT_CLASSES.firstNotNullOfOrNull { it.from(classLoader) }
            ?: discoverClassesBySimpleName("HomeUserCenterFragment")
                .firstOrNull { candidate ->
                    candidate.declaredMethods.any { method ->
                        method.parameterTypes.any { it == android.content.Context::class.java } &&
                            method.parameterTypes.any { java.util.List::class.java.isAssignableFrom(it) }
                    }
                }

    private fun resolveMineVipViewClass(): Class<*>? =
        MINE_VIP_VIEW_CLASSES.firstNotNullOfOrNull { it.from(classLoader) }
            ?: discoverClassesBySimpleName("MineVipEntranceView").firstOrNull()
            ?: discoverClassesBySimpleName("VipEntranceView").firstOrNull()

    private fun discoverClassesBySimpleName(simpleName: String): Sequence<Class<*>> {
        val baseLoader = classLoader as? BaseDexClassLoader ?: return emptySequence()
        val pathList = runCatching {
            BaseDexClassLoader::class.java.getDeclaredField("pathList").apply { isAccessible = true }.get(baseLoader)
        }.getOrNull() ?: return emptySequence()
        val dexElements = runCatching {
            pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }.get(pathList) as? Array<*>
        }.getOrNull() ?: return emptySequence()

        return dexElements.asSequence()
            .mapNotNull { element ->
                val dexFile = runCatching {
                    element?.javaClass?.getDeclaredField("dexFile")?.apply { isAccessible = true }?.get(element)
                }.getOrNull() as? DexFile
                dexFile?.entries()?.asSequence()
            }
            .flatten()
            .distinct()
            .filter { name ->
                name.substringAfterLast('.') == simpleName || name.endsWith(".$simpleName")
            }
            .mapNotNull { name -> runCatching { Class.forName(name, false, classLoader) }.getOrNull() }
    }

    private companion object {
        private val HOME_USER_CENTER_FRAGMENT_CLASSES = arrayOf(
            "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment",
            "tv.danmaku.p9138bili.p9228ui.main2.p9247mine.HomeUserCenterFragment",
        )

        private val MINE_VIP_VIEW_CLASSES = arrayOf(
            "tv.danmaku.bili.ui.main2.mine.widgets.MineVipEntranceView",
            "tv.danmaku.p9138bili.p9228ui.main2.p9247mine.widgets.MineVipEntranceView",
            "tv.danmaku.p9138bili.p9228ui.main2.p9247mine.modularvip.VipEntranceView",
        )
    }
}

private fun java.util.Enumeration<String>.asSequence(): Sequence<String> = sequence {
    while (hasMoreElements()) {
        yield(nextElement())
    }
}
