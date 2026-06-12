package io.github.bzzq.hooks

import io.github.bzzq.ModuleSettings

class FullNumberFormatHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        val numberFormatClass = HostAccess.findClass(classLoader, *NUMBER_FORMAT_CLASSES)
        if (numberFormatClass == null) {
            log("NumberFormat class not found, skipping full-number hook")
            return
        }

        // Hook the core formatting method: public static String format(long j12, String str)
        // This is used globally in Bilibili app to convert large numbers to "万", "亿", etc.
        val formatMethod = HostAccess.methods(numberFormatClass)
            .filter { it.name == "format" }
            .filter { 
                it.parameterTypes.size == 2 && 
                it.parameterTypes[0] == Long::class.javaPrimitiveType && 
                it.parameterTypes[1] == String::class.java 
            }
            .firstOrNull()

        if (formatMethod != null) {
            xposed.hook(formatMethod).intercept { chain ->
                if (ModuleSettings.isFullNumberFormatEnabled(prefs)) {
                    val value = chain.args[0] as Long
                    // Return raw number as string instead of shortened format
                    if (value >= 0) {
                        return@intercept value.toString()
                    }
                }
                chain.proceed()
            }
            log("Installed global full-number hook via NumberFormat.format")
        } else {
            log("NumberFormat.format method not found, full-number display might not work")
        }
    }

    private companion object {
        private val NUMBER_FORMAT_CLASSES = arrayOf(
            "com.bilibili.p4566base.p4568util.NumberFormat",
            "com.bilibili.n9.util.NumberFormat",
            "com.bilibili.lib.utils.NumberFormat",
        )
    }
}
