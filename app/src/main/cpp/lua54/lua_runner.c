/*
** lua_runner.c — MobileStudio standalone Lua 5.4 interpreter.
**
** The upstream distribution ships the interactive interpreter in `lua.c`,
** which is not vendored here (LuaGame embeds only the VM plus `luac`). This
** file provides an API-compatible subset of that CLI so that `lua` works as a
** regular shell command:
**
**   lua [options] [script [args]]
**
** Options:
**   -e stat      execute string 'stat'
**   -l mod       require library 'mod'
**   -v           show version information
**   -i           enter interactive mode after executing the script
**   -            execute stdin and stop processing options
**   -h, --help   show help
**
** With a script, `arg` is set so that arg[0] is the script name and
** arg[1..] the script arguments. Without a script: stdin is executed when it
** is not a terminal; an interactive prompt loop (with <eof> continuation)
** runs when it is.
**
** Exit status: 0 on success, 1 on errors (including usage errors).
*/

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

#define LUA_PROMPT "> "
#define LUA_PROMPT2 ">> "

#define MAX_ACTIONS 128

/* A pending -e / -l action, kept in command-line order. */
typedef struct Action {
    int kind;              /* 'e' = execute string, 'l' = require library */
    const char *value;
} Action;

static int report(lua_State *L, int status) {
    if (status != LUA_OK) {
        const char *msg = lua_tostring(L, -1);
        if (msg == NULL) {
            msg = "(error object is not a string)";
        }
        fprintf(stderr, "lua: %s\n", msg);
        lua_pop(L, 1);
    }
    return status;
}

static int dostring(lua_State *L, const char *s, const char *name) {
    int status = luaL_loadbuffer(L, s, strlen(s), name);
    if (status == LUA_OK) {
        status = lua_pcall(L, 0, 0, 0);
    }
    return report(L, status);
}

static int dofile(lua_State *L, const char *name) {
    int status = luaL_loadfile(L, name);   /* NULL name reads stdin */
    if (status == LUA_OK) {
        status = lua_pcall(L, 0, 0, 0);
    }
    return report(L, status);
}

static int dolibrary(lua_State *L, const char *name) {
    lua_getglobal(L, "require");
    lua_pushstring(L, name);
    int status = lua_pcall(L, 1, 0, 0);
    return report(L, status);
}

/*
** Returns 1 when a syntax error is caused by an unfinished input line
** (the message ends with LUA_QL("<eof>")).
*/
static int incomplete(lua_State *L, int status) {
    if (status == LUA_ERRSYNTAX) {
        size_t lmsg;
        const char *msg = lua_tolstring(L, -1, &lmsg);
        if (msg != NULL &&
            lmsg >= sizeof("'<eof>'") - 1 &&
            strcmp(msg + lmsg - (sizeof("'<eof>'") - 1), "'<eof>'") == 0) {
            lua_pop(L, 1);
            return 1;
        }
    }
    return 0;
}

/*
** Interactive loop. Lines are accumulated until they form a syntactically
** complete chunk, so multi-line statements (function ... end) work.
** Results of each chunk are printed with print().
*/
static int dotty(lua_State *L) {
    static char buffer[8192];
    int continuing = 0;

    buffer[0] = '\0';
    for (;;) {
        size_t blen;

        fputs(continuing ? LUA_PROMPT2 : LUA_PROMPT, stdout);
        fflush(stdout);

        char line[4096];
        if (fgets(line, (int)sizeof(line), stdin) == NULL) {
            fputc('\n', stdout);
            break;
        }
        if (line[0] == '\n' || line[0] == '\r' || line[0] == '\0') {
            if (!continuing) {
                continue;               /* ignore blank input lines */
            }
        }
        blen = strlen(buffer);
        if (blen + strlen(line) + 1 > sizeof(buffer)) {
            fprintf(stderr, "lua: input line too long\n");
            buffer[0] = '\0';
            continuing = 0;
            lua_settop(L, 0);
            continue;
        }
        strcat(buffer, line);           /* keeps the trailing newline */

        int status = luaL_loadbuffer(L, buffer, strlen(buffer), "=stdin");
        if (status == LUA_OK) {
            status = lua_pcall(L, 0, LUA_MULTRET, 0);
            if (status == LUA_OK) {
                int nres = lua_gettop(L);
                if (nres > 0) {
                    lua_getglobal(L, "print");
                    lua_insert(L, 1);
                    if (lua_pcall(L, nres, 0, 0) != LUA_OK) {
                        fprintf(stderr, "lua: %s\n", lua_tostring(L, -1));
                        lua_pop(L, 1);
                    }
                }
            } else {
                report(L, status);
            }
            buffer[0] = '\0';
            continuing = 0;
        } else if (incomplete(L, status)) {
            continuing = 1;             /* keep reading until complete */
        } else {
            report(L, status);
            buffer[0] = '\0';
            continuing = 0;
        }
        lua_settop(L, 0);
    }
    return LUA_OK;
}

static void show_version(void) {
    printf("%s\n", LUA_COPYRIGHT);
}

static void show_usage(const char *prog) {
    printf(
        "usage: %s [options] [script [args]]\n"
        "Available options are:\n"
        "  -e stat        execute string 'stat'\n"
        "  -l mod         require library 'mod'\n"
        "  -i             enter interactive mode after executing the script\n"
        "  -v             show version information\n"
        "  -              execute stdin and stop processing options\n"
        "  -h, --help     show this help\n",
        prog);
}

/*
** arg[0] = script name, arg[1..] = script arguments when a script is given;
** otherwise arg[0] is the interpreter name.
*/
static void createargtable(lua_State *L, char **argv, int argc, int script) {
    int i;

    if (script < 0) {
        lua_createtable(L, 1, 0);
        lua_pushstring(L, argv[0]);
        lua_rawseti(L, -2, 0);
    } else {
        int narg = argc - (script + 1);
        if (narg < 0) {
            narg = 0;
        }
        lua_createtable(L, script + 1, narg);
        for (i = script; i < argc; i++) {
            lua_pushstring(L, argv[i]);
            lua_rawseti(L, -2, i - script);
        }
    }
    lua_setglobal(L, "arg");
}

static int panic(lua_State *L) {
    fprintf(stderr, "lua: unprotected error: %s\n", lua_tostring(L, -1));
    fflush(stderr);
    exit(EXIT_FAILURE);
}

int main(int argc, char **argv) {
    int script = -1;              /* index of the script name in argv */
    int interactive = 0;
    int version = 0;
    Action actions[MAX_ACTIONS];
    int nactions = 0;
    int i;

    lua_State *L = luaL_newstate();
    if (L == NULL) {
        fprintf(stderr, "lua: cannot create state: not enough memory\n");
        return EXIT_FAILURE;
    }
    lua_atpanic(L, panic);
    luaL_openlibs(L);

    for (i = 1; i < argc; i++) {
        const char *arg = argv[i];
        int j;

        if (strcmp(arg, "--") == 0) {
            script = (i + 1 < argc) ? i + 1 : -1;
            break;
        }
        if (strcmp(arg, "--help") == 0) {
            show_usage(argv[0]);
            lua_close(L);
            return EXIT_SUCCESS;
        }
        if (arg[0] != '-' || arg[1] == '\0') {
            script = i;           /* first non-option: script (or "-") */
            break;
        }
        /* Short option cluster: -v, -i, -e code, -ecode, -l mod, ... */
        for (j = 1; arg[j] != '\0'; j++) {
            switch (arg[j]) {
                case 'v':
                    version = 1;
                    break;
                case 'i':
                    interactive = 1;
                    break;
                case 'h':
                    show_usage(argv[0]);
                    lua_close(L);
                    return EXIT_SUCCESS;
                case 'e':
                case 'l': {
                    int kind = arg[j];
                    const char *value = arg + j + 1;
                    if (value[0] == '\0') {
                        if (i + 1 >= argc) {
                            fprintf(stderr, "lua: option '-%c' needs a value\n",
                                    kind);
                            lua_close(L);
                            return EXIT_FAILURE;
                        }
                        value = argv[++i];
                    }
                    if (nactions >= MAX_ACTIONS) {
                        fprintf(stderr, "lua: too many -e/-l options\n");
                        lua_close(L);
                        return EXIT_FAILURE;
                    }
                    actions[nactions].kind = kind;
                    actions[nactions].value = value;
                    nactions++;
                    j = (int)strlen(arg) - 1;   /* value consumed the rest */
                    break;
                }
                default:
                    fprintf(stderr, "lua: unrecognized option '-%c'\n",
                            arg[j]);
                    show_usage(argv[0]);
                    lua_close(L);
                    return EXIT_FAILURE;
            }
        }
    }

    if (version) {
        show_version();
    }
    createargtable(L, argv, argc, script);

    for (i = 0; i < nactions; i++) {
        int status;
        if (actions[i].kind == 'e') {
            status = dostring(L, actions[i].value, "=(command line)");
        } else {
            status = dolibrary(L, actions[i].value);
        }
        if (status != LUA_OK) {
            lua_close(L);
            return EXIT_FAILURE;
        }
    }

    int status = LUA_OK;
    if (script >= 0) {
        const char *name = (strcmp(argv[script], "-") == 0) ? NULL
                                                            : argv[script];
        status = dofile(L, name);
        if (status == LUA_OK && interactive) {
            status = dotty(L);
        }
    } else if (nactions > 0 || version) {
        /*
         * Lua semantics: `lua -v` and `lua -e '...'` exit after running —
         * the REPL is entered only with -i. Falling through to dotty() here
         * made `lua -v` hang forever on a terminal (it waits for input).
         */
        if (interactive) {
            status = dotty(L);
        }
    } else if (!isatty(STDIN_FILENO)) {
        status = dofile(L, NULL);     /* piped stdin: run it as a script */
        if (status == LUA_OK && interactive) {
            status = dotty(L);
        }
    } else {
        status = dotty(L);            /* interactive REPL */
    }

    lua_close(L);
    return status == LUA_OK ? EXIT_SUCCESS : EXIT_FAILURE;
}
