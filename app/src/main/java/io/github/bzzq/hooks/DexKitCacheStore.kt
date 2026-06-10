package io.github.bzzq.hooks

import com.tencent.mmkv.MMKV
import org.luckypray.dexkit.DexKitBridge

class DexKitCacheStore(
    private val packageName: String,
    private val versionCode: Long,
    private val sourceDir: String,
    private val classLoader: ClassLoader,
    private val log: (String, Throwable?) -> Unit,
) {
    private val kv by lazy(LazyThreadSafetyMode.NONE) {
        val application = checkNotNull(HostEnv.currentApplication()) { "Host application is not ready" }
        MMKV.initialize(application)
        MMKV.mmkvWithID("dexkit_cache_$packageName", MMKV.MULTI_PROCESS_MODE)
            ?: error("Failed to open MMKV for $packageName")
    }

    private var bridge: DexKitBridge? = null

    fun ensureVersionState() {
        val cachedVersion = kv.getLong(KEY_VERSION_CODE, Long.MIN_VALUE)
        if (cachedVersion == versionCode) return

        kv.clearAll()
        kv.putLong(KEY_VERSION_CODE, versionCode)
        kv.putString(KEY_SOURCE_DIR, sourceDir)
    }

    fun readMethodCache(key: String): String = kv.getString(methodKey(key), "") ?: ""

    fun saveMethodCache(key: String, serialized: String) {
        kv.putString(methodKey(key), serialized)
    }

    fun readStringCache(key: String): String = kv.getString(stringKey(key), "") ?: ""

    fun saveStringCache(key: String, value: String) {
        kv.putString(stringKey(key), value)
    }

    fun readClassCache(key: String): String = kv.getString(classKey(key), "") ?: ""

    fun saveClassCache(key: String, className: String) {
        kv.putString(classKey(key), className)
    }

    fun openBridge(): DexKitBridge {
        bridge?.takeIf { it.isValid }?.let { return it }

        val newBridge = runCatching {
            DexKitBridge.create(sourceDir)
        }.recoverCatching {
            log("DexKit sourceDir mode failed for $packageName, retrying with classLoader", it)
            DexKitBridge.create(classLoader, true)
        }.getOrElse {
            throw it
        }

        bridge = newBridge
        return newBridge
    }

    fun close() {
        bridge?.close()
        bridge = null
    }

    private fun methodKey(name: String) = "method_$name"

    private fun stringKey(name: String) = "string_$name"

    private fun classKey(name: String) = "class_$name"

    companion object {
        private const val KEY_VERSION_CODE = "host_version_code"
        private const val KEY_SOURCE_DIR = "host_source_dir"
    }
}
