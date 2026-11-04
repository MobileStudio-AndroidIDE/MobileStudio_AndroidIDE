package com.aide.mobilestudio.ai.shell

/** Coarse command risk levels, ordered from least to most dangerous. */
enum class CommandRisk {
    READ_ONLY,
    MUTATING,
    INSTALL,
    DESTRUCTIVE,
    SYSTEM,
}

data class CommandRiskAssessment(
    val risk: CommandRisk,
    val requiresApproval: Boolean,
    val reasons: List<String>,
    val segments: List<String> = emptyList(),
) {
    fun describe(): String =
        "${risk.name}" + if (reasons.isEmpty()) "" else " — ${reasons.joinToString("; ")}"
}

/**
 * Classifies a shell command by risk. Commands are split on the shell operators
 * `&&`, `||`, `;`, `|` and newline (respecting quotes) and each segment is
 * classified; the strictest segment wins.
 *
 * The classifier never assumes a command is safe: an unrecognised command is
 * treated as [CommandRisk.MUTATING] and therefore requires approval.
 */
object CommandRiskClassifier {

    private val readOnly = setOf(
        "ls", "ll", "la", "cat", "head", "tail", "wc", "grep", "egrep", "fgrep", "rg",
        "find", "file", "stat", "pwd", "echo", "printf", "which", "whereis", "type",
        "env", "printenv", "du", "df", "tree", "uname", "hostname", "id", "groups",
        "whoami", "date", "cal", "uptime", "sort", "uniq", "cut", "tr", "diff", "cmp",
        "sha256sum", "md5sum", "basename", "dirname", "realpath", "readlink", "seq",
        "awk", "jq", "xxd", "od", "gzip", "gunzip", "unzip", "tar", "less", "more",
    )

    private val mutating = setOf(
        "mv", "cp", "mkdir", "rmdir", "touch", "ln", "truncate", "tee", "dd",
        "install", "rsync", "patch", "chgrp",
    )

    private val install = setOf(
        "apt", "apt-get", "apk", "yum", "dnf", "zypper", "pacman", "brew",
        "pip", "pip3", "conda", "npm", "yarn", "pnpm", "gem", "bundle", "composer",
        "cargo", "go", "gradle", "gradlew", "mvn", "mvnw", "sdkmanager", "avdmanager", "dotnet", "rustup",
        "curl", "wget", "adb", "node", "npx", "deno",
    )

    private val destructive = setOf(
        "mkfs", "mkfs.ext4", "shred", "wipefs", "fdisk", "parted",
    )

    private val system = setOf(
        "sudo", "doas", "su", "mount", "umount", "reboot", "shutdown", "halt",
        "poweroff", "systemctl", "service", "iptables", "ip6tables", "setenforce",
        "insmod", "modprobe", "passwd", "useradd", "usermod", "groupadd", "sysctl",
        "chown", "chmod",
    )

    private val shells = setOf("sh", "bash", "zsh", "ksh", "dash")
    private val downloaders = setOf("curl", "wget")

    fun classify(command: String): CommandRiskAssessment {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return CommandRiskAssessment(CommandRisk.READ_ONLY, false, listOf("empty command"))

        val segments = splitSegments(trimmed)
        var worst = CommandRisk.READ_ONLY
        val reasons = mutableListOf<String>()
        var hasDownloader = false
        var hasShellPipe = false

        for (segment in segments) {
            val tokens = shellTokens(segment)
            if (tokens.isEmpty()) continue
            val cmd = tokens[0].substringAfterLast('/')
            if (cmd in downloaders) hasDownloader = true
            if (cmd in shells) hasShellPipe = true

            val (risk, segmentReasons) = classifySegment(cmd, tokens.drop(1), segment)
            if (risk.ordinal > worst.ordinal) worst = risk
            reasons += segmentReasons
        }

        if (hasDownloader && hasShellPipe) {
            worst = maxRisk(worst, CommandRisk.DESTRUCTIVE)
            reasons += "pipes a downloaded script into a shell"
        }

        val requiresApproval = worst != CommandRisk.READ_ONLY
        return CommandRiskAssessment(worst, requiresApproval, reasons.distinct(), segments)
    }

    fun requiresApproval(command: String): Boolean = classify(command).requiresApproval

    private fun classifySegment(cmd: String, args: List<String>, segment: String): Pair<CommandRisk, List<String>> {
        val reasons = mutableListOf<String>()
        var risk: CommandRisk = when (cmd) {
            in readOnly -> CommandRisk.READ_ONLY
            in destructive -> { reasons += "destructive disk command"; CommandRisk.DESTRUCTIVE }
            in system -> { reasons += "system-level command"; CommandRisk.SYSTEM }
            in mutating -> { reasons += "modifies files"; CommandRisk.MUTATING }
            in install -> { reasons += "installs or downloads"; CommandRisk.INSTALL }
            "rm" -> classifyRm(args, reasons)
            "git" -> classifyGit(args, reasons)
            "sed" -> if (args.any { it == "-i" || it.startsWith("-i") }) {
                reasons += "in-place edit"; CommandRisk.MUTATING
            } else CommandRisk.READ_ONLY
            "python", "python3", "perl", "ruby", "php", "lua" -> {
                if (args.any { it == "-c" || it == "-e" }) {
                    reasons += "executes inline script"; CommandRisk.MUTATING
                } else CommandRisk.READ_ONLY
            }
            else -> { reasons += "unrecognised command"; CommandRisk.MUTATING }
        }

        if (hasRedirection(segment)) {
            risk = maxRisk(risk, CommandRisk.MUTATING)
            reasons += "redirects output to a file"
        }
        if (cmd == "dd" && args.any { it.startsWith("if=") }) {
            risk = CommandRisk.DESTRUCTIVE
            reasons += "dd reads a raw device/file"
        }
        return risk to reasons
    }

    private fun classifyRm(args: List<String>, reasons: MutableList<String>): CommandRisk {
        val recursive = args.any {
            it == "-r" || it == "-R" || it == "-rf" || it == "-fr" || it == "-fR" ||
                it == "--recursive" || it.contains("r") && it.startsWith("-") && !it.startsWith("--")
        }
        val targets = args.filterNot { it.startsWith("-") }
        val broad = targets.any { it == "/" || it == "/*" || it == "~" || it == "~/*" || it.endsWith("/*") }
        return when {
            recursive || broad -> { reasons += "recursive or broad removal"; CommandRisk.DESTRUCTIVE }
            else -> { reasons += "removes files"; CommandRisk.MUTATING }
        }
    }

    private fun classifyGit(args: List<String>, reasons: MutableList<String>): CommandRisk {
        val sub = args.firstOrNull { !it.startsWith("-") } ?: return CommandRisk.READ_ONLY
        return when (sub) {
            "status", "log", "diff", "show", "branch", "remote", "ls-files", "rev-parse",
            "describe", "blame", "tag", "config", "stash" ->
                if (sub == "config" || sub == "stash") {
                    reasons += "git $sub may modify state"; CommandRisk.MUTATING
                } else CommandRisk.READ_ONLY
            "add", "commit", "checkout", "switch", "merge", "rebase", "reset", "clean",
            "rm", "mv", "restore", "apply", "cherry-pick", "revert", "pull", "fetch" ->
                { reasons += "git $sub modifies the repository"; CommandRisk.MUTATING }
            "push" -> { reasons += "git push publishes changes"; CommandRisk.INSTALL }
            "clone" -> { reasons += "git clone downloads a repository"; CommandRisk.INSTALL }
            else -> { reasons += "git $sub"; CommandRisk.MUTATING }
        }
    }

    private fun hasRedirection(segment: String): Boolean {
        var inSingle = false
        var inDouble = false
        var escaped = false
        for (c in segment) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                inSingle -> if (c == '\'') inSingle = false
                inDouble -> if (c == '"') inDouble = false
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == '>' -> return true
            }
        }
        return false
    }

    private fun splitSegments(command: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escaped = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                escaped -> { sb.append(c); escaped = false }
                c == '\\' -> { sb.append(c); escaped = true }
                inSingle -> { sb.append(c); if (c == '\'') inSingle = false }
                inDouble -> { sb.append(c); if (c == '"') inDouble = false }
                c == '\'' -> { sb.append(c); inSingle = true }
                c == '"' -> { sb.append(c); inDouble = true }
                c == ';' || c == '\n' -> { out.add(sb.toString()); sb.setLength(0) }
                c == '&' && i + 1 < command.length && command[i + 1] == '&' -> { out.add(sb.toString()); sb.setLength(0); i++ }
                c == '|' -> {
                    out.add(sb.toString()); sb.setLength(0)
                    if (i + 1 < command.length && command[i + 1] == '|') i++
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun shellTokens(segment: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escaped = false
        for (c in segment) {
            when {
                escaped -> { sb.append(c); escaped = false }
                c == '\\' -> escaped = true
                inSingle -> { if (c == '\'') inSingle = false else sb.append(c) }
                inDouble -> { if (c == '"') inDouble = false else sb.append(c) }
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == ' ' || c == '\t' -> { if (sb.isNotEmpty()) { tokens.add(sb.toString()); sb.setLength(0) } }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString())
        return tokens
    }

    private fun maxRisk(a: CommandRisk, b: CommandRisk): CommandRisk =
        if (a.ordinal >= b.ordinal) a else b
}
