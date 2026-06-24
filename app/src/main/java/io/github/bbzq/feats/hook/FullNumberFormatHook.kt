package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.from
import io.github.bbzq.feats.hookBefore
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class FullNumberFormatHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName) return

        val methods = findFormatterMethods()
        if (methods.isEmpty()) {
            log("startHook: FullNumberFormat skipped, formatter method missing")
            return
        }

        methods.forEach { method ->
            env.hookBefore(method) { param ->
                if (!ModuleSettings.isFullNumberFormatEnabled(prefs)) return@hookBefore

                val rawNumber = when (val value = param.args.firstOrNull()) {
                    is Long -> value
                    is Int -> value.toLong()
                    else -> return@hookBefore
                }

                if (rawNumber >= 0) {
                    param.result = rawNumber.toString()
                }
            }
        }

        log("startHook: FullNumberFormat, methods=${methods.size}")
    }

    private fun findFormatterMethods(): List<Method> {
        val classes = NUMBER_FORMAT_CLASS_NAMES
            .asSequence()
            .mapNotNull { it.from(classLoader) }
            .distinctBy { it.name }
            .toList()

        return classes.asSequence()
            .flatMap { type -> type.allMethods() }
            .filter(::isTargetFormatter)
            .distinctBy(Method::toGenericString)
            .toList()
    }

    private fun isTargetFormatter(method: Method): Boolean {
        if (!Modifier.isStatic(method.modifiers)) return false
        if (method.returnType != String::class.java) return false
        if (method.name !in TARGET_METHOD_NAMES) return false

        val params = method.parameterTypes
        if (params.size !in 1..2) return false
        val first = params[0]
        if (
            first != Long::class.javaPrimitiveType &&
            first != Int::class.javaPrimitiveType
        ) {
            return false
        }
        if (params.size == 2 && params[1] != String::class.java) return false
        return true
    }

    private companion object {
        private val NUMBER_FORMAT_CLASS_NAMES = listOf(
            "com.bilibili.base.util.NumberFormat",
            "com.bilibili.p4566base.p4568util.NumberFormat",
            "com.bilibili.n9.util.NumberFormat",
            "com.bilibili.lib.utils.NumberFormat",
        )

        private val TARGET_METHOD_NAMES = setOf(
            "format",
            "formatWithComma",
        )
    }
}
