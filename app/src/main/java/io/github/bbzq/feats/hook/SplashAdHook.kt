package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.from
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.methodsNamed
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class SplashAdHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        val parserMethods = buildList {
            addAll(findGsonParsers())
            addAll(findFastJsonParsers())
            addAll(findKotlinxJsonParsers())
        }.distinctBy(Method::toGenericString)

        parserMethods.forEach { method ->
            env.hookAfter(method) { param ->
                if (!ModuleSettings.isSkipSplashAdEnabled(prefs)) return@hookAfter
                runCatching {
                    dispatch(param.result)
                    dispatchKotlinx(param.args.firstOrNull(), param.result)
                }
                    .onFailure {
                        log("Splash response processor failed at ${method.declaringClass.name}.${method.name}", it)
                    }
            }
        }

        if (parserMethods.isEmpty()) {
            log("startHook: SplashAd, no parser found")
        } else {
            log("startHook: SplashAd, methods=${parserMethods.size}")
        }
    }

    private fun findGsonParsers(): List<Method> {
        val gson = "com.google.gson.Gson".from(classLoader) ?: return emptyList()
        return gson.methodsNamed("fromJson")
            .filter { !Modifier.isAbstract(it.modifiers) && it.returnType != Void.TYPE }
            .toList()
    }

    private fun findFastJsonParsers(): List<Method> {
        val json = FAST_JSON_CLASSES.firstNotNullOfOrNull { it.from(classLoader) }
            ?: return emptyList()
        return json.declaredMethods
            .filter {
                Modifier.isStatic(it.modifiers) &&
                    it.name in FAST_JSON_PARSE_METHODS &&
                    it.returnType != Void.TYPE
            }
            .toList()
    }

    private fun findKotlinxJsonParsers(): List<Method> {
        val json = KOTLINX_JSON_CLASSES.firstNotNullOfOrNull { it.from(classLoader) }
            ?: return emptyList()
        return json.methodsNamed("decodeFromString")
            .filter { it.parameterCount >= 2 && it.returnType != Void.TYPE }
            .toList()
    }

    private fun dispatch(rawResult: Any?) {
        val result = unwrap(rawResult) ?: return
        val fields = SPLASH_FIELDS[result.javaClass.name] ?: return
        fields.forEach { clearMutableList(result, it) }
    }

    private fun dispatchKotlinx(deserializer: Any?, rawResult: Any?) {
        val result = rawResult ?: return
        when (resolveSerialName(deserializer)) {
            "kntr.srcs.app.splash.model.SplashListResponse" -> clearAllMutableLists(result)
            "kntr.srcs.app.splash.model.SplashShowStrategy" -> clearSplashShowStrategy(result)
        }
    }

    private fun resolveSerialName(deserializer: Any?): String? {
        val descriptor = deserializer?.callNoArg("getDescriptor") ?: return null
        return descriptor.callNoArg("getSerialName") as? String
            ?: descriptor.getObjectField("serialName") as? String
    }

    private fun unwrap(result: Any?): Any? {
        if (result == null) return null
        val className = result.javaClass.name
        return if (className.endsWith("GeneralResponse") || className.endsWith("RxGeneralResponse")) {
            result.getObjectField("data")
        } else {
            result
        }
    }

    private fun clearMutableList(target: Any, fieldName: String) {
        (target.getObjectField(fieldName) as? MutableList<*>)?.clear()
    }

    private fun clearAllMutableLists(target: Any) {
        target.javaClass.declaredFields
            .filter { List::class.java.isAssignableFrom(it.type) }
            .forEach { field ->
                field.isAccessible = true
                runCatching { (field.get(target) as? MutableList<*>)?.clear() }
            }
    }

    private fun clearSplashShowStrategy(target: Any) {
        target.javaClass.declaredFields
            .filter { !it.type.isPrimitive }
            .filter { it.type != String::class.java && it.type.name !in JSON_OBJECT_CLASSES }
            .forEach { field ->
                field.isAccessible = true
                runCatching { field.set(target, null) }
            }
    }

    private fun Any.callNoArg(name: String): Any? {
        return javaClass.methods
            .firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.let { method ->
                method.isAccessible = true
                runCatching { method.invoke(this) }.getOrNull()
            }
    }

    private companion object {
        private val FAST_JSON_CLASSES = arrayOf(
            "com.alibaba.fastjson.JSON",
            "com.alibaba.fastjson2.JSON",
        )
        private val FAST_JSON_PARSE_METHODS = setOf("parse", "parseObject")
        private val KOTLINX_JSON_CLASSES = arrayOf(
            "kotlinx.serialization.json.Json",
            "kotlinx.serialization.p7923json.AbstractC137025Json",
        )
        private val JSON_OBJECT_CLASSES = setOf(
            "kotlinx.serialization.json.JsonObject",
            "kotlinx.serialization.p7923json.JsonObject",
        )
        private val SPLASH_LIST_FIELDS = listOf("adList", "showList", "splashList", "strategyList")
        private val SPLASH_SHOW_FIELDS = listOf("showList", "splashList", "strategyList")

        private val SPLASH_FIELDS = mapOf(
            "tv.danmaku.bili.splash.ad.model.SplashListResponse" to SPLASH_LIST_FIELDS,
            "tv.danmaku.bili.ui.splash.SplashData" to SPLASH_LIST_FIELDS,
            "tv.danmaku.bili.ui.splash.ad.model.SplashData" to SPLASH_LIST_FIELDS,
            "tv.danmaku.bili.splash.ad.model.SplashShowResponse" to SPLASH_SHOW_FIELDS,
            "tv.danmaku.bili.ui.splash.ShowSplashData" to SPLASH_SHOW_FIELDS,
            "tv.danmaku.bili.ui.splash.ad.model.SplashShowData" to SPLASH_SHOW_FIELDS,
        )
    }
}

