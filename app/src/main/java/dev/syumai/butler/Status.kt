package dev.syumai.butler

import android.content.Context

/** A localizable status line: resolved against a Context at display time so the OS locale always applies. */
class Status(val text: Int, vararg val args: Any) {
    fun resolve(context: Context): String = context.getString(text, *args)
}
