package io.github.bbzq.roaming.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.bbzq.ModuleSettings
import io.github.bbzq.roaming.BaseRoamingHook
import io.github.bbzq.roaming.MethodHookParam
import io.github.bbzq.roaming.RoamingEnv
import io.github.bbzq.roaming.allFields
import io.github.bbzq.roaming.allMethods
import io.github.bbzq.roaming.callMethod
import io.github.bbzq.roaming.from
import io.github.bbzq.roaming.hookBefore
import io.github.bbzq.roaming.replace
import org.json.JSONObject
import java.lang.reflect.Method

class FreeCopyHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        var count = 0
        count += hookDescCopy()
        count += hookKnownLongClickListeners()
        count += hookTargetedLongClickListener()
        count += hookConversation()
        log("startHook: FreeCopy, methods=$count")
    }

    private fun hookDescCopy(): Int {
        var count = 0
        DESC_COPY_CLASSES.mapNotNull { it.from(classLoader) }
            .distinct()
            .forEach { type ->
                val methods = type.allMethods()
                    .filter { method ->
                        method.parameterTypes.any { View::class.java.isAssignableFrom(it) } &&
                            method.parameterTypes.any { ClickableSpan::class.java.isAssignableFrom(it) }
                    }
                    .distinctBy(Method::toGenericString)
                    .toList()
                methods.forEach { method ->
                    env.replace(method) { param ->
                        if (!isRemoveDirectCopyEnabled()) return@replace param.invokeOriginalMethod()
                        if (isEnhancedCopyEnabled()) {
                            val view = param.args.firstIsInstanceOrNull<View>()
                            val text = findTextOnTarget(param.thisObject) ?: findText(view)
                            if (!text.isNullOrBlank()) showCopyDialog(view?.context ?: findContext(param.thisObject), text)
                        }
                        defaultValue(method.returnType)
                    }
                }
                count += methods.size
            }
        return count
    }

    private fun hookKnownLongClickListeners(): Int {
        var count = 0
        LONG_CLICK_CLASSES.mapNotNull { it.from(classLoader) }
            .distinct()
            .forEach { type ->
                val methods = type.allMethods()
                    .filter { method ->
                        method.name == "onLongClick" &&
                            method.parameterTypes.contentEquals(arrayOf(View::class.java))
                    }
                    .distinctBy(Method::toGenericString)
                    .toList()
                methods.forEach { method ->
                    env.replace(method) { param ->
                        handleCopyLongClick(param, method)
                    }
                }
                count += methods.size
            }
        return count
    }

    private fun hookTargetedLongClickListener(): Int {
        val method = View::class.java.getDeclaredMethod(
            "setOnLongClickListener",
            View.OnLongClickListener::class.java,
        )
        env.hookBefore(method) { param ->
            val listener = param.args.firstOrNull() as? View.OnLongClickListener ?: return@hookBefore
            if (listener is CopyLongClickWrapper) return@hookBefore
            val view = param.thisObject as? View ?: return@hookBefore
            if (!isCopyLongClickSource(view, listener)) return@hookBefore
            param.args[0] = CopyLongClickWrapper(listener)
        }
        return 1
    }

    private fun hookConversation(): Int {
        var count = 0
        CONVERSATION_ACTIVITIES.mapNotNull { it.from(classLoader) }
            .distinct()
            .forEach { activityClass ->
                val methods = activityClass.allMethods()
                    .filter { it.parameterCount == 8 }
                    .distinctBy(Method::toGenericString)
                    .toList()
                methods.forEach { method ->
                    env.hookBefore(method) { param ->
                        if (!isRemoveDirectCopyEnabled()) return@hookBefore
                        if (!isCopyAction(param.args.getOrNull(7), param.args.firstOrNull())) return@hookBefore

                        val activity = param.thisObject as? Activity ?: return@hookBefore
                        val message = param.args.getOrNull(1) ?: return@hookBefore
                        val text = extractMessageText(message)
                        if (isEnhancedCopyEnabled() && !text.isNullOrBlank()) {
                            showCopyDialog(activity, text)
                        }
                        param.args.getOrNull(6)?.callMethod("dismiss")
                        param.result = null
                    }
                }
                count += methods.size
            }
        return count
    }

    private fun handleCopyLongClick(param: MethodHookParam, method: Method): Any? {
        if (!isRemoveDirectCopyEnabled()) return param.invokeOriginalMethod()
        if (isEnhancedCopyEnabled()) {
            val view = param.args.firstIsInstanceOrNull<View>()
            val text = findText(view) ?: findTextOnTarget(param.thisObject)
            if (!text.isNullOrBlank()) showCopyDialog(view?.context ?: findContext(param.thisObject), text)
        }
        return if (method.returnType == Boolean::class.javaPrimitiveType ||
            method.returnType == Boolean::class.javaObjectType
        ) {
            true
        } else {
            defaultValue(method.returnType)
        }
    }

    private inner class CopyLongClickWrapper(
        private val original: View.OnLongClickListener,
    ) : View.OnLongClickListener {
        override fun onLongClick(view: View): Boolean {
            if (!isRemoveDirectCopyEnabled()) return original.onLongClick(view)
            if (isEnhancedCopyEnabled()) {
                val text = findText(view) ?: findTextOnTarget(original)
                if (!text.isNullOrBlank()) showCopyDialog(view.context, text)
            }
            return true
        }
    }

    private fun isRemoveDirectCopyEnabled(): Boolean =
        ModuleSettings.isDisableLongPressCopyEnabled(prefs)

    private fun isEnhancedCopyEnabled(): Boolean =
        isRemoveDirectCopyEnabled() && ModuleSettings.isEnhanceLongPressCopyEnabled(prefs)

    private fun isCopyLongClickSource(view: View, listener: View.OnLongClickListener): Boolean {
        val listenerName = listener.javaClass.name
        if (COPY_LISTENER_TOKENS.any { listenerName.contains(it, ignoreCase = true) }) return true

        val viewName = view.javaClass.name
        if (COPY_VIEW_CLASS_TOKENS.any { viewName.contains(it, ignoreCase = true) }) return true

        val idName = view.resourceEntryNameOrNull()
        if (idName in COPYABLE_VIEW_IDS) return true

        return view is ViewGroup && COPYABLE_VIEW_IDS.any { name ->
            val id = view.resources.getIdentifier(name, "id", view.context.packageName)
            id != 0 && view.findViewById<View>(id) != null
        }
    }

    private fun isCopyAction(action: Any?, firstArg: Any?): Boolean {
        if (action == null) return false
        if (action == firstArg) return true
        return action.toString().contains("COPY", ignoreCase = true)
    }

    private fun findText(view: View?): CharSequence? {
        if (view == null) return null

        if (view is TextView) {
            findTextOnTarget(view)?.let { return it }
            view.text.normalizeCopyText()?.let { return it }
        }

        val group = view as? ViewGroup ?: return findTextOnTarget(view)
        COPYABLE_VIEW_IDS.forEach { name ->
            val id = group.resources.getIdentifier(name, "id", group.context.packageName)
            if (id != 0) {
                findText(group.findViewById(id))?.let { return it }
            }
        }
        for (index in 0 until group.childCount) {
            findText(group.getChildAt(index))?.let { return it }
        }
        return findTextOnTarget(view)
    }

    private fun findTextOnTarget(target: Any?): CharSequence? {
        if (target == null) return null
        val values = target.javaClass.allFields()
            .asIterable()
            .mapNotNull { field -> runCatching { field.get(target) }.getOrNull() }

        values.filterIsInstance<SpannableStringBuilder>()
            .mapNotNull { it.normalizeCopyText() }
            .maxByOrNull { it.length }
            ?.let { return it }

        return values.filterIsInstance<CharSequence>()
            .mapNotNull { it.normalizeCopyText() }
            .maxByOrNull { it.length }
    }

    private fun findContext(target: Any?): Context? {
        if (target is Context) return target
        if (target is View) return target.context
        if (target == null) return null
        return target.javaClass.allFields()
            .firstNotNullOfOrNull { field ->
                runCatching { field.get(target) as? Context }.getOrNull()
            }
    }

    private fun extractMessageText(message: Any): String? {
        val raw = message.callMethod("getContentString") as? String ?: return null
        return runCatching {
            val json = JSONObject(raw)
            json.optString("content").ifBlank {
                buildList {
                    json.optString("title").takeIf(String::isNotBlank)?.let(::add)
                    json.optString("text").takeIf(String::isNotBlank)?.let(::add)
                    json.optJSONArray("modules")?.let { modules ->
                        for (index in 0 until modules.length()) {
                            val item = modules.optJSONObject(index) ?: continue
                            listOf(item.optString("title"), item.optString("detail"))
                                .filter(String::isNotBlank)
                                .joinToString("\uff1a")
                                .takeIf(String::isNotBlank)
                                ?.let(::add)
                        }
                    }
                }.joinToString("\n")
            }
        }.getOrElse { raw.takeIf(String::isNotBlank) }
    }

    private fun showCopyDialog(context: Context?, text: CharSequence) {
        val activity = context as? Activity ?: return
        activity.runOnUiThread {
            val themeId = activity.resources.getIdentifier("AppTheme.Dialog.Alert", "style", activity.packageName)
            val builder = if (themeId != 0) {
                AlertDialog.Builder(activity, themeId)
            } else {
                AlertDialog.Builder(activity)
            }
            val dialog = builder
                .setTitle("\u81ea\u7531\u590d\u5236\u5185\u5bb9")
                .setMessage(text)
                .setPositiveButton("\u5206\u4eab") { _, _ ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text.toString())
                    }
                    activity.startActivity(Intent.createChooser(intent, "\u5206\u4eab\u6587\u672c"))
                }
                .setNeutralButton("\u590d\u5236\u5168\u90e8") { _, _ ->
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("bbzq_copy", text))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            dialog.findViewById<TextView>(android.R.id.message)?.apply {
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
            }
        }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
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

    private fun View.resourceEntryNameOrNull(): String? =
        runCatching { resources.getResourceEntryName(id) }.getOrNull()

    private fun CharSequence?.normalizeCopyText(): String? {
        val value = this?.toString()
            ?.replace(COPY_SUFFIX_REGEX, "")
            ?.trim()
            ?: return null
        return value.takeIf { it.isNotBlank() }
    }

    private inline fun <reified T> List<Any?>.firstIsInstanceOrNull(): T? =
        firstOrNull { it is T } as? T

    private companion object {
        private val COPY_SUFFIX_REGEX = Regex("""\s*(\u5c55\u5f00|\u6536\u8d77|\u5168\u6587)$""")

        private val CONVERSATION_ACTIVITIES = listOf(
            "com.bilibili.bplus.p5162im.conversation.ConversationActivity",
            "com.bilibili.bplus.p5166im.conversation.ConversationActivity",
            "com.bilibili.bplus.im.conversation.ConversationActivity",
        )

        private val DESC_COPY_CLASSES = listOf(
            "com.bilibili.p5797ship.theseus.p5838ugc.intro.ugcheadline.UgcIntroductionComponent",
            "com.bilibili.ship.theseus.ugc.intro.ugcheadline.UgcIntroductionComponent",
            "com.p6334mall.videodetail.p6444vd.p6451ugc.intro.ugcheadline.UgcIntroductionComponent",
            "com.p6338mall.videodetail.p6448vd.p6455ugc.intro.ugcheadline.UgcIntroductionComponent",
            "tv.danmaku.bili.ui.video.section.info.VideoDescCopyHelper",
            "com.bilibili.video.story.StoryTextCopyHelper",
            "com.bilibili.app.comm.comment2.helper.TextCopyHelper",
        )

        private val LONG_CLICK_CLASSES = listOf(
            "com.bilibili.app.comm.comment2.widget.CommentExpandableTextView\$OnLongClickListener",
            "com.bilibili.app.comment3.ui.widget.CommentLongClickListener",
        )

        private val COPY_LISTENER_TOKENS = listOf(
            ".comment",
            ".followinglist.",
            ".theseus.",
            ".videodetail.",
            ".conversation.",
            "Comment",
            "Copy",
            "Desc",
            "Opus",
            "RichText",
        )

        private val COPY_VIEW_CLASS_TOKENS = listOf(
            "ExpandableTextView",
            "EllipsizingTextView",
            "RichText",
            "Comment",
            "Opus",
        )

        private val COPYABLE_VIEW_IDS = listOf(
            "message",
            "comment_message",
            "dy_card_text",
            "dy_opus_paragraph_desc",
            "dy_opus_paragraph_title",
            "dy_opus_copy_right_id",
            "dy_opus_paragraph_text",
        )
    }
}
