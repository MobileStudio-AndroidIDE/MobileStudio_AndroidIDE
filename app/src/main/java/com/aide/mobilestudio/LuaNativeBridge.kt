package com.aide.mobilestudio

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * LuaNativeBridge — JNI bridge to the embedded official Lua 5.4.9 VM
 * (liblua_game.so, arm64-v8a).
 *
 * Execution flow:
 *   nativeCreateVM()   → luaL_newstate() → luaL_openlibs()
 *   nativeBindCallback → love.* / engine.* / game API registration
 *   nativeLoadAndRun() → main.lua load → lua_pcall → game loop
 *   nativeDestroyVM()  → lua_close() (memory + resources freed)
 *
 * Lua functions bound via [registerCallback] marshal their arguments to a JSON
 * array (numbers/strings/booleans/nil), dispatch to the Kotlin callback, and the
 * callback's return values are marshalled back into Lua return values.
 * A callback that returns {"ok":false,"error":...} raises a proper Lua error.
 */
object LuaNativeBridge {
    private const val TAG = "LuaNativeBridge"

    /** True when liblua_game.so loaded successfully. */
    val available: Boolean = try {
        System.loadLibrary("lua_game")
        true
    } catch (e: Throwable) {
        Log.e(TAG, "liblua_game.so load failed — native Lua runtime unavailable", e)
        false
    }

    external fun nativeVersion(): String
    external fun nativeCreateVM(): Boolean
    external fun nativeDestroyVM()
    external fun nativeSyntaxCheck(code: String): String
    external fun nativeLoadAndRun(path: String): String
    external fun nativeCallGlobal(path: String, argsJson: String): String
    external fun nativeBindCallback(path: String, id: Int): Boolean
    external fun nativeRestrictModule(name: String, alt: String)

    // ------------------------------------------------------------------
    // Kotlin callback registry
    // ------------------------------------------------------------------

    private val callbacks = HashMap<Int, (List<Any?>) -> List<Any?>>()
    private var nextCallbackId = 0

    fun registerCallback(fn: (List<Any?>) -> List<Any?>): Int = synchronized(callbacks) {
        val id = ++nextCallbackId
        callbacks[id] = fn
        id
    }

    fun unregisterAllCallbacks() = synchronized(callbacks) { callbacks.clear() }

    /** Invokes a registered callback directly (used for global aliases). */
    fun invokeRegistered(id: Int, args: List<Any?>): List<Any?> =
        synchronized(callbacks) { callbacks[id] }?.invoke(args) ?: emptyList()

    // ------------------------------------------------------------------
    // Structured global calls (used by the game loop: love.load/update/draw)
    // ------------------------------------------------------------------

    data class CallResult(val ok: Boolean, val results: List<Any?>, val error: String?)

    /** Calls a dotted global path (e.g. "love.update") with the given args. */
    fun callGlobalJson(path: String, args: List<Any?>): CallResult {
        if (!available) return CallResult(false, emptyList(), "native Lua runtime unavailable")
        val json = try {
            nativeCallGlobal(path, encodeResults(args))
        } catch (e: Throwable) {
            return CallResult(false, emptyList(), "native call failed: ${e.message}")
        }
        return try {
            val obj = JSONObject(json)
            val error = obj.optString("error", "").ifEmpty { null }
            val results = if (obj.has("results")) {
                val arr = obj.getJSONArray("results")
                (0 until arr.length()).map { i ->
                    when (val v = arr.get(i)) {
                        JSONObject.NULL -> null
                        is Boolean -> v
                        is Int -> v.toDouble()
                        is Long -> v.toDouble()
                        is Double -> v
                        else -> v.toString()
                    }
                }
            } else emptyList()
            CallResult(obj.optBoolean("ok", false), results, error)
        } catch (e: Throwable) {
            CallResult(false, emptyList(), "invalid call result: $json")
        }
    }

    /**
     * Called from native (lua_bridge_callback). Must NOT throw:
     * any Kotlin-side failure is surfaced to Lua as a proper runtime error.
     */
    @JvmStatic
    fun dispatchCallback(id: Int, argsJson: String): String {
        return try {
            val fn = synchronized(callbacks) { callbacks[id] } ?: return "[]"
            val results = fn(decodeArgs(argsJson))
            encodeResults(results)
        } catch (e: Throwable) {
            Log.e(TAG, "Lua callback $id failed", e)
            encodeError("Kotlin callback error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------
    // JSON marshalling helpers
    // ------------------------------------------------------------------

    private fun decodeArgs(json: String): List<Any?> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            when (val v = arr.get(i)) {
                JSONObject.NULL -> null
                is Boolean -> v
                is Int -> v.toDouble()
                is Long -> v.toDouble()
                is Double -> v
                else -> v.toString()
            }
        }
    }

    private fun encodeResults(results: List<Any?>): String {
        val arr = JSONArray()
        for (r in results) {
            when (r) {
                null -> arr.put(JSONObject.NULL)
                is Boolean -> arr.put(r)
                is Int -> arr.put(r.toDouble())
                is Long -> arr.put(r.toDouble())
                is Float -> arr.put(r.toDouble())
                is Double -> arr.put(r)
                else -> arr.put(r.toString())
            }
        }
        return arr.toString()
    }

    private fun encodeError(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}
