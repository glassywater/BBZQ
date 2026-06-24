package io.github.bbzq

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method

class ModuleSettingsBridge private constructor() : SharedPreferences {
    private val cacheLock = Any()
    private var localCache: Map<String, Any> = emptyMap()
    private var lastLoadTime = 0L
    private var providerUnavailable = false
    private var providerFailureTime = 0L
    private var hasAuthoritativeSnapshot = false

    private fun ensureLoaded() {
        val now = System.currentTimeMillis()
        var snapshotToPersist: Map<String, Any>? = null
        synchronized(cacheLock) {
            if (localCache.isNotEmpty() && now - lastLoadTime < CACHE_EXPIRATION) return
            val source = getAllFromSources()
            val loaded = source.values
                .mapNotNull { (key, value) -> value?.let { key to it } }
                .toMap()
            if (loaded.isNotEmpty()) localCache = loaded
            hasAuthoritativeSnapshot = source.authoritative
            lastLoadTime = now
            lastProviderStatus = source.status
            if (source.authoritative && loaded.isNotEmpty() && source.persistSnapshot) {
                snapshotToPersist = loaded
            }
        }
        snapshotToPersist?.let(::persistHostSnapshot)
    }

    private fun getAllFromSources(): SettingsSource {
        val remote = getAllFromRemotePreferences()
        val provider = getAllFromProvider()
        if (remote.isNotEmpty() && provider.isNotEmpty()) {
            return SettingsSource(remote + provider, authoritative = true, status = "remote+provider ok")
        }
        if (remote.isNotEmpty()) return SettingsSource(remote, authoritative = true, status = "remote ok")
        if (provider.isNotEmpty()) return SettingsSource(provider, authoritative = true, status = "provider ok")

        val failedStatus = lastProviderStatus
        val snapshot = getAllFromHostSnapshot()
        if (snapshot.isNotEmpty()) {
            return SettingsSource(
                snapshot,
                authoritative = true,
                status = "snapshot ok; live settings unavailable ($failedStatus)",
                persistSnapshot = false,
            )
        }

        return SettingsSource(
            fallbackDefaults(),
            authoritative = false,
            status = "defaults only; live settings unavailable ($failedStatus)",
            persistSnapshot = false,
        )
    }

    private fun getAllFromRemotePreferences(): Map<String, Any?> {
        val remotePrefs = resolveRemotePreferences() ?: run {
            lastProviderStatus = "remote unavailable"
            return emptyMap()
        }
        return runCatching {
            remotePrefs.all.mapValues { it.value }
        }.getOrElse {
            lastProviderStatus = "remote ${it.javaClass.simpleName}: ${it.message}"
            emptyMap()
        }
    }

    private fun getAllFromProvider(): Map<String, Any?> {
        if (isProviderRetryBlocked()) return emptyMap()
        val result = call(ModuleSettingsProvider.METHOD_GET_ALL, null, null)
        val values = result?.keySet()?.associateWith { key -> result.get(key) }.orEmpty()
        return values
    }

    private fun getAllFromHostSnapshot(): Map<String, Any?> =
        runCatching {
            resolveHostSnapshotPreferences()?.all.orEmpty().mapValues { it.value }
        }.getOrElse {
            lastProviderStatus = "snapshot ${it.javaClass.simpleName}: ${it.message}"
            emptyMap()
        }

    private fun fallbackDefaults(): Map<String, Any?> = mapOf(
        ModuleSettings.KEY_SKIP_SPLASH_AD_ENABLED to true,
        ModuleSettings.KEY_UNLOCK_VIDEO_FEATURES_ENABLED to false,
        ModuleSettings.KEY_FULL_NUMBER_FORMAT_ENABLED to false,
    )

    override fun getAll(): MutableMap<String, *> {
        ensureLoaded()
        return synchronized(cacheLock) { localCache.toMutableMap() }
    }

    override fun getString(key: String?, defValue: String?): String? {
        ensureLoaded()
        return synchronized(cacheLock) { localCache[key] as? String } ?: run {
            if (hasAuthoritativeSnapshot) return defValue
            if (isProviderRetryBlocked()) return defValue
            val result = call(
                ModuleSettingsProvider.METHOD_GET_STRING,
                key,
                Bundle().apply { putString(ModuleSettingsProvider.EXTRA_DEFAULT, defValue) },
            )
            result?.getString(ModuleSettingsProvider.EXTRA_VALUE, defValue)
                ?.also { cacheRuntimeValue(key, it) }
        }
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        ensureLoaded()
        val cached = synchronized(cacheLock) { localCache[key] }
        val cachedSet = when (cached) {
            is Set<*> -> cached.map { it.toString() }.toMutableSet()
            is List<*> -> cached.map { it.toString() }.toMutableSet()
            else -> null
        }
        if (cachedSet != null) return cachedSet
        if (hasAuthoritativeSnapshot) return defValues
        if (isProviderRetryBlocked()) return defValues

        val result = call(
            ModuleSettingsProvider.METHOD_GET_STRING_SET,
            key,
            Bundle().apply {
                putStringArrayList(ModuleSettingsProvider.EXTRA_DEFAULT, ArrayList(defValues.orEmpty()))
            },
        )
        return result?.getStringArrayList(ModuleSettingsProvider.EXTRA_VALUE)
            ?.toMutableSet()
            ?.also { cacheRuntimeValue(key, it.toSet()) }
            ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int {
        ensureLoaded()
        return (synchronized(cacheLock) { localCache[key] } as? Number)?.toInt() ?: run {
            if (hasAuthoritativeSnapshot) return defValue
            if (isProviderRetryBlocked()) return defValue
            val result = call(
                ModuleSettingsProvider.METHOD_GET_INT,
                key,
                Bundle().apply { putInt(ModuleSettingsProvider.EXTRA_DEFAULT, defValue) },
            )
            result?.getInt(ModuleSettingsProvider.EXTRA_VALUE, defValue)
                ?.also { cacheRuntimeValue(key, it) }
                ?: defValue
        }
    }

    override fun getLong(key: String?, defValue: Long): Long {
        ensureLoaded()
        return when (val value = synchronized(cacheLock) { localCache[key] }) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: defValue
            else -> defValue
        }
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        ensureLoaded()
        return when (val value = synchronized(cacheLock) { localCache[key] }) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull() ?: defValue
            else -> defValue
        }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        ensureLoaded()
        return (synchronized(cacheLock) { localCache[key] } as? Boolean) ?: run {
            if (hasAuthoritativeSnapshot) return defValue
            if (isProviderRetryBlocked()) return defValue
            val result = call(
                ModuleSettingsProvider.METHOD_GET_BOOLEAN,
                key,
                Bundle().apply { putBoolean(ModuleSettingsProvider.EXTRA_DEFAULT, defValue) },
            )
            result?.getBoolean(ModuleSettingsProvider.EXTRA_VALUE, defValue)
                ?.also { cacheRuntimeValue(key, it) }
                ?: defValue
        }
    }

    override fun contains(key: String?): Boolean {
        ensureLoaded()
        if (synchronized(cacheLock) { localCache.containsKey(key) }) return true
        if (hasAuthoritativeSnapshot) return false
        if (isProviderRetryBlocked()) return false
        val result = call(ModuleSettingsProvider.METHOD_CONTAINS, key, null)
        return result?.getBoolean(ModuleSettingsProvider.EXTRA_VALUE, false) ?: false
    }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
        recordStatus: Boolean = true,
    ): Bundle? {
        val resolver = resolveContentResolver() ?: return null
        return try {
            resolver.call(ModuleSettingsProvider.CONTENT_URI, method, arg, extras).also {
                providerUnavailable = false
                providerFailureTime = 0L
                if (recordStatus) {
                    lastProviderStatus = if (it == null) "$method returned null" else "$method ok"
                }
            }
        } catch (e: IllegalArgumentException) {
            providerUnavailable = true
            providerFailureTime = System.currentTimeMillis()
            if (recordStatus) lastProviderStatus = "$method IllegalArgumentException: ${e.message}"
            null
        } catch (e: SecurityException) {
            providerUnavailable = true
            providerFailureTime = System.currentTimeMillis()
            if (recordStatus) lastProviderStatus = "$method SecurityException: ${e.message}"
            null
        }
    }

    private fun resolveContentResolver(): ContentResolver? {
        return resolveContext()?.moduleAwareContentResolver()
    }

    private fun resolveContext(): Context? {
        cachedContext.get()?.let { return it }
        val application = runCatching {
            currentApplicationMethod.invoke(null) as? Application
        }.getOrNull() ?: return null
        cachedContext = WeakReference(application)
        return application
    }

    private fun Context.moduleAwareContentResolver(): ContentResolver =
        runCatching {
            createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY).contentResolver
        }.getOrDefault(contentResolver)

    private fun resolveRemotePreferences(): SharedPreferences? {
        cachedRemotePrefs.get()?.let { return it }
        val xposed = cachedXposed ?: return null
        return runCatching {
            xposed.getRemotePreferences(ModuleSettings.PREFS_NAME)
        }.getOrNull()?.also {
            cachedRemotePrefs = WeakReference(it)
        }
    }

    private fun resolveHostSnapshotPreferences(): SharedPreferences? =
        resolveContext()?.getSharedPreferences(HOST_SNAPSHOT_PREFS_NAME, Context.MODE_PRIVATE)

    private fun persistHostSnapshot(values: Map<String, Any>) {
        val prefs = resolveHostSnapshotPreferences() ?: return
        runCatching {
            prefs.edit()
                .clear()
                .applyValues(values)
                .apply()
        }.onFailure {
            lastProviderStatus = "snapshot write ${it.javaClass.simpleName}: ${it.message}"
            Log.w(LOG_TAG, "runtime settings snapshot write failed", it)
        }
    }

    private fun cacheRuntimeValue(key: String?, value: Any) {
        if (key == null) return
        var snapshotToPersist: Map<String, Any>? = null
        synchronized(cacheLock) {
            val updated = localCache.toMutableMap()
            updated[key] = value
            localCache = updated
            lastLoadTime = System.currentTimeMillis()
            snapshotToPersist = updated
        }
        snapshotToPersist?.let(::persistHostSnapshot)
    }

    private inner class Editor : SharedPreferences.Editor {
        private val operations = mutableListOf<() -> Unit>()
        private val cacheUpdates = mutableListOf<(MutableMap<String, Any>) -> Unit>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            operations += {
                call(
                    ModuleSettingsProvider.METHOD_PUT_STRING,
                    key,
                    Bundle().apply { putString(ModuleSettingsProvider.EXTRA_VALUE, value) },
                    recordStatus = false,
                )
            }
            cacheUpdates += { cache ->
                if (key != null && value != null) cache[key] = value
                else if (key != null) cache.remove(key)
            }
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply {
            operations += {
                call(
                    ModuleSettingsProvider.METHOD_PUT_STRING_SET,
                    key,
                    Bundle().apply {
                        putStringArrayList(ModuleSettingsProvider.EXTRA_VALUE, ArrayList(values.orEmpty()))
                    },
                    recordStatus = false,
                )
            }
            cacheUpdates += { cache ->
                if (key != null && values != null) cache[key] = values.toSet()
                else if (key != null) cache.remove(key)
            }
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            operations += {
                call(
                    ModuleSettingsProvider.METHOD_PUT_INT,
                    key,
                    Bundle().apply { putInt(ModuleSettingsProvider.EXTRA_VALUE, value) },
                    recordStatus = false,
                )
            }
            cacheUpdates += { cache -> if (key != null) cache[key] = value }
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            operations += {
                call(
                    ModuleSettingsProvider.METHOD_PUT_STRING,
                    key,
                    Bundle().apply { putString(ModuleSettingsProvider.EXTRA_VALUE, value.toString()) },
                    recordStatus = false,
                )
            }
            cacheUpdates += { cache -> if (key != null) cache[key] = value.toString() }
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            operations += {
                call(
                    ModuleSettingsProvider.METHOD_PUT_STRING,
                    key,
                    Bundle().apply { putString(ModuleSettingsProvider.EXTRA_VALUE, value.toString()) },
                    recordStatus = false,
                )
            }
            cacheUpdates += { cache -> if (key != null) cache[key] = value.toString() }
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            operations += {
                call(
                    ModuleSettingsProvider.METHOD_PUT_BOOLEAN,
                    key,
                    Bundle().apply { putBoolean(ModuleSettingsProvider.EXTRA_VALUE, value) },
                    recordStatus = false,
                )
            }
            cacheUpdates += { cache -> if (key != null) cache[key] = value }
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            operations += { call(ModuleSettingsProvider.METHOD_REMOVE, key, null, recordStatus = false) }
            cacheUpdates += { cache -> if (key != null) cache.remove(key) }
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearRequested = true
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            ensureLoaded()
            if (clearRequested) {
                synchronized(cacheLock) { localCache.keys.toList() }.forEach { key ->
                    call(ModuleSettingsProvider.METHOD_REMOVE, key, null, recordStatus = false)
                }
            }
            operations.forEach { it() }

            var snapshotToPersist: Map<String, Any>? = null
            synchronized(cacheLock) {
                val updated = if (clearRequested) mutableMapOf() else localCache.toMutableMap()
                cacheUpdates.forEach { it(updated) }
                localCache = updated
                lastLoadTime = System.currentTimeMillis()
                if (hasAuthoritativeSnapshot) snapshotToPersist = updated
            }
            snapshotToPersist?.let(::persistHostSnapshot)

            operations.clear()
            cacheUpdates.clear()
            clearRequested = false
        }
    }

    companion object {
        private const val CACHE_EXPIRATION = 5000L
        private const val MODULE_PACKAGE = "io.github.bbzq"
        private const val HOST_SNAPSHOT_PREFS_NAME = "bbzq_runtime_settings_snapshot"
        private const val LOG_TAG = "BBZQ"
        private var cachedContext = WeakReference<Context>(null)
        private var cachedXposed: XposedInterface? = null
        private var cachedRemotePrefs = WeakReference<SharedPreferences>(null)
        private val currentApplicationMethod: Method by lazy(LazyThreadSafetyMode.NONE) {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
        }
        @Volatile var lastProviderStatus: String = "not called"
            private set

        fun attach(context: Context, xposed: XposedInterface? = null) {
            cachedContext = WeakReference(context.applicationContext ?: context)
            if (xposed != null) cachedXposed = xposed
            instance.resetTransientState()
        }

        val instance: ModuleSettingsBridge by lazy(LazyThreadSafetyMode.NONE) {
            ModuleSettingsBridge()
        }
    }

    private fun resetTransientState() {
        synchronized(cacheLock) {
            localCache = emptyMap()
            lastLoadTime = 0L
            providerUnavailable = false
            providerFailureTime = 0L
            hasAuthoritativeSnapshot = false
        }
        cachedRemotePrefs = WeakReference(null)
    }

    private fun isProviderRetryBlocked(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            if (!providerUnavailable) return false
            if (now - providerFailureTime >= CACHE_EXPIRATION) {
                providerUnavailable = false
                providerFailureTime = 0L
                return false
            }
            return true
        }
    }

    private data class SettingsSource(
        val values: Map<String, Any?>,
        val authoritative: Boolean,
        val status: String,
        val persistSnapshot: Boolean = true,
    )
}

private fun SharedPreferences.Editor.applyValues(values: Map<String, Any>): SharedPreferences.Editor = apply {
    values.forEach { (key, value) ->
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> putStringSet(key, value.map { it.toString() }.toSet())
            is List<*> -> putStringSet(key, value.map { it.toString() }.toSet())
            else -> putString(key, value.toString())
        }
    }
}
