package com.aide.mobilestudio

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object AssetUtils {
    private const val TAG = "AssetUtils"

    fun listAssets(context: Context, assetDir: String = ""): List<String> {
        return try {
            context.assets.list(assetDir)?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun assetExists(context: Context, assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun copyAsset(context: Context, assetPath: String, outFile: File, overwrite: Boolean = true): Boolean {
        if (!overwrite && outFile.exists()) return true
        return try {
            outFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { i ->
                FileOutputStream(outFile).use { o -> i.copyTo(o) }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "copyAsset failed: $assetPath -> ${outFile.absolutePath} (${e.message})")
            false
        }
    }
}
