package com.aide.mobilestudio

import java.io.File

data class FileIconInfo(
    val symbol: String,
    val color: String
)

object FileIcons {
    private val iconMap = mapOf(
        // 💻 Code 💻
        "kt"         to FileIconInfo("\uD83D\uDD7A", "#C678DD"),
        "kts"        to FileIconInfo("\uD83D\uDD7A", "#C678DD"),
        "java"       to FileIconInfo("\u2615",       "#F05C5C"),
        "xml"        to FileIconInfo("\uD83D\uDCCB", "#E5C07B"),
        "gradle"     to FileIconInfo("\uD83D\uDC18", "#56B6C2"),
        "json"       to FileIconInfo("\uD83D\uDCC4", "#D19A66"),
        "yaml"       to FileIconInfo("\u2699\uFE0F", "#46C07D"),
        "yml"        to FileIconInfo("\u2699\uFE0F", "#46C07D"),
        "toml"       to FileIconInfo("\u2699\uFE0F", "#4F8EF7"),
        "properties" to FileIconInfo("\u2699\uFE0F", "#E5C07B"),
        "cfg"        to FileIconInfo("\u2699\uFE0F", "#E5C07B"),
        "conf"       to FileIconInfo("\u2699\uFE0F", "#E5C07B"),
        "ini"        to FileIconInfo("\u2699\uFE0F", "#E5C07B"),
        "pro"        to FileIconInfo("\uD83D\uDCE6", "#46C07D"),

        // ⚡ C / C++ ⚡
        "c"          to FileIconInfo("\u26A1",       "#F05C5C"),
        "cpp"        to FileIconInfo("\u26A1",       "#F05C5C"),
        "cc"         to FileIconInfo("\u26A1",       "#F05C5C"),
        "cxx"        to FileIconInfo("\u26A1",       "#F05C5C"),
        "h"          to FileIconInfo("\uD83C\uDFF7", "#56B6C2"),
        "hpp"        to FileIconInfo("\uD83C\uDFF7", "#56B6C2"),
        "hxx"        to FileIconInfo("\uD83C\uDFF7", "#56B6C2"),

        // 📜 Scripts 📜
        "py"         to FileIconInfo("\uD83D\uDC0D", "#C678DD"),
        "js"         to FileIconInfo("\uD83D\uDCDC", "#D19A66"),
        "ts"         to FileIconInfo("\uD83D\uDCDC", "#4F8EF7"),
        "jsx"        to FileIconInfo("\u269B",       "#D19A66"),
        "tsx"        to FileIconInfo("\u269B",       "#4F8EF7"),
        "css"        to FileIconInfo("\uD83C\uDFA8", "#4F8EF7"),
        "scss"       to FileIconInfo("\uD83C\uDFA8", "#F05C5C"),
        "sass"       to FileIconInfo("\uD83C\uDFA8", "#F05C5C"),
        "less"       to FileIconInfo("\uD83C\uDFA8", "#C678DD"),
        "html"       to FileIconInfo("\uD83C\uDF10", "#F05C5C"),
        "dart"       to FileIconInfo("\uD83C\uDFAF", "#56B6C2"),
        "go"         to FileIconInfo("\uD83D\uDC39", "#4F8EF7"),
        "rs"         to FileIconInfo("\u26A0\uFE0F", "#D19A66"),
        "rb"         to FileIconInfo("\uD83D\uDC8E", "#F05C5C"),
        "php"        to FileIconInfo("\uD83D\uDC0B", "#C678DD"),
        "swift"      to FileIconInfo("\uD83E\uDD84", "#D19A66"),
        "scala"      to FileIconInfo("\u26A1",       "#F05C5C"),
        "groovy"     to FileIconInfo("\uD83D\uDC36", "#4F8EF7"),
        "lua"        to FileIconInfo("\uD83C\uDF19", "#C678DD"),
        "r"          to FileIconInfo("\uD83D\uDC3B", "#4F8EF7"),
        "pl"         to FileIconInfo("\uD83D\uDC39", "#46C07D"),
        "sh"         to FileIconInfo("\uD83D\uDCBB", "#46C07D"),
        "bash"       to FileIconInfo("\uD83D\uDCBB", "#46C07D"),
        "zsh"        to FileIconInfo("\uD83D\uDCBB", "#46C07D"),
        "bat"        to FileIconInfo("\uD83D\uDCBB", "#46C07D"),
        "cmd"        to FileIconInfo("\uD83D\uDCBB", "#46C07D"),
        "ps1"        to FileIconInfo("\uD83D\uDCBB", "#4F8EF7"),
        "sql"        to FileIconInfo("\uD83D\uDDC3", "#E5C07B"),

        // 🤖 Android 🤖
        "apk"        to FileIconInfo("\uD83D\uDCF1", "#56B6C2"),
        "aar"        to FileIconInfo("\uD83D\uDCE6", "#56B6C2"),
        "aab"        to FileIconInfo("\uD83D\uDCF1", "#46C07D"),
        "dex"        to FileIconInfo("\u2699\uFE0F", "#F05C5C"),
        "smali"      to FileIconInfo("\uD83D\uDC7E", "#C678DD"),

        // 📝 Docs 📝
        "md"         to FileIconInfo("\uD83D\uDCDD", "#C3CCD8"),
        "txt"        to FileIconInfo("\uD83D\uDCC4", "#76839A"),
        "rtf"        to FileIconInfo("\uD83D\uDCC3", "#76839A"),
        "pdf"        to FileIconInfo("\uD83D\uDCF5", "#F05C5C"),
        "doc"        to FileIconInfo("\uD83D\uDCD6", "#4F8EF7"),
        "docx"       to FileIconInfo("\uD83D\uDCD6", "#4F8EF7"),
        "xls"        to FileIconInfo("\uD83D\uDCCA", "#46C07D"),
        "xlsx"       to FileIconInfo("\uD83D\uDCCA", "#46C07D"),
        "ppt"        to FileIconInfo("\uD83D\uDCA1", "#D19A66"),
        "pptx"       to FileIconInfo("\uD83D\uDCA1", "#D19A66"),
        "csv"        to FileIconInfo("\uD83D\uDCCA", "#46C07D"),

        // 🖼️ Images 🖼️
        "png"        to FileIconInfo("\uD83D\uDDBC", "#D19A66"),
        "jpg"        to FileIconInfo("\uD83D\uDDBC", "#D19A66"),
        "jpeg"       to FileIconInfo("\uD83D\uDDBC", "#D19A66"),
        "gif"        to FileIconInfo("\uD83D\uDDBC", "#D19A66"),
        "webp"       to FileIconInfo("\uD83D\uDDBC", "#D19A66"),
        "bmp"        to FileIconInfo("\uD83D\uDDBC", "#D19A66"),
        "ico"        to FileIconInfo("\uD83D\uDDBC", "#D19A66"),
        "svg"        to FileIconInfo("\uD83D\uDCD0", "#4F8EF7"),
        "ai"         to FileIconInfo("\uD83D\uDCD0", "#D19A66"),
        "psd"        to FileIconInfo("\uD83C\uDFA8", "#4F8EF7"),

        // 🎵 Audio 🎵
        "mp3"        to FileIconInfo("\uD83C\uDFB5", "#C678DD"),
        "wav"        to FileIconInfo("\uD83C\uDFB5", "#C678DD"),
        "ogg"        to FileIconInfo("\uD83C\uDFB5", "#C678DD"),
        "flac"       to FileIconInfo("\uD83C\uDFB5", "#C678DD"),
        "aac"        to FileIconInfo("\uD83C\uDFB5", "#C678DD"),
        "m4a"        to FileIconInfo("\uD83C\uDFB5", "#C678DD"),
        "wma"        to FileIconInfo("\uD83C\uDFB5", "#C678DD"),
        "mid"        to FileIconInfo("\uD83C\uDFB9", "#C678DD"),
        "midi"       to FileIconInfo("\uD83C\uDFB9", "#C678DD"),

        // 🎥 Video 🎥
        "mp4"        to FileIconInfo("\uD83C\uDFA5", "#F05C5C"),
        "mkv"        to FileIconInfo("\uD83C\uDFA5", "#F05C5C"),
        "avi"        to FileIconInfo("\uD83C\uDFA5", "#F05C5C"),
        "mov"        to FileIconInfo("\uD83C\uDFA5", "#F05C5C"),
        "wmv"        to FileIconInfo("\uD83C\uDFA5", "#F05C5C"),
        "flv"        to FileIconInfo("\uD83C\uDFA5", "#F05C5C"),
        "webm"       to FileIconInfo("\uD83C\uDFA5", "#F05C5C"),
        "3gp"        to FileIconInfo("\uD83C\uDFA5", "#F05C5C"),

        // 📦 Archives 📦
        "zip"        to FileIconInfo("\uD83D\uDCE0", "#E5C07B"),
        "tar"        to FileIconInfo("\uD83D\uDCE0", "#E5C07B"),
        "gz"         to FileIconInfo("\uD83D\uDCE0", "#E5C07B"),
        "bz2"        to FileIconInfo("\uD83D\uDCE0", "#E5C07B"),
        "xz"         to FileIconInfo("\uD83D\uDCE0", "#E5C07B"),
        "zst"        to FileIconInfo("\uD83D\uDCE0", "#E5C07B"),
        "7z"         to FileIconInfo("\uD83D\uDCE0", "#E5C07B"),
        "rar"        to FileIconInfo("\uD83D\uDCE0", "#E5C07B"),
        "jar"        to FileIconInfo("\uD83D\uDCE0", "#C678DD"),

        // 🔡 Fonts 🔡
        "ttf"        to FileIconInfo("\uD83D\uDD24", "#4F8EF7"),
        "otf"        to FileIconInfo("\uD83D\uDD24", "#4F8EF7"),
        "woff"       to FileIconInfo("\uD83D\uDD24", "#4F8EF7"),
        "woff2"      to FileIconInfo("\uD83D\uDD24", "#4F8EF7"),
        "eot"        to FileIconInfo("\uD83D\uDD24", "#4F8EF7"),

        // 🛠️ IDE / Project 🛠️
        "iml"        to FileIconInfo("\u2699\uFE0F", "#4F8EF7"),
        "idea"       to FileIconInfo("\u2699\uFE0F", "#C3CCD8"),
        "classpath"  to FileIconInfo("\u2699\uFE0F", "#46C07D"),
        "project"    to FileIconInfo("\u2699\uFE0F", "#E5C07B"),

        // 🪵 Git 🪵
        "gitignore"  to FileIconInfo("\uD83D\uDEAB", "#F05C5C"),
        "gitattributes" to FileIconInfo("\uD83D\uDEAB", "#E5C07B"),
        "gitmodules" to FileIconInfo("\uD83D\uDEAB", "#4F8EF7"),
        "gitkeep"    to FileIconInfo("\uD83D\uDC4D", "#46C07D"),

        // 🧩 Mixed 🧩
        "db"         to FileIconInfo("\uD83D\uDDC3", "#E5C07B"),
        "sqlite"     to FileIconInfo("\uD83D\uDDC3", "#E5C07B"),
        "lock"       to FileIconInfo("\uD83D\uDD12", "#C3CCD8"),
        "log"        to FileIconInfo("\uD83D\uDCDD", "#76839A"),
        "env"        to FileIconInfo("\uD83D\uDD11", "#E5C07B"),
        "dockerfile" to FileIconInfo("\uD83D\uDC33", "#4F8EF7"),
        "makefile"   to FileIconInfo("\u2699\uFE0F", "#F05C5C"),
        "cmake"      to FileIconInfo("\u2699\uFE0F", "#56B6C2"),
    )

    private val folderMap = mapOf(
        "closed" to FileIconInfo("\uD83D\uDCC1", "#E5C07B"),
        "open"   to FileIconInfo("\uD83D\uDCC2", "#E5C07B"),
        "src"    to FileIconInfo("\uD83D\uDCC1", "#56B6C2"),
        "res"    to FileIconInfo("\uD83D\uDCC1", "#46C07D"),
        "lib"    to FileIconInfo("\uD83D\uDCC1", "#C678DD"),
        "build"  to FileIconInfo("\uD83D\uDCC1", "#D19A66"),
        "test"   to FileIconInfo("\uD83D\uDCC1", "#F05C5C"),
        ".git"   to FileIconInfo("\uD83D\uDD00", "#F05C5C"),
        ".gradle" to FileIconInfo("\uD83D\uDD00", "#46C07D"),
        ".idea"  to FileIconInfo("\uD83D\uDD00", "#4F8EF7"),
    )

    fun forExtension(ext: String): FileIconInfo =
        iconMap[ext.lowercase()] ?: FileIconInfo("\u00B7", "#76839A")

    /**
     * Content-based detection (`file(1)`-style): uses the extension when present,
     * then the file name (LICENSE/README/Makefile…), then content sniffing
     * (shebang → script, text bytes → 📄, binary → ⬛). Extension-less text files
     * like LICENSE resolve to the text icon.
     */
    fun forFile(file: File): FileIconInfo {
        val name = file.name
        val ext = name.lowercase().substringAfterLast('.', "")
        val hasExt = ext.isNotEmpty() && ext != name.lowercase()
        if (hasExt) {
            iconMap[ext]?.let { return it }
        }
        // Name-based (case-insensitive)
        val lower = name.lowercase()
        val base = lower.substringBeforeLast('.')
        val nameIcon = when {
            base == "license" || base == "licence" || base == "copying" || base == "notice" -> FileIconInfo("\uD83D\uDCC4", "#D19A66")
            base == "readme" || base == "changelog" || base == "authors" || base == "contributors" -> FileIconInfo("\uD83D\uDCC4", "#46C07D")
            base == "makefile" || base == "dockerfile" -> FileIconInfo("\u2699\uFE0F", "#F05C5C")
            lower == "gradlew" || lower == "gradlew.bat" -> FileIconInfo("\uD83D\uDC18", "#56B6C2")
            lower == ".gitignore" || lower == ".gitattributes" || lower == ".editorconfig" -> FileIconInfo("\u2699\uFE0F", "#46C07D")
            lower == "cmakelists.txt" -> iconMap["cmake"]!!
            else -> null
        }
        if (nameIcon != null) return nameIcon
        if (!hasExt && iconMap[ext]?.let { true } == true) return iconMap[ext]!!
        // Content sniffing (minimal read; fails safe)
        val head = readHead(file) ?: return FileIconInfo("\uD83D\uDCC4", "#8E99AC")
        val trimmed = head.trimStart()
        return when {
            trimmed.startsWith("#!/bin/sh") || trimmed.startsWith("#!/bin/bash") ||
                trimmed.startsWith("#!/usr/bin/env") -> FileIconInfo("\u2699\uFE0F", "#46C07D")
            trimmed.startsWith("<?xml") || trimmed.startsWith("<!DOCTYPE") -> FileIconInfo("\uD83D\uDCCB", "#E5C07B")
            trimmed.startsWith("{") || trimmed.startsWith("[") -> FileIconInfo("\uD83D\uDCC4", "#D19A66")
            isTextBytes(head) -> FileIconInfo("\uD83D\uDCC4", "#8E99AC")
            else -> FileIconInfo("\u00B7", "#76839A")
        }
    }

    private fun readHead(file: File, size: Int = 128): String? = try {
        if (!file.isFile || file.length() == 0L) null
        else {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(size)
                var n = 0
                while (n < buf.size) {
                    val r = raf.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                String(buf, 0, n)
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun isTextBytes(head: String): Boolean {
        if (head.isEmpty()) return true
        var suspicious = 0
        for (ch in head) {
            val c = ch.code
            if (c == 0 || (c < 9) || (c in 14..31)) suspicious++
        }
        return suspicious == 0
    }

    fun forFolder(type: String = "closed"): FileIconInfo =
        folderMap[type] ?: folderMap["closed"]!!

    fun forFolderName(name: String): FileIconInfo {
        val lower = name.lowercase()
        return folderMap[lower] ?: when {
            lower.contains("src") -> folderMap["src"]!!
            lower.contains("res") -> folderMap["res"]!!
            lower.contains("lib") -> folderMap["lib"]!!
            lower.contains("build") -> folderMap["build"]!!
            lower.contains("test") -> folderMap["test"]!!
            lower.startsWith(".") -> folderMap[".git"]!!
            else -> folderMap["closed"]!!
        }
    }

    fun iconColor(ext: String): String = forExtension(ext).color
    fun iconSymbol(ext: String): String = forExtension(ext).symbol
}
