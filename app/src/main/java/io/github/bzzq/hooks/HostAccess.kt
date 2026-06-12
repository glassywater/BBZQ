package io.github.bzzq.hooks

import android.content.Context
import android.view.View
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object HostAccess {
    fun findClass(classLoader: ClassLoader, vararg names: String): Class<*>? =
        names.firstNotNullOfOrNull { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()
        }

    fun findClasses(classLoader: ClassLoader, vararg names: String): List<Class<*>> =
        names.mapNotNull { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()
        }

    fun findField(type: Class<*>, vararg names: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            names.forEach { name ->
                runCatching { current.getDeclaredField(name) }
                    .getOrNull()
                    ?.let { return it.apply { isAccessible = true } }
            }
            current = current.superclass
        }
        return null
    }

    fun fields(type: Class<*>): Sequence<Field> = sequence {
        var current: Class<*>? = type
        while (current != null) {
            yieldAll(current.declaredFields.asSequence().onEach { it.isAccessible = true })
            current = current.superclass
        }
    }

    fun methods(type: Class<*>): Sequence<Method> = sequence {
        val seen = mutableSetOf<String>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.forEach { method ->
                val signature = method.name + method.parameterTypes.joinToString { it.name }
                if (seen.add(signature)) {
                    method.isAccessible = true
                    yield(method)
                }
            }
            current = current.superclass
        }
    }

    fun method(
        type: Class<*>,
        names: Collection<String>,
        predicate: (Method) -> Boolean = { true },
    ): Method? = methods(type).firstOrNull { it.name in names && predicate(it) }

    fun get(target: Any, vararg names: String): Any? {
        names.forEach { name ->
            invoke(target, getterName(name))?.let { return it }
            invoke(target, name)?.let { return it }
            findField(target.javaClass, name)?.let { field ->
                return runCatching { field.get(target) }.getOrNull()
            }
        }
        return null
    }

    fun getLong(target: Any, vararg names: String): Long? =
        names.firstNotNullOfOrNull { name -> valueToLong(get(target, name)) }

    fun getBoolean(target: Any, vararg names: String): Boolean? =
        names.firstNotNullOfOrNull { name ->
            when (val value = get(target, name)) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> value.toBooleanStrictOrNull()
                else -> null
            }
        }

    fun set(target: Any, value: Any?, vararg names: String): Boolean {
        names.forEach { name ->
            val setter = methods(target.javaClass).firstOrNull { method ->
                method.name == setterName(name) &&
                    method.parameterCount == 1 &&
                    isValueCompatible(method.parameterTypes[0], value)
            }
            if (setter != null && runCatching { setter.invoke(target, value) }.isSuccess) return true

            val field = findField(target.javaClass, name) ?: return@forEach
            if (isValueCompatible(field.type, value) && runCatching { field.set(target, value) }.isSuccess) {
                return true
            }
        }
        return false
    }

    fun clear(target: Any, vararg names: String): Boolean {
        names.forEach { name ->
            val clearer = method(target.javaClass, listOf(clearerName(name))) { it.parameterCount == 0 }
            if (clearer != null && runCatching { clearer.invoke(target) }.isSuccess) return true

            val field = findField(target.javaClass, name) ?: return@forEach
            val cleared = when (field.type) {
                Boolean::class.javaPrimitiveType -> false
                Byte::class.javaPrimitiveType -> 0.toByte()
                Short::class.javaPrimitiveType -> 0.toShort()
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                Double::class.javaPrimitiveType -> 0.0
                Char::class.javaPrimitiveType -> '\u0000'
                else -> null
            }
            if (runCatching { field.set(target, cleared) }.isSuccess) return true
        }
        return false
    }

    fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val method = methods(target.javaClass).firstOrNull { candidate ->
            candidate.name == name &&
                candidate.parameterCount == args.size &&
                candidate.parameterTypes.indices.all { index ->
                    isValueCompatible(candidate.parameterTypes[index], args[index])
                }
        } ?: return null
        return runCatching { method.invoke(target, *args) }.getOrNull()
    }

    fun context(target: Any?): Context? {
        if (target is Context) return target
        if (target is View) return target.context
        if (target == null) return null
        return fields(target.javaClass)
            .firstOrNull { Context::class.java.isAssignableFrom(it.type) && !Modifier.isStatic(it.modifiers) }
            ?.let { runCatching { it.get(target) as? Context }.getOrNull() }
    }

    fun asMutableList(value: Any?): MutableList<Any?>? {
        @Suppress("UNCHECKED_CAST")
        return value as? MutableList<Any?>
    }

    fun asIterable(value: Any?): Iterable<*> = when (value) {
        is Iterable<*> -> value
        is Array<*> -> value.asIterable()
        else -> emptyList<Any?>()
    }

    private fun getterName(name: String): String =
        "get${name.replaceFirstChar { it.uppercaseChar() }}"

    private fun setterName(name: String): String =
        "set${name.replaceFirstChar { it.uppercaseChar() }}"

    private fun clearerName(name: String): String =
        "clear${name.replaceFirstChar { it.uppercaseChar() }}"

    private fun valueToLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    private fun isValueCompatible(type: Class<*>, value: Any?): Boolean {
        if (value == null) return !type.isPrimitive
        if (type.isInstance(value)) return true
        return primitiveWrapper(type)?.isInstance(value) == true
    }

    private fun primitiveWrapper(type: Class<*>): Class<*>? = when (type) {
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
        Short::class.javaPrimitiveType -> Short::class.javaObjectType
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        Char::class.javaPrimitiveType -> Char::class.javaObjectType
        else -> null
    }
}
