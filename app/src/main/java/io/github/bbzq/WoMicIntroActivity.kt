package io.github.bbzq

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class WoMicIntroActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F6F7F8"))
            setPadding(dp(20), dp(24), dp(20), dp(24))
            addView(createTitle())
            addView(
                createCard(
                    "WO Mic 解锁",
                    "面向 WO Mic 的解锁 Hook：伪造订阅付费状态、解锁高级音量拖动、拦截 AdMob 广告加载。混淆类名与 WO Mic 版本绑定。",
                ),
            )
            addView(
                createCard(
                    "下载 WO Mic 5.3",
                    "点击跳转到 Telegram 频道下载已适配的 WO Mic 5.3 安装包。",
                ) {
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/NPatch_HS/126541")),
                        )
                    }
                },
            )
        }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#F6F7F8"))
                addView(
                    content,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
    }

    private fun createTitle(): TextView {
        return TextView(this).apply {
            text = "WO Mic 解锁"
            textSize = 28f
            setTextColor(Color.parseColor("#111111"))
            setPadding(0, 0, 0, dp(16))
        }
    }

    private fun createCard(title: String, body: String, onClick: (() -> Unit)? = null): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(12)
            }

            addView(TextView(this@WoMicIntroActivity).apply {
                text = title
                textSize = 15f
                setTextColor(Color.parseColor("#FB7299"))
            })
            addView(TextView(this@WoMicIntroActivity).apply {
                text = body
                textSize = 16f
                setTextColor(Color.parseColor("#222222"))
                setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
