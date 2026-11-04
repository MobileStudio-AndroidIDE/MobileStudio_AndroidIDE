package com.aide.mobilestudio

import android.content.Context

object L10n {
    fun str(context: Context, resId: Int): String = context.getString(resId)

    fun str(context: Context, resId: Int, vararg args: Any?): String =
        context.getString(resId, *args)
}
