package io.github.bbzq

import android.content.Context

object LinkerGuard {
    fun hasConflict(context: Context): Boolean = false

    fun triggerConflict(context: Context) {}
}
