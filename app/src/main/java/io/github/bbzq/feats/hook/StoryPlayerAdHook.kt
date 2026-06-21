package io.github.bbzq.feats.hook

import android.content.SharedPreferences
import io.github.bbzq.ModuleSettings
import io.github.bbzq.ModuleSettingsBridge
import io.github.bbzq.StoryVideoAdTag
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.MethodHookParam
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.from
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.replace
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class StoryPlayerAdHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName) return
        val enabled = ModuleSettings.isPurifyStoryVideoAdEnabled(prefs)
        if (!enabled) {
            log("startHook: StoryPlayerAd disabled, provider=${ModuleSettingsBridge.lastProviderStatus}")
            return
        }

        var hookCount = 0
        hookCount += installStoryFeedResponseHook()
        hookCount += installStoryPagerPlayerHook()
        hookCount += installStoryAdRerankHook()
        if (hookCount == 0) {
            log("startHook: StoryPlayerAd no hook point found")
        }
    }

    private fun installStoryFeedResponseHook(): Int {
        val storyFeedResponse = STORY_FEED_RESPONSE.from(classLoader) ?: return 0
        val getItems = storyFeedResponse.declaredMethods.firstOrNull {
            it.name == "getItems" &&
                it.parameterCount == 0 &&
                List::class.java.isAssignableFrom(it.returnType) &&
                !Modifier.isStatic(it.modifiers) &&
                !Modifier.isAbstract(it.modifiers)
        }
            ?: return 0

        env.hookAfter(getItems) { param ->
            val result = filterReturnList(param)
            if (result != null) {
                log(
                    "StoryPlayerAd removed ${result.removed} item(s) " +
                        "reasons=${result.reasonSummary()} " +
                        "from ${getItems.declaringClass.name}.${getItems.name}",
                )
            }
        }
        log("startHook: StoryPlayerAd at ${getItems.declaringClass.name}.${getItems.name}")
        return 1
    }

    private fun installStoryPagerPlayerHook(): Int {
        val storyPagerPlayer = "com.bilibili.video.story.player.StoryPagerPlayer".from(classLoader)
            ?: return 0
        val methods = storyPagerPlayer.declaredMethods
            .filter(::isStoryListMethod)
            .distinctBy(Method::toGenericString)
            .toList()

        methods.forEach { method ->
            env.hookBefore(method) { param ->
                val result = filterArgumentList(param, 0)
                if (result != null) {
                    log(
                        "StoryPlayerAd removed ${result.removed} item(s) " +
                            "reasons=${result.reasonSummary()} " +
                            "from ${method.declaringClass.name}.${method.name}",
                    )
                }
            }
        }
        if (methods.isNotEmpty()) {
            log(
                "startHook: StoryPlayerAd at ${storyPagerPlayer.name}, " +
                    "methods=${methods.joinToString(",") { it.name }}",
            )
        }
        return methods.size
    }

    private fun installStoryAdRerankHook(): Int {
        val rerankTask = STORY_AD_RERANK_TASK.from(classLoader) ?: return 0
        val invokeSuspend = rerankTask.declaredMethods.firstOrNull {
            it.name == "invokeSuspend" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == Any::class.java &&
                it.returnType == Any::class.java &&
                !Modifier.isStatic(it.modifiers) &&
                !Modifier.isAbstract(it.modifiers)
        }
            ?: return 0
        val unit = KOTLIN_UNIT.from(classLoader)
            ?.getDeclaredField("INSTANCE")
            ?.apply { isAccessible = true }
            ?.get(null)
            ?: return 0

        env.replace(invokeSuspend) {
            log("StoryPlayerAd disabled story ad rerank request")
            unit
        }
        log("startHook: StoryPlayerAd at ${invokeSuspend.declaringClass.name}.${invokeSuspend.name}")
        return 1
    }

    private fun isStoryListMethod(method: Method): Boolean =
        method.returnType == Void.TYPE &&
            method.declaringClass.name == STORY_PAGER_PLAYER &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            !method.isSynthetic &&
            !method.isBridge &&
            method.parameterCount == 1 &&
            List::class.java.isAssignableFrom(method.parameterTypes[0]) &&
            isStoryDetailListParameter(method)

    private fun isStoryDetailListParameter(method: Method): Boolean {
        val parameterType = method.genericParameterTypes.firstOrNull()?.typeName ?: return true
        return parameterType == List::class.java.name || parameterType.contains(STORY_DETAIL)
    }

    private fun filterReturnList(param: MethodHookParam): FilterResult? {
        val items = (param.result as? List<*>)?.map { it } ?: return null
        val result = filteredStoryList(items)
        if (result.removed == 0) return null
        param.result = result.items
        incrementBlockedCount(prefs, result.removed)
        return result
    }

    private fun filterArgumentList(param: MethodHookParam, index: Int): FilterResult? {
        val items = (param.args.getOrNull(index) as? List<*>)?.map { it } ?: return null
        val result = filteredStoryList(items)
        if (result.removed == 0) return null
        param.args[index] = result.items
        incrementBlockedCount(prefs, result.removed)
        return result
    }

    private fun filteredStoryList(items: List<Any?>): FilterResult {
        val selectedTags = ModuleSettings.getPurifyStoryVideoAdTags(prefs)
        if (selectedTags.isEmpty()) return FilterResult(items, 0)

        val tags = ModuleSettings.storyVideoAdTags.associateBy { it.key }
        val filtered = ArrayList<Any?>(items.size)
        val reasons = linkedMapOf<String, Int>()
        var removed = 0
        items.forEach { item ->
            val reason = removeReason(item, selectedTags, tags)
            if (reason != null) {
                removed += 1
                reasons[reason] = (reasons[reason] ?: 0) + 1
            } else {
                filtered += item
            }
        }
        return FilterResult(filtered, removed, reasons)
    }

    private fun removeReason(
        item: Any?,
        selectedTags: Set<String>,
        tags: Map<String, StoryVideoAdTag>,
    ): String? {
        if (item == null) return null
        if (item.javaClass.name != STORY_DETAIL) return null
        if ("ad" in selectedTags && isStoryAd(item)) return "ad"
        val entryText = readCartEntryText(item) ?: return null
        return selectedTags.firstOrNull { key -> tags[key]?.cartText == entryText }
            ?.let { "tag:$it" }
    }

    private fun isStoryAd(item: Any): Boolean =
        runCatching {
            item.javaClass.getDeclaredMethod("isAd").apply { isAccessible = true }.invoke(item) as? Boolean
        }.getOrNull()
            ?: (item.getObjectField("ad") as? Boolean)
            ?: false

    private fun readCartEntryText(item: Any): String? {
        val cart = runCatching {
            item.javaClass.getDeclaredMethod("getCartIconInfo").apply { isAccessible = true }.invoke(item)
        }.getOrNull() ?: item.getObjectField("cartIconInfo")
        return cart?.let {
            runCatching {
                it.javaClass.getDeclaredMethod("getEntryText").apply { isAccessible = true }.invoke(it) as? String
            }.getOrNull() ?: it.getObjectField("entryText")?.toString()
        }
    }

    private fun incrementBlockedCount(prefs: SharedPreferences, count: Int) {
        val oldValue = prefs.getInt(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_BLOCKED_COUNT, 0)
        prefs.edit().putInt(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_BLOCKED_COUNT, oldValue + count).apply()
    }

    private data class FilterResult(
        val items: List<Any?>,
        val removed: Int,
        val reasons: Map<String, Int> = emptyMap(),
    ) {
        fun reasonSummary(): String =
            reasons.entries.joinToString(",") { (reason, count) -> "$reason:$count" }
    }

    private companion object {
        private const val STORY_PAGER_PLAYER = "com.bilibili.video.story.player.StoryPagerPlayer"
        private const val STORY_FEED_RESPONSE = "com.bilibili.video.story.api.StoryFeedResponse"
        private const val STORY_AD_RERANK_TASK =
            "com.bilibili.video.story.action.service.StoryAdReRankService\$2"
        private const val STORY_DETAIL = "com.bilibili.video.story.StoryDetail"
        private const val KOTLIN_UNIT = "kotlin.Unit"
    }
}

