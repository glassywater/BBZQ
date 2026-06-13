package io.github.bzzq.hooks

import io.github.bzzq.ModuleSettings
import io.github.libxposed.api.XposedInterface
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class SkipVideoAdHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    @Volatile
    private var bvid = ""

    @Volatile
    private var cid = ""

    @Volatile
    private var duration = -1

    @Volatile
    private var segments: List<Segment>? = null

    @Volatile
    private var lastSeekMs = 0L

    @Volatile
    private var cooldownMs = POLL_COOLDOWN_MS

    private var playerRef: WeakReference<Any>? = null

    private val player: Any?
        get() = playerRef?.get()

    override fun startHook() {
        if (!ModuleSettings.isSkipVideoAdEnabled(prefs)) return

        hookPlayRequest()
        hookPlayerState()
        hookCurrentPosition()

        log("SkipVideoAdHook installed")
    }

    private fun hookPlayRequest() {
        val executeMethod = HostMethodResolver(context).resolve(
            cacheKey = "player_moss_execute_play_view_unite",
            fixedCandidates = {
                HostAccess.findClass(
                    classLoader,
                    "com.bapis.bilibili.app.playerunite.v1.PlayerMoss",
                )?.let(HostAccess::methods) ?: emptySequence()
            },
            usingStrings = listOf("executePlayViewUnite"),
            validate = { method ->
                method.name == "executePlayViewUnite" && method.parameterCount >= 1
            },
        ) ?: run {
            log("executePlayViewUnite not found — bvid/cid capture via request disabled")
            return
        }

        xposed.hook(executeMethod)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                runCatching { captureFromRequest(chain.args.getOrNull(0)) }
                chain.proceed()
            }

        log("Hooked ${executeMethod.declaringClass.name}#${executeMethod.name} for bvid/cid")
    }

    private fun captureFromRequest(request: Any?) {
        request ?: return
        val newBvid = HostAccess.invoke(request, "getBvid") as? String ?: ""
        val vod = HostAccess.invoke(request, "getVod") ?: return
        val rawCid = (HostAccess.invoke(vod, "getCid") as? Number)?.toLong() ?: return
        val resolvedBvid = if (newBvid.isNotEmpty()) {
            newBvid
        } else {
            val aid = (HostAccess.invoke(vod, "getAid") as? Number)?.toLong() ?: return
            if (aid == -1L) return
            av2bv(aid)
        }

        bvid = resolvedBvid
        cid = rawCid.toString()
        log("Captured bvid=$bvid cid=$cid")
    }

    private fun hookPlayerState() {
        val stateMethod = HostMethodResolver(context).resolve(
            cacheKey = "player_core_state_change",
            fixedCandidates = { emptySequence() },
            usingStrings = listOf("getDuration"),
            validate = { method ->
                method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    method.returnType == Void.TYPE
            },
        ) ?: run {
            log("Player state method not found — segment fetch disabled")
            return
        }

        xposed.hook(stateMethod)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val state = chain.args[0] as? Int
                val result = chain.proceed()
                when (state) {
                    2 -> {
                        playerRef = WeakReference(chain.thisObject)
                        duration = -1
                        segments = null
                        val currentBvid = bvid
                        val currentCid = cid
                        if (currentBvid.isNotEmpty() && currentCid.isNotEmpty()) {
                            fetchSegmentsAsync(currentBvid, currentCid)
                        }
                    }

                    3, 4, 5 -> {
                        playerRef = WeakReference(chain.thisObject)
                        if (duration <= 0) {
                            duration =
                                (HostAccess.invoke(chain.thisObject, "getDuration") as? Number)?.toInt() ?: -1
                        }
                    }
                }
                result
            }

        log("Hooked ${stateMethod.declaringClass.name}#${stateMethod.name} for player state")
    }

    private fun fetchSegmentsAsync(currentBvid: String, currentCid: String) {
        Thread {
            var fetched = false
            for (attempt in 0 until MAX_RETRIES) {
                val result = SponsorBlockClient.fetchSegments(currentBvid, currentCid)
                if (result != null) {
                    segments = result
                    log("Fetched ${result.size} segment(s) for bvid=$currentBvid cid=$currentCid")
                    fetched = true
                    break
                }
                if (attempt < MAX_RETRIES - 1) {
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }
            if (!fetched) {
                log("Failed to fetch segments for bvid=$currentBvid cid=$currentCid after $MAX_RETRIES attempts")
            }
        }.start()
    }

    private fun hookCurrentPosition() {
        val positionMethod = HostMethodResolver(context).resolve(
            cacheKey = "player_get_current_position",
            fixedCandidates = { emptySequence() },
            usingStrings = listOf("getCurrentPosition"),
            validate = { method ->
                method.name == "getCurrentPosition" &&
                    method.parameterTypes.isEmpty() &&
                    (
                        method.returnType == Int::class.javaPrimitiveType ||
                            method.returnType == Long::class.javaPrimitiveType
                        ) &&
                    method.declaringClass.name.let { name ->
                        name.contains("Player", ignoreCase = true) ||
                            name.contains("Service", ignoreCase = true)
                    }
            },
        ) ?: run {
            log("getCurrentPosition not found — ad seek disabled")
            return
        }

        xposed.hook(positionMethod)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val result = chain.proceed()
                val now = System.currentTimeMillis()
                if (now - lastSeekMs > cooldownMs) {
                    lastSeekMs = now
                    val positionMs = (result as? Number)?.toInt()
                    cooldownMs = if (positionMs != null && seekIfNeeded(positionMs)) {
                        SEEK_COOLDOWN_MS
                    } else {
                        POLL_COOLDOWN_MS
                    }
                }
                result
            }

        log("Hooked ${positionMethod.declaringClass.name}#${positionMethod.name} for seek")
    }

    private fun seekIfNeeded(positionMs: Int): Boolean {
        val currentSegments = segments ?: return false
        val currentDuration = duration
        if (currentDuration > 0 && positionMs > currentDuration) return false

        currentSegments.forEach { segment ->
            val startMs = (segment.startSec * 1000).toInt()
            val endMs = (segment.endSec * 1000).toInt()
            if (positionMs in startMs until endMs) {
                val currentPlayer = player ?: return false
                log("Seeking past ad segment [$startMs-$endMs]ms, current=$positionMs")
                HostAccess.invoke(currentPlayer, "seekTo", endMs)
                    ?: HostAccess.invoke(currentPlayer, "seekTo", endMs.toLong())
                return true
            }
        }
        return false
    }

    private fun av2bv(aid: Long): String {
        val value = CharArray(12) { if (it < 3) "BV1"[it] else '0' }
        var temp = ((1L shl 51) or aid) xor 23442827791579L
        var index = 11
        while (temp > 0) {
            value[index--] = BV_TABLE[(temp % 58).toInt()]
            temp /= 58
        }
        value[3] = value[9].also { value[9] = value[3] }
        value[4] = value[7].also { value[7] = value[4] }
        return String(value)
    }

    private data class Segment(
        val startSec: Float,
        val endSec: Float,
        val cid: String,
    )

    private object SponsorBlockClient {
        private const val BASE_URL = "https://bsbsb.top/api/skipSegments/"

        private val client by lazy(LazyThreadSafetyMode.NONE) {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        fun fetchSegments(bvid: String, cid: String): List<Segment>? = runCatching {
            val request = Request.Builder()
                .url("$BASE_URL${sha256Prefix(bvid)}?category=sponsor")
                .header("origin", "BBZQ")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    null
                } else {
                    val body = response.body?.string()
                    if (body.isNullOrEmpty()) null else parseSegments(body, bvid, cid)
                }
            }
        }.getOrNull()

        private fun parseSegments(json: String, bvid: String, cid: String): List<Segment>? =
            try {
                val array = JSONArray(json)
                (0 until array.length())
                    .mapNotNull { index ->
                        val item = array.getJSONObject(index)
                        if (item.optString("videoID") != bvid) return@mapNotNull null
                        val segments = item.getJSONArray("segments")
                        (0 until segments.length()).map { segmentIndex ->
                            val segmentItem = segments.getJSONObject(segmentIndex)
                            val values = segmentItem.getJSONArray("segment")
                            Segment(
                                startSec = values.getDouble(0).toFloat(),
                                endSec = values.getDouble(1).toFloat(),
                                cid = segmentItem.optString("cid"),
                            )
                        }
                    }
                    .flatten()
                    .filter { it.cid == cid }
                    .sortedBy { it.startSec }
                    .takeIf { it.isNotEmpty() }
            } catch (_: JSONException) {
                null
            }

        private fun sha256Prefix(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(4)
    }

    private companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        private const val SEEK_COOLDOWN_MS = 3000L
        private const val POLL_COOLDOWN_MS = 1000L
        private const val BV_TABLE =
            "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"
    }
}
