package com.aide.mobilestudio.ai.opencode.permission

data class PermissionRule(val permission: String, val action: String, val pattern: String)

object Permission {

    fun wildcardMatch(input: String, pattern: String): Boolean {
        val normalized = input.replace("\\", "/")
        val p = pattern.replace("\\", "/")
        val sb = StringBuilder()
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> {
                    if (!c.isLetterOrDigit() && c != '_' && c != '/' && c != ' ') sb.append('\\')
                    sb.append(c)
                }
            }
            i++
        }
        var regex = sb.toString()
        if (regex.endsWith(" .*")) regex = regex.slice(0 until regex.length - 3) + "( .*)?"
        return Regex("^$regex$", RegexOption.DOT_MATCHES_ALL).matches(normalized)
    }

    fun fromConfig(permission: org.json.JSONObject?): List<PermissionRule> {
        if (permission == null) return emptyList()
        val ruleset = mutableListOf<PermissionRule>()
        val keys = permission.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = permission.get(key)
            when (value) {
                is String -> ruleset.add(PermissionRule(key, value, "*"))
                is Boolean -> ruleset.add(PermissionRule(key, if (value) "allow" else "deny", "*"))
                is org.json.JSONObject -> {
                    val inner = value.keys()
                    while (inner.hasNext()) {
                        val pattern = inner.next()
                        val action = value.get(pattern)
                        val actionStr = when (action) {
                            is String -> action
                            is Boolean -> if (action) "allow" else "deny"
                            else -> "ask"
                        }
                        ruleset.add(PermissionRule(key, actionStr, expand(pattern)))
                    }
                }
            }
        }
        return ruleset
    }

    fun fromToolsConfig(tools: org.json.JSONObject?): List<PermissionRule> {
        if (tools == null) return emptyList()
        val ruleset = mutableListOf<PermissionRule>()
        val keys = tools.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val enabled = tools.optBoolean(key, true)
            val permissionKey = when (key) {
                "write", "edit", "patch" -> "edit"
                else -> key
            }
            ruleset.add(PermissionRule(permissionKey, if (enabled) "allow" else "deny", "*"))
        }
        return ruleset
    }

    private fun expand(pattern: String): String {
        if (pattern.startsWith("~/")) return pattern.replaceFirst("~/", "/data/data/com.aide.mobilestudio/")
        if (pattern.startsWith("\$HOME/")) return pattern.replaceFirst("\$HOME/", "/data/data/com.aide.mobilestudio/")
        return pattern
    }

    fun merge(vararg rulesets: List<PermissionRule>): List<PermissionRule> = rulesets.flatMap { it }

    fun evaluate(permission: String, pattern: String, vararg rulesets: List<PermissionRule>): PermissionRule {
        return rulesets
            .flatMap { it }
            .lastOrNull { wildcardMatch(permission, it.permission) && wildcardMatch(pattern, it.pattern) }
            ?: PermissionRule(permission, "ask", "*")
    }

    fun disabledTools(tools: List<String>, ruleset: List<PermissionRule>): Set<String> {
        val edits = setOf("edit", "write", "patch")
        return tools.filterTo(mutableSetOf()) { tool ->
            val permissionKey = if (tool in edits) "edit" else tool
            val rule = ruleset.lastOrNull { wildcardMatch(permissionKey, it.permission) }
            rule?.pattern == "*" && rule.action == "deny"
        }
    }

    fun normalize(permission: Any?): org.json.JSONObject {
        val result = org.json.JSONObject()
        when (permission) {
            is String -> result.put("*", permission)
            is org.json.JSONObject -> {
                val keys = permission.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    result.put(key, permission.get(key))
                }
            }
        }
        return result
    }
}
