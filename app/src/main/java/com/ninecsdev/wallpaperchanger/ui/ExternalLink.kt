package com.ninecsdev.wallpaperchanger.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri

private const val TAG = "ExternalLink"

/** Hands [url] to whatever app claims it, returning false when nothing does. */
fun openUrl(context: Context, url: String): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Nothing on this device opens $url", e)
        false
    }

fun openPlayStoreListing(context: Context) {
    val id = context.packageName
    if (!openUrl(context, "market://details?id=$id")) {
        openUrl(context, "https://play.google.com/store/apps/details?id=$id")
    }
}
