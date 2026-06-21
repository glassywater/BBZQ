package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.ModuleSettingsBridge
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allFields
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.from
import io.github.bbzq.feats.hookBefore
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class HomeRecommendAutoRefreshHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private var blockedCount = 0
    private var blockedColdStartCount = 0

    override fun startHook() {
        if (env.processName != env.packageName) return
        val enabled = ModuleSettings.isBlockHomeRecommendAutoRefreshEnabled(prefs)
        if (!enabled) {
            log("startHook: HomeRecommendAutoRefresh disabled, provider=${ModuleSettingsBridge.lastProviderStatus}")
            return
        }

        val componentClass = AUTO_REFRESH_COMPONENT.from(classLoader)
        val requestManagerClass = PEGASUS_REQUEST_MANAGER.from(classLoader)
        val flushClass = PEGASUS_FLUSH.from(classLoader)
        val resourceClass = RESOURCE.from(classLoader)
        if (
            componentClass == null ||
            requestManagerClass == null ||
            flushClass == null ||
            resourceClass == null
        ) {
            log(
                "startHook: HomeRecommendAutoRefresh missing " +
                    "component=$componentClass requestManager=$requestManagerClass " +
                    "flush=$flushClass resource=$resourceClass",
            )
            return
        }

        val autoRefreshMethod = componentClass.declaredMethods
            .firstOrNull {
                it.parameterCount == 1 &&
                    it.parameterTypes[0] == flushClass &&
                    it.returnType == Void.TYPE &&
                    !Modifier.isStatic(it.modifiers) &&
                    !Modifier.isAbstract(it.modifiers)
            }
        if (autoRefreshMethod == null) {
            log("startHook: HomeRecommendAutoRefresh no hook point found")
            return
        }

        val requestMethods = requestManagerClass.allMethods()
            .filter {
                it.parameterCount in 3..4 &&
                    it.returnType == Any::class.java &&
                    Modifier.isStatic(it.modifiers) &&
                    requestParamSymbols(it.parameterTypes[0], flushClass)?.hasRequestParamFields() == true
            }
            .toList()
        val requestParamClass = requestMethods.map { it.parameterTypes[0] }.distinct().singleOrNull()
        val requestSymbols = requestParamSymbols(requestParamClass, flushClass)?.copy(
            resourceError = resourceClass.declaredMethods.firstOrNull {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 1 &&
                    Throwable::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                    resourceClass.isAssignableFrom(it.returnType)
            },
        ) ?: RequestSymbols(null, null, null, null)
        val resourceError = requestSymbols.resourceError
        if (!requestSymbols.hasRequestParamFields() || resourceError == null) {
            log(
                "startHook: HomeRecommendAutoRefresh no cold request hook point found " +
                    "methods=${requestMethods.size} requestParam=$requestParamClass idx=${requestSymbols.idxField} " +
                    "refresh=${requestSymbols.refreshField} flush=${requestSymbols.flushField} " +
                    "error=$resourceError",
            )
            return
        }

        env.hookBefore(autoRefreshMethod) { param ->
            val flushName = (param.args.firstOrNull() as? Enum<*>)?.name ?: return@hookBefore
            if (flushName !in BLOCKED_FLUSHES) return@hookBefore
            param.result = null
            logBlocked(flushName)
        }
        requestMethods.forEach { method ->
            env.hookBefore(method) { param ->
                val requestParam = param.args.firstOrNull() ?: return@hookBefore
                if (!requestSymbols.isColdStartNormalRefresh(requestParam)) return@hookBefore
                param.result = resourceError.invoke(
                    null,
                    BlockColdStartRefreshException(),
                )
                logBlockedColdStart()
            }
        }
        log(
            "startHook: HomeRecommendAutoRefresh at " +
                "${autoRefreshMethod.declaringClass.name}.${autoRefreshMethod.name}, " +
                "coldStartRequest=${requestMethods.joinToString { it.name }}",
        )
    }

    private fun logBlocked(flushName: String) {
        val count = ++blockedCount
        if (count <= 20 || count % 20 == 0) {
            log("HomeRecommendAutoRefresh blocked $flushName count=$count")
        }
    }

    private fun logBlockedColdStart() {
        val count = ++blockedColdStartCount
        if (count <= 20 || count % 20 == 0) {
            log("HomeRecommendAutoRefresh blocked cold start request count=$count")
        }
    }

    private fun requestParamSymbols(requestParamClass: Class<*>?, flushClass: Class<*>): RequestSymbols? {
        if (requestParamClass == null) return null
        val fields = requestParamClass.allFields().toList()
        return RequestSymbols(
            idxField = fields.firstOrNull { it.type == Long::class.javaPrimitiveType },
            refreshField = fields.singleOrNull { it.type == Boolean::class.javaPrimitiveType },
            flushField = fields.singleOrNull { it.type == flushClass },
            resourceError = null,
        )
    }

    private data class RequestSymbols(
        val idxField: Field?,
        val refreshField: Field?,
        val flushField: Field?,
        val resourceError: Method?,
    ) {
        fun hasRequestParamFields(): Boolean =
            idxField != null && refreshField != null && flushField != null

        fun isColdStartNormalRefresh(requestParam: Any): Boolean {
            val idx = runCatching { idxField?.getLong(requestParam) }.getOrNull() ?: return false
            val refresh = runCatching { refreshField?.getBoolean(requestParam) }.getOrNull() ?: return false
            val flushName = (runCatching { flushField?.get(requestParam) }.getOrNull() as? Enum<*>)?.name
                ?: return false
            return idx == 0L && refresh && flushName == NORMAL_FLUSH
        }
    }

    private class BlockColdStartRefreshException : RuntimeException("BBZQ blocked cold start refresh")

    private companion object {
        private const val AUTO_REFRESH_COMPONENT = "com.bilibili.pegasus.components.AutoRefreshComponent"
        private const val PEGASUS_REQUEST_MANAGER = "com.bilibili.pegasus.request.c"
        private const val PEGASUS_FLUSH = "com.bilibili.pegasus.data.request.PegasusFlush"
        private const val RESOURCE = "com.bilibili.lib.arch.lifecycle.Resource"
        private const val NORMAL_FLUSH = "NORMAL"
        private val BLOCKED_FLUSHES = setOf(
            "AUTO_BACK_FROM_BACKGROUND",
            "AUTO_BACK_FROM_BEHAVIOR",
            "AUTO_BACK_FROM_OTHER_PAGE",
        )
    }
}

