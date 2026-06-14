package io.github.bbzq.roaming.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.roaming.BaseRoamingHook
import io.github.bbzq.roaming.RoamingEnv
import io.github.bbzq.roaming.from
import io.github.bbzq.roaming.getObjectField
import io.github.bbzq.roaming.hookAfter
import io.github.bbzq.roaming.methodsNamed
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class BottomBarHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        val parserMethods = buildList {
            addAll(findGsonParsers())
            addAll(findFastJsonParsers())
        }.distinctBy(Method::toGenericString)

        parserMethods.forEach { method ->
            env.hookAfter(method) { param ->
                runCatching { dispatch(param.result) }
                    .onFailure {
                        log("Bottom bar processor failed at ${method.declaringClass.name}.${method.name}", it)
                    }
            }
        }

        if (parserMethods.isEmpty()) {
            log("startHook: BottomBar, no parser found")
        } else {
            log("startHook: BottomBar, methods=${parserMethods.size}")
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
        return json.methodsNamed(null)
            .filter {
                Modifier.isStatic(it.modifiers) &&
                    it.name in FAST_JSON_PARSE_METHODS &&
                    it.returnType != Void.TYPE
            }
            .toList()
    }

    private fun dispatch(rawResult: Any?) {
        val result = unwrap(rawResult) ?: return
        if (!result.isTabResponse()) return

        val tabData = result.readAny("tabData") ?: return
        val bottom = tabData.readMutableList("bottom") ?: return
        val hiddenIds = ModuleSettings.getHiddenBottomBarItems(prefs)
        val enabled = ModuleSettings.isCustomBottomBarEnabled(prefs)
        val knownItems = linkedSetOf<String>()

        bottom.removeAll { item ->
            if (item == null) return@removeAll false
            val id = item.readString("tabId", "f498840tabId", "id")
                ?.takeIf { it.isNotBlank() }
                ?: return@removeAll false
            val name = item.readString("name", "f498841name", "title").orEmpty()
            val uri = item.readString("uri", "f498844uri", "jumpUrl").orEmpty()

            knownItems += encodeBottomItem(
                order = knownItems.size,
                id = id,
                name = name.ifBlank { id },
                uri = uri,
            )
            enabled && id in hiddenIds
        }

        saveKnownItems(knownItems)
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

    private fun Any.isTabResponse(): Boolean {
        val className = javaClass.name
        return className in TAB_RESPONSE_CLASSES || className.endsWith("MainResourceManager\$TabResponse")
    }

    private fun Any.readAny(vararg names: String): Any? =
        names.firstNotNullOfOrNull { name -> getObjectField(name) }

    private fun Any.readString(vararg names: String): String? =
        readAny(*names)?.toString()

    @Suppress("UNCHECKED_CAST")
    private fun Any.readMutableList(vararg names: String): MutableList<Any?>? {
        names.forEach { name ->
            val value = getObjectField(name) as? MutableList<Any?>
            if (value != null) return value
        }
        return null
    }

    private fun saveKnownItems(items: Set<String>) {
        if (items.isEmpty()) return
        val oldItems = ModuleSettings.getKnownBottomBarItems(prefs)
        if (oldItems == items) return
        prefs.edit()
            .putStringSet(ModuleSettings.KEY_KNOWN_BOTTOM_BAR_ITEMS, items.toMutableSet())
            .apply()
    }

    private fun encodeBottomItem(order: Int, id: String, name: String, uri: String): String =
        listOf(order.toString(), id, name, uri)
            .joinToString(ITEM_SEPARATOR) { it.sanitizeItemPart() }

    private fun String.sanitizeItemPart(): String =
        replace('\t', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')

    private companion object {
        private const val ITEM_SEPARATOR = "\t"
        private val FAST_JSON_CLASSES = arrayOf(
            "com.alibaba.fastjson.JSON",
            "com.alibaba.fastjson2.JSON",
        )
        private val FAST_JSON_PARSE_METHODS = setOf("parse", "parseObject")
        private val TAB_RESPONSE_CLASSES = setOf(
            "tv.danmaku.bili.ui.main2.resource.MainResourceManager\$TabResponse",
            "tv.danmaku.p9138bili.p9228ui.main2.resource.MainResourceManager\$TabResponse",
        )
    }
}
