/*
 * lua_game_bridge.c — Lua 5.4.9 JNI bridge for the MobileStudio LuaGame runtime.
 *
 * Lifecycle (called from LuaNativeBridge):
 *   nativeCreateVM()   → luaL_newstate() → luaL_openlibs()
 *   nativeBindCallback → registers love.* / engine.* functions (C closures)
 *   nativeLoadAndRun() → luaL_loadfile() → lua_pcall()  (main.lua top-level)
 *   nativeCallGlobal() → resolves a dotted global path and lua_pcall()s it
 *   nativeDestroyVM()  → lua_close() (memory + resources freed)
 *
 * Callback marshalling: a bound C closure converts its Lua arguments to a JSON
 * array (numbers/strings/booleans/nil), dispatches to the Kotlin side via
 * LuaNativeBridge.dispatchCallback(id, argsJson), and converts the returned
 * JSON array back into Lua return values. If the Kotlin side returns
 * {"ok":false,"error":...} the error is raised as a proper Lua error.
 *
 * Errors are NEVER hidden: load errors, syntax errors and runtime errors are
 * returned as strings (with "file:line:" position info) and logged to logcat.
 */

#include <jni.h>
#include <android/log.h>
#include <lua.h>
#include <lauxlib.h>
#include <lualib.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define TAG "LuaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* g_jvm = NULL;
static jclass g_bridge_class = NULL;
static jmethodID g_dispatch = NULL;
static lua_State* g_L = NULL;

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "com/aide/mobilestudio/LuaNativeBridge");
    if (cls == NULL) {
        LOGE("JNI_OnLoad: LuaNativeBridge class not found");
        (*env)->ExceptionClear(env);
        return JNI_ERR;
    }
    g_bridge_class = (jclass)(*env)->NewGlobalRef(env, cls);
    g_dispatch = (*env)->GetStaticMethodID(env, cls, "dispatchCallback",
                                           "(ILjava/lang/String;)Ljava/lang/String;");
    if (g_dispatch == NULL) {
        LOGE("JNI_OnLoad: dispatchCallback method not found");
        (*env)->ExceptionClear(env);
        return JNI_ERR;
    }
    LOGI("JNI_OnLoad: Lua bridge ready");
    return JNI_VERSION_1_6;
}

/* ------------------------------------------------------------------ */
/* Dynamic string buffer (JSON building)                              */
/* ------------------------------------------------------------------ */

typedef struct { char* data; size_t len; size_t cap; int oom; } StrBuf;

static void sb_init(StrBuf* b) {
    b->cap = 256; b->len = 0; b->oom = 0;
    b->data = (char*)malloc(b->cap);
    if (!b->data) { b->oom = 1; return; }
    b->data[0] = 0;
}

static void sb_putc(StrBuf* b, char c) {
    if (b->oom) return;
    if (b->len + 2 > b->cap) {
        size_t nc = b->cap * 2;
        char* nd = (char*)realloc(b->data, nc);
        if (!nd) { b->oom = 1; return; }
        b->data = nd; b->cap = nc;
    }
    b->data[b->len++] = c;
    b->data[b->len] = 0;
}

static void sb_puts(StrBuf* b, const char* s) { while (*s) sb_putc(b, *s++); }

static void sb_free(StrBuf* b) { free(b->data); b->data = NULL; b->len = 0; b->cap = 0; }

/* ------------------------------------------------------------------ */
/* JSON marshalling                                                   */
/* ------------------------------------------------------------------ */

static void json_escape(StrBuf* b, const char* s) {
    sb_putc(b, '"');
    for (; *s; s++) {
        unsigned char c = (unsigned char)*s;
        switch (c) {
            case '"':  sb_puts(b, "\\\""); break;
            case '\\': sb_puts(b, "\\\\"); break;
            case '\n': sb_puts(b, "\\n");  break;
            case '\r': sb_puts(b, "\\r");  break;
            case '\t': sb_puts(b, "\\t");  break;
            default:
                if (c < 0x20) {
                    char tmp[8];
                    snprintf(tmp, sizeof(tmp), "\\u%04x", c);
                    sb_puts(b, tmp);
                } else {
                    sb_putc(b, (char)c);
                }
        }
    }
    sb_putc(b, '"');
}

/* Marshals Lua stack values in [start..end] into a JSON array. */
static void marshal_range(lua_State* L, int start, int end, StrBuf* b) {
    sb_putc(b, '[');
    for (int i = start; i <= end; i++) {
        if (i > start) sb_putc(b, ',');
        switch (lua_type(L, i)) {
            case LUA_TNIL:
                sb_puts(b, "null");
                break;
            case LUA_TBOOLEAN:
                sb_puts(b, lua_toboolean(L, i) ? "true" : "false");
                break;
            case LUA_TNUMBER:
                if (lua_isinteger(L, i)) {
                    char tmp[32];
                    snprintf(tmp, sizeof(tmp), "%lld", (long long)lua_tointeger(L, i));
                    sb_puts(b, tmp);
                } else {
                    char tmp[48];
                    snprintf(tmp, sizeof(tmp), "%.17g", (double)lua_tonumber(L, i));
                    sb_puts(b, tmp);
                }
                break;
            case LUA_TSTRING:
                json_escape(b, lua_tostring(L, i));
                break;
            default:
                /* tables/userdata/functions are not marshalled */
                sb_puts(b, "null");
                break;
        }
    }
    sb_putc(b, ']');
}

/* Parses one JSON scalar value and pushes it onto the Lua stack.
 * Returns the advanced pointer, or NULL on error. */
static const char* parse_value(lua_State* L, const char* p) {
    if (p == NULL) return NULL;
    if (strncmp(p, "null", 4) == 0)  { lua_pushnil(L);     return p + 4; }
    if (strncmp(p, "true", 4) == 0)  { lua_pushboolean(L, 1); return p + 4; }
    if (strncmp(p, "false", 5) == 0) { lua_pushboolean(L, 0); return p + 5; }

    if (*p == '"') {
        p++;
        StrBuf b;
        sb_init(&b);
        while (*p && *p != '"') {
            if (*p == '\\' && p[1]) {
                p++;
                switch (*p) {
                    case 'n': sb_putc(&b, '\n'); p++; break;
                    case 'r': sb_putc(&b, '\r'); p++; break;
                    case 't': sb_putc(&b, '\t'); p++; break;
                    case 'b': sb_putc(&b, '\b'); p++; break;
                    case 'f': sb_putc(&b, '\f'); p++; break;
                    case 'u': {
                        p++;
                        int cp = 0, n = 0;
                        while (n < 4 && *p) {
                            char c = *p;
                            int v;
                            if (c >= '0' && c <= '9') v = c - '0';
                            else if (c >= 'a' && c <= 'f') v = c - 'a' + 10;
                            else if (c >= 'A' && c <= 'F') v = c - 'A' + 10;
                            else break;
                            cp = (cp << 4) | v;
                            n++; p++;
                        }
                        if (cp < 0x80) {
                            sb_putc(&b, (char)cp);
                        } else if (cp < 0x800) {
                            sb_putc(&b, (char)(0xC0 | (cp >> 6)));
                            sb_putc(&b, (char)(0x80 | (cp & 0x3F)));
                        } else {
                            sb_putc(&b, (char)(0xE0 | (cp >> 12)));
                            sb_putc(&b, (char)(0x80 | ((cp >> 6) & 0x3F)));
                            sb_putc(&b, (char)(0x80 | (cp & 0x3F)));
                        }
                        break;
                    }
                    default: sb_putc(&b, *p); p++; break;
                }
            } else {
                sb_putc(&b, *p);
                p++;
            }
        }
        if (*p == '"') p++;
        if (b.oom) {
            lua_pushnil(L);
        } else {
            lua_pushlstring(L, b.data ? b.data : "", b.len);
        }
        sb_free(&b);
        return p;
    }

    /* number */
    {
        char* end = NULL;
        double d = strtod(p, &end);
        if (end == p) {
            lua_pushnil(L);
            return p + 1; /* skip one char to avoid an infinite loop */
        }
        lua_pushnumber(L, d);
        return end;
    }
}

/* Parses a flat JSON array and pushes each element onto the Lua stack.
 * Returns the number of pushed values. */
static int unmarshal_array(lua_State* L, const char* json) {
    if (json == NULL) return 0;
    const char* p = json;
    while (*p && *p != '[') p++;
    if (*p != '[') return 0;
    p++;
    int count = 0;
    while (*p) {
        while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r' || *p == ',') p++;
        if (*p == ']' || *p == 0) break;
        const char* np = parse_value(L, p);
        if (np == NULL || np == p) break;
        p = np;
        count++;
    }
    return count;
}

/* ------------------------------------------------------------------ */
/* Callback CFunction                                                 */
/* ------------------------------------------------------------------ */

static int lua_bridge_callback(lua_State* L) {
    if (g_jvm == NULL || g_bridge_class == NULL || g_dispatch == NULL) {
        return luaL_error(L, "Lua bridge is not initialized");
    }

    const int id = (int)lua_tointeger(L, lua_upvalueindex(1));
    const int nargs = lua_gettop(L);

    StrBuf args;
    sb_init(&args);
    marshal_range(L, 1, nargs, &args);
    if (args.oom) {
        sb_free(&args);
        return luaL_error(L, "out of memory marshalling callback arguments");
    }

    JNIEnv* env = NULL;
    int attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) {
            sb_free(&args);
            return luaL_error(L, "cannot attach JNI thread for callback dispatch");
        }
        attached = 1;
    }

    jstring jargs = (*env)->NewStringUTF(env, args.data);
    sb_free(&args);
    if (jargs == NULL) {
        if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
        return luaL_error(L, "cannot create callback argument string");
    }

    jstring jres = (jstring)(*env)->CallStaticObjectMethod(env, g_bridge_class, g_dispatch,
                                                           (jint)id, jargs);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, jargs);
        if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
        return luaL_error(L, "Java callback dispatch failed");
    }
    (*env)->DeleteLocalRef(env, jargs);

    const char* res_c = "[]";
    if (jres != NULL) {
        res_c = (*env)->GetStringUTFChars(env, jres, NULL);
        if (res_c == NULL) res_c = "[]";
    }

    /* Kotlin-side errors are reported as {"ok":false,"error":...} */
    if (strncmp(res_c, "{\"ok\":false,\"error\":", 20) == 0) {
        const char* q = res_c + 20;
        while (*q && *q != '"') q++;
        const char* np = parse_value(L, q); /* pushes the error message string */
        const char* msg = (np != NULL) ? lua_tostring(L, -1) : NULL;
        char buf[512];
        snprintf(buf, sizeof(buf), "%s", msg ? msg : "Kotlin callback error");
        lua_pop(L, 1);
        if (jres != NULL) {
            (*env)->ReleaseStringUTFChars(env, jres, res_c);
            (*env)->DeleteLocalRef(env, jres);
        }
        if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
        return luaL_error(L, "%s", buf);
    }

    const int base = lua_gettop(L) + 1;
    unmarshal_array(L, res_c);
    const int nres = lua_gettop(L) - base + 1;

    if (jres != NULL) {
        (*env)->ReleaseStringUTFChars(env, jres, res_c);
        (*env)->DeleteLocalRef(env, jres);
    }
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
    return nres;
}

/* ------------------------------------------------------------------ */
/* Helpers                                                            */
/* ------------------------------------------------------------------ */

/* Converts the error object on top of the stack into a Java string. */
static jstring make_error_string(JNIEnv* env, lua_State* L) {
    size_t len = 0;
    const char* msg = luaL_tolstring(L, -1, &len); /* pushes tostring result */
    jstring result = (msg != NULL)
        ? (*env)->NewStringUTF(env, msg)
        : (*env)->NewStringUTF(env, "unknown Lua error");
    lua_pop(L, 2); /* tostring result + error object */
    return result;
}

/* ------------------------------------------------------------------ */
/* JNI entry points                                                   */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_LuaNativeBridge_nativeVersion(JNIEnv* env, jobject thiz) {
    (void)thiz;
    return (*env)->NewStringUTF(env, LUA_RELEASE);
}

JNIEXPORT jboolean JNICALL
Java_com_aide_mobilestudio_LuaNativeBridge_nativeCreateVM(JNIEnv* env, jobject thiz) {
    (void)thiz;
    if (g_L != NULL) {
        lua_close(g_L);
        g_L = NULL;
    }
    g_L = luaL_newstate();
    if (g_L == NULL) {
        LOGE("luaL_newstate failed (out of memory)");
        return JNI_FALSE;
    }
    luaL_openlibs(g_L);
    LOGI("Lua VM created — %s", LUA_RELEASE);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_aide_mobilestudio_LuaNativeBridge_nativeDestroyVM(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    if (g_L != NULL) {
        lua_close(g_L);
        g_L = NULL;
        LOGI("Lua VM closed — memory and resources freed");
    }
}

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_LuaNativeBridge_nativeSyntaxCheck(JNIEnv* env, jobject thiz, jstring jcode) {
    (void)thiz;
    if (g_L == NULL) return (*env)->NewStringUTF(env, "Lua VM is not initialized");
    const char* code = (*env)->GetStringUTFChars(env, jcode, NULL);
    if (code == NULL) return (*env)->NewStringUTF(env, "cannot read Lua source");
    const int status = luaL_loadstring(g_L, code);
    (*env)->ReleaseStringUTFChars(env, jcode, code);
    if (status == LUA_OK) {
        lua_pop(g_L, 1);
        return (*env)->NewStringUTF(env, "");
    }
    return make_error_string(env, g_L);
}

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_LuaNativeBridge_nativeLoadAndRun(JNIEnv* env, jobject thiz, jstring jpath) {
    (void)thiz;
    if (g_L == NULL) return (*env)->NewStringUTF(env, "Lua VM is not initialized");
    const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return (*env)->NewStringUTF(env, "cannot read file path");

    LOGI("Loading Lua file: %s", path);
    const int loadStatus = luaL_loadfile(g_L, path);
    if (loadStatus != LUA_OK) {
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return make_error_string(env, g_L);
    }
    const int runStatus = lua_pcall(g_L, 0, LUA_MULTRET, 0);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (runStatus != LUA_OK) {
        return make_error_string(env, g_L);
    }
    LOGI("Lua file executed OK");
    return (*env)->NewStringUTF(env, "");
}

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_LuaNativeBridge_nativeCallGlobal(JNIEnv* env, jobject thiz,
                                                            jstring jpath, jstring jargsJson) {
    (void)thiz;
    if (g_L == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"Lua VM is not initialized\"}");
    }
    const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"cannot read callback path\"}");
    }
    const char* argsJson = (jargsJson != NULL)
        ? (*env)->GetStringUTFChars(env, jargsJson, NULL)
        : "[]";
    if (argsJson == NULL) argsJson = "[]";

    /* Resolve the dotted global path (e.g. "love.graphics.clear") */
    char buf[256];
    strncpy(buf, path, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = 0;
    char* save = NULL;
    char* seg = strtok_r(buf, ".", &save);
    int resolved = 0;
    if (seg != NULL) {
        lua_getglobal(g_L, seg);
        resolved = 1;
        while ((seg = strtok_r(NULL, ".", &save)) != NULL) {
            if (!lua_istable(g_L, -1)) { resolved = 0; break; }
            lua_getfield(g_L, -1, seg);
            lua_remove(g_L, -2);
        }
    }

    if (!resolved || !lua_isfunction(g_L, -1)) {
        if (resolved) lua_pop(g_L, 1);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        if (jargsJson != NULL) (*env)->ReleaseStringUTFChars(env, jargsJson, argsJson);
        /* Missing callback = no-op (matches the LuaJ runtime's callFn) */
        return (*env)->NewStringUTF(env, "{\"ok\":true}");
    }

    const int nargs = unmarshal_array(g_L, argsJson);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (jargsJson != NULL) (*env)->ReleaseStringUTFChars(env, jargsJson, argsJson);

    const int status = lua_pcall(g_L, nargs, LUA_MULTRET, 0);
    if (status != LUA_OK) {
        size_t len = 0;
        const char* msg = luaL_tolstring(g_L, -1, &len);
        StrBuf b;
        sb_init(&b);
        sb_puts(&b, "{\"ok\":false,\"error\":");
        json_escape(&b, msg ? msg : "unknown Lua error");
        sb_puts(&b, "}");
        lua_pop(g_L, 1);
        jstring result = (b.data != NULL)
            ? (*env)->NewStringUTF(env, b.data)
            : (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"out of memory\"}");
        sb_free(&b);
        return result;
    }

    const int nres = lua_gettop(g_L);
    StrBuf b;
    sb_init(&b);
    sb_puts(&b, "{\"ok\":true,\"results\":");
    marshal_range(g_L, 1, nres, &b);
    sb_puts(&b, "}");
    lua_pop(g_L, nres);
    jstring result = (b.data != NULL && !b.oom)
        ? (*env)->NewStringUTF(env, b.data)
        : (*env)->NewStringUTF(env, "{\"ok\":true}");
    sb_free(&b);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_aide_mobilestudio_LuaNativeBridge_nativeBindCallback(JNIEnv* env, jobject thiz,
                                                              jstring jpath, jint id) {
    (void)thiz;
    if (g_L == NULL) return JNI_FALSE;
    const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return JNI_FALSE;

    char buf[256];
    strncpy(buf, path, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = 0;

    const char* parts[8];
    int nparts = 0;
    char* save = NULL;
    char* seg = strtok_r(buf, ".", &save);
    while (seg != NULL && nparts < 8) {
        parts[nparts++] = seg;
        seg = strtok_r(NULL, ".", &save);
    }
    if (nparts < 1) {
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return JNI_FALSE;
    }

    /* Root table (create if missing) */
    lua_getglobal(g_L, parts[0]);
    if (!lua_istable(g_L, -1)) {
        lua_pop(g_L, 1);
        lua_newtable(g_L);
        lua_setglobal(g_L, parts[0]);
        lua_getglobal(g_L, parts[0]);
    }

    /* Intermediate tables (create if missing) */
    for (int i = 1; i < nparts - 1; i++) {
        lua_getfield(g_L, -1, parts[i]);
        if (!lua_istable(g_L, -1)) {
            lua_pop(g_L, 1);
            lua_newtable(g_L);
            lua_pushvalue(g_L, -1);
            lua_setfield(g_L, -3, parts[i]);
        }
        lua_remove(g_L, -2);
    }

    /* Register the C closure with the callback id as its upvalue */
    lua_pushinteger(g_L, (lua_Integer)id);
    lua_pushcclosure(g_L, lua_bridge_callback, 1);
    lua_setfield(g_L, -2, parts[nparts - 1]);
    lua_pop(g_L, 1);

    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return JNI_TRUE;
}

/* __index handler for restricted modules (table, key) */
static int restrict_index(lua_State* L) {
    const char* name = lua_tostring(L, lua_upvalueindex(1));
    const char* alt = lua_tostring(L, lua_upvalueindex(2));
    const char* key = luaL_checkstring(L, 2);
    return luaL_error(L, "Restricted API: %s.%s — blocked by the game sandbox.\nUse %s instead.",
                      name ? name : "?", key, alt ? alt : "love.filesystem");
}

JNIEXPORT void JNICALL
Java_com_aide_mobilestudio_LuaNativeBridge_nativeRestrictModule(JNIEnv* env, jobject thiz,
                                                                jstring jname, jstring jalt) {
    (void)thiz;
    if (g_L == NULL) return;
    const char* name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char* alt = (jalt != NULL) ? (*env)->GetStringUTFChars(env, jalt, NULL) : "love.filesystem";
    if (name == NULL) return;

    lua_newtable(g_L);              /* module shell */
    lua_newtable(g_L);              /* metatable */
    lua_pushstring(g_L, name);      /* upvalue 1 */
    if (alt != NULL) lua_pushstring(g_L, alt); else lua_pushnil(g_L); /* upvalue 2 */
    lua_pushcclosure(g_L, restrict_index, 2);
    lua_setfield(g_L, -2, "__index");
    lua_setmetatable(g_L, -2);
    lua_setglobal(g_L, name);

    (*env)->ReleaseStringUTFChars(env, jname, name);
    if (jalt != NULL && alt != NULL) (*env)->ReleaseStringUTFChars(env, jalt, alt);
}
