package com.aide.mobilestudio

import android.util.Log
import kotlinx.coroutines.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap

object ResourceIndex {

    private const val TAG = "ResourceIndex"

    data class ResourceEntry(
        val name: String,
        val type: ResourceType,
        val filePath: String,
        val lineNumber: Int = 0,
        val attributes: Map<String, String> = emptyMap(),
        val value: String? = null
    )

    enum class ResourceType(val folderPrefix: String, val rClass: String) {
        ID("values", "R.id"),
        STRING("values", "R.string"),
        COLOR("values", "R.color"),
        DIMEN("values", "R.dimen"),
        DRAWABLE("drawable", "R.drawable"),
        MIPMAP("mipmap", "R.mipmap"),
        LAYOUT("layout", "R.layout"),
        MENU("menu", "R.menu"),
        STYLE("values", "R.style"),
        NAVIGATION("navigation", "R.navigation"),
        XML("xml", "R.xml"),
        ANIM("anim", "R.anim"),
        RAW("raw", "R.raw"),
        FONT("font", "R.font"),
        BOOL("values", "R.bool"),
        INTEGER("values", "R.integer"),
        ARRAY("values", "R.array"),
        ATTR("values", "R.attr"),
        UNKNOWN("", "")
    }

    private val index = ConcurrentHashMap<ResourceType, MutableList<ResourceEntry>>()
    private val idDefinitions = ConcurrentHashMap<String, MutableList<ResourceEntry>>()
    private val allEntries = ConcurrentHashMap<String, MutableList<ResourceEntry>>()

    private var indexedProjectDir: File? = null
    private var isIndexing = false
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    private fun notifyListeners() {
        listeners.forEach { try { it() } catch (_: Exception) {} }
    }

    fun isIndexed(): Boolean = indexedProjectDir != null && allEntries.isNotEmpty()

    fun indexProject(projectDir: File, onDone: (() -> Unit)? = null) {
        if (isIndexing) return
        isIndexing = true
        indexedProjectDir = projectDir

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                clear()
                val appDir = findAppDir(projectDir)
                if (appDir == null) {
                    Log.w(TAG, "No app/ directory found in $projectDir")
                    isIndexing = false
                    onDone?.invoke()
                    notifyListeners()
                    return@launch
                }

                val resDir = File(appDir, "src/main/res")
                if (!resDir.exists()) {
                    Log.w(TAG, "No res/ directory found at ${resDir.absolutePath}")
                    isIndexing = false
                    onDone?.invoke()
                    notifyListeners()
                    return@launch
                }

                indexResDirectory(resDir)

                // Also index debug/release res variants
                listOf("debug", "release", "androidTest").forEach { variant ->
                    val variantRes = File(appDir, "src/$variant/res")
                    if (variantRes.exists()) indexResDirectory(variantRes)
                }

                // Index R.java / R.txt if generated
                indexRFiles(projectDir)

                Log.i(TAG, "Indexing complete: ${allEntries.size} resources across ${index.size} types")
                isIndexing = false
                onDone?.invoke()
                notifyListeners()
            } catch (e: Exception) {
                Log.e(TAG, "Indexing failed", e)
                isIndexing = false
                onDone?.invoke()
            }
        }
    }

    private fun indexResDirectory(resDir: File) {
        if (!resDir.exists() || !resDir.isDirectory) return

        resDir.listFiles()?.forEach { typeDir ->
            if (!typeDir.isDirectory) return@forEach
            val resourceType = resolveResourceType(typeDir.name) ?: return@forEach

            typeDir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                indexFile(file, resourceType)
            }
        }
    }

    private fun indexFile(file: File, resourceType: ResourceType) {
        val fileName = file.nameWithoutExtension
        if (fileName.startsWith(".")) return

        when (resourceType) {
            ResourceType.STRING, ResourceType.DIMEN, ResourceType.COLOR,
            ResourceType.BOOL, ResourceType.INTEGER, ResourceType.ARRAY,
            ResourceType.ATTR, ResourceType.STYLE -> indexValueFile(file, resourceType)
            ResourceType.DRAWABLE, ResourceType.MIPMAP, ResourceType.LAYOUT,
            ResourceType.MENU, ResourceType.NAVIGATION, ResourceType.XML,
            ResourceType.ANIM, ResourceType.RAW, ResourceType.FONT -> {
                val entry = ResourceEntry(
                    name = fileName,
                    type = resourceType,
                    filePath = file.absolutePath
                )
                addEntry(entry)
            }
            ResourceType.ID -> indexIdFile(file)
            else -> {
                val entry = ResourceEntry(
                    name = fileName,
                    type = resourceType,
                    filePath = file.absolutePath
                )
                addEntry(entry)
            }
        }
    }

    private fun indexValueFile(file: File, resourceType: ResourceType) {
        try {
            val content = file.readText()
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(content))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val tagName = parser.name
                    val nameAttr = parser.getAttributeValue(null, "name")

                    if (nameAttr != null) {
                        val entry = ResourceEntry(
                            name = nameAttr,
                            type = resourceType,
                            filePath = file.absolutePath,
                            lineNumber = parser.lineNumber,
                            value = extractValue(parser, tagName)
                        )
                        addEntry(entry)

                        // Special handling for ID definitions
                        if (resourceType == ResourceType.ID) {
                            idDefinitions.getOrPut(nameAttr) { mutableListOf() }.add(entry)
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse value file: ${file.name}", e)
        }
    }

    private fun indexIdFile(file: File) {
        try {
            val content = file.readText()
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(content))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val nameAttr = parser.getAttributeValue(null, "name")
                    if (nameAttr != null) {
                        val entry = ResourceEntry(
                            name = nameAttr,
                            type = ResourceType.ID,
                            filePath = file.absolutePath,
                            lineNumber = parser.lineNumber
                        )
                        addEntry(entry)
                        idDefinitions.getOrPut(nameAttr) { mutableListOf() }.add(entry)
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse ID file: ${file.name}", e)
        }
    }

    private fun extractValue(parser: XmlPullParser, tagName: String): String? {
        return try {
            if (parser.next() == XmlPullParser.TEXT) {
                parser.text?.trim()
            } else null
        } catch (_: Exception) { null }
    }

    private fun indexRFiles(projectDir: File) {
        val buildDir = File(projectDir, "app/build")
        if (!buildDir.exists()) return

        // Try to find generated R.txt
        val rTxt = buildDir.walkTopDown()
            .firstOrNull { it.name == "R.txt" && it.length() < 5_000_000 }

        if (rTxt != null) {
            try {
                rTxt.readLines().forEach { line ->
                    val parts = line.split(" ", limit = 5)
                    if (parts.size >= 3) {
                        val typeStr = parts[0]
                        val name = parts[2]
                        val resType = when (typeStr) {
                            "id" -> ResourceType.ID
                            "string" -> ResourceType.STRING
                            "drawable" -> ResourceType.DRAWABLE
                            "color" -> ResourceType.COLOR
                            "dimen" -> ResourceType.DIMEN
                            "layout" -> ResourceType.LAYOUT
                            "menu" -> ResourceType.MENU
                            "style" -> ResourceType.STYLE
                            "navigation" -> ResourceType.NAVIGATION
                            "xml" -> ResourceType.XML
                            "anim" -> ResourceType.ANIM
                            "raw" -> ResourceType.RAW
                            "font" -> ResourceType.FONT
                            "bool" -> ResourceType.BOOL
                            "integer" -> ResourceType.INTEGER
                            "array" -> ResourceType.ARRAY
                            "attr" -> ResourceType.ATTR
                            else -> return@forEach
                        }
                        if (!hasEntry(name, resType)) {
                            val entry = ResourceEntry(
                                name = name,
                                type = resType,
                                filePath = rTxt.absolutePath
                            )
                            addEntry(entry)
                            if (resType == ResourceType.ID) {
                                idDefinitions.getOrPut(name) { mutableListOf() }.add(entry)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse R.txt", e)
            }
        }
    }

    private fun addEntry(entry: ResourceEntry) {
        index.getOrPut(entry.type) { mutableListOf() }.add(entry)
        allEntries.getOrPut(entry.name) { mutableListOf() }.add(entry)
    }

    private fun hasEntry(name: String, type: ResourceType): Boolean {
        return index[type]?.any { it.name == name } == true
    }

    fun searchResources(prefix: String, type: ResourceType? = null, maxResults: Int = 20): List<ResourceEntry> {
        val p = prefix.lowercase()
        val types = if (type != null) listOf(type) else ResourceType.values().filter { it != ResourceType.UNKNOWN }

        return types.flatMap { t -> index[t] ?: emptyList() }
            .filter { it.name.lowercase().contains(p) }
            .sortedBy { entry ->
                val n = entry.name.lowercase()
                when {
                    n.startsWith(p) -> 0
                    n.contains(p) -> 1
                    else -> 2
                }
            }
            .take(maxResults)
    }

    fun getResourcesByType(type: ResourceType): List<ResourceEntry> {
        return index[type]?.toList() ?: emptyList()
    }

    fun findResourceByName(name: String, type: ResourceType? = null): List<ResourceEntry> {
        return allEntries[name.lowercase()]?.filter { type == null || it.type == type } ?: emptyList()
    }

    fun getXmlIds(layoutFile: File): List<ResourceEntry> {
        val results = mutableListOf<ResourceEntry>()
        try {
            val content = layoutFile.readText()
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(content))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    for (i in 0 until parser.attributeCount) {
                        val attrName = parser.getAttributeName(i)
                        val attrValue = parser.getAttributeValue(i)
                        if (attrName == "android:id" && attrValue?.startsWith("@+id/") == true) {
                            val idName = attrValue.removePrefix("@+id/")
                            results.add(ResourceEntry(
                                name = idName,
                                type = ResourceType.ID,
                                filePath = layoutFile.absolutePath,
                                lineNumber = parser.lineNumber,
                                attributes = mapOf("layout" to layoutFile.nameWithoutExtension)
                            ))
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse layout: ${layoutFile.name}", e)
        }
        return results
    }

    fun findResourceUsages(name: String, type: ResourceType): List<ResourceEntry> {
        val results = mutableListOf<ResourceEntry>()
        val projectDir = indexedProjectDir ?: return results

        val searchPattern = when (type) {
            ResourceType.ID -> listOf("R.id.$name", "@id/$name", "@+id/$name")
            ResourceType.STRING -> listOf("R.string.$name", "@string/$name")
            ResourceType.DRAWABLE -> listOf("R.drawable.$name", "@drawable/$name")
            ResourceType.COLOR -> listOf("R.color.$name", "@color/$name")
            ResourceType.LAYOUT -> listOf("R.layout.$name", "@layout/$name")
            ResourceType.DIMEN -> listOf("R.dimen.$name", "@dimen/$name")
            ResourceType.MENU -> listOf("R.menu.$name", "@menu/$name")
            ResourceType.STYLE -> listOf("R.style.$name", "@style/$name")
            ResourceType.NAVIGATION -> listOf("R.navigation.$name", "@navigation/$name")
            ResourceType.MIPMAP -> listOf("R.mipmap.$name", "@mipmap/$name")
            else -> listOf("R.${type.name.lowercase()}.$name")
        }

        val sourceExtensions = setOf("kt", "java", "xml", "gradle", "kts")

        projectDir.walkTopDown()
            .maxDepth(8)
            .filter { it.isFile && it.extension in sourceExtensions && !isInBuildDir(it) }
            .forEach { file ->
                try {
                    val content = file.readText()
                    searchPattern.forEach { pattern ->
                        var index = 0
                        while (true) {
                            val found = content.indexOf(pattern, index, ignoreCase = false)
                            if (found == -1) break
                            val lineNum = content.substring(0, found).count { it == '\n' } + 1
                            results.add(ResourceEntry(
                                name = name,
                                type = type,
                                filePath = file.absolutePath,
                                lineNumber = lineNum,
                                attributes = mapOf("pattern" to pattern)
                            ))
                            index = found + pattern.length
                        }
                    }
                } catch (_: Exception) {}
            }

        return results
    }

    fun getAllEntries(): Map<ResourceType, List<ResourceEntry>> = index.toMap()

    fun getResourceTypes(): Set<ResourceType> = index.keys.toSet()

    fun clear() {
        index.clear()
        idDefinitions.clear()
        allEntries.clear()
        indexedProjectDir = null
    }

    private fun resolveResourceType(dirName: String): ResourceType? {
        val baseName = dirName.replace(Regex("-\\d+(\\.\\d+)*$"), "")
        return when {
            baseName == "values" -> null
            baseName.startsWith("drawable") -> ResourceType.DRAWABLE
            baseName.startsWith("mipmap") -> ResourceType.MIPMAP
            baseName.startsWith("layout") -> ResourceType.LAYOUT
            baseName.startsWith("menu") -> ResourceType.MENU
            baseName.startsWith("anim") -> ResourceType.ANIM
            baseName.startsWith("raw") -> ResourceType.RAW
            baseName.startsWith("xml") -> ResourceType.XML
            baseName.startsWith("navigation") -> ResourceType.NAVIGATION
            baseName.startsWith("font") -> ResourceType.FONT
            else -> null
        }
    }

    private fun findAppDir(projectDir: File): File? {
        val appDir = File(projectDir, "app")
        if (appDir.exists() && appDir.isDirectory) return appDir
        // Fallback: find first module with src/main/res
        return projectDir.listFiles()?.firstOrNull {
            it.isDirectory && File(it, "src/main/res").exists()
        }
    }

    private fun isInBuildDir(file: File): Boolean {
        val path = file.absolutePath
        return path.contains("/build/") || path.contains("\\build\\") ||
               path.contains("/.gradle/") || path.contains("\\.gradle\\") ||
               path.contains("/.idea/") || path.contains("\\.idea\\")
    }
}
