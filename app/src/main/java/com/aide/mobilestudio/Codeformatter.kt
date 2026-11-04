package com.aide.mobilestudio

/**
 * 간단한 Kotlin/Java 코드 포맷터
 * - 들여쓰기 정규화 (탭→4스페이스)
 * - 연산자 뒤 공백
 * - 중괄호 앞 공백
 * - 주석 정리
 * - import 정렬
 */
object CodeFormatter {

    fun format(src: String): String {
        var result = src

        // 1. 탭→4 spaces
        result = result.replace("\t", "    ")

        // 2. 연속 공줄 최대 2개로 제한
        result = Regex("\n{3,}").replace(result, "\n\n")

        // 3. 줄 끝 공백 제거
        result = result.lines().joinToString("\n") { it.trimEnd() }

        // 4. 연산자 뒤 공백 정규화
        // = (not ==, !=, <=, >=, ->, =>)
        result = Regex("(?<![=!<>])=(?![=>])").replace(result) { m ->
            val before = result.getOrElse(m.range.first - 1) { ' ' }
            val after  = result.getOrElse(m.range.last  + 1) { ' ' }
            "${if (before == ' ') "" else " "}=${if (after == ' ') "" else " "}"
        }

        // 5. { 앞 공백 보장 (같은 줄에 { 오는 경우)
        result = Regex("(\\S)\\{").replace(result, "$1 {")

        // 6. import 정렬 (블록 찾아서 분리)
        result = sortImports(result)

        // 7. 파일 끝 개행 보장
        if (!result.endsWith("\n")) result += "\n"

        return result
    }

    private fun sortImports(src: String): String {
        val lines = src.lines().toMutableList()
        // Find import block
        val firstImport = lines.indexOfFirst { it.startsWith("import ") }
        if (firstImport < 0) return src
        var lastImport = firstImport
        for (i in firstImport until lines.size) {
            if (lines[i].startsWith("import ") || lines[i].isBlank()) {
                if (lines[i].startsWith("import ")) lastImport = i
            } else break
        }
        val importLines = lines.subList(firstImport, lastImport + 1)
        val sorted = importLines
            .filter { it.startsWith("import ") }
            .sortedWith(compareBy(
                { if (it.contains("android")) 0 else if (it.contains("java") || it.contains("kotlin")) 1 else 2 },
                { it }
            ))
        // Group by top-level package
        val grouped = mutableListOf<String>()
        var lastGroup = ""
        sorted.forEach { imp ->
            val group = imp.removePrefix("import ").substringBefore(".")
            if (group != lastGroup && grouped.isNotEmpty()) grouped.add("")
            grouped.add(imp)
            lastGroup = group
        }
        importLines.clear()
        importLines.addAll(grouped)
        return lines.joinToString("\n")
    }
}