package com.aide.mobilestudio.validation

import org.w3c.dom.Element
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory

/**
 * AndroidManifest.xml checks: well-formedness with line/column precision,
 * package/namespace rules, component class resolution, `exported` requirements
 * for targetSdk >= 31 and duplicate component detection.
 */
class ManifestCheck : ProjectCheck() {
    override val id = "manifest"
    override val displayName = "AndroidManifest"
    override val progressLabel = "Checking Manifest..."

    override fun run(ctx: ValidationContext): CheckOutcome {
        val manifests = CheckSupport.manifestFiles(ctx.projectRoot)
        if (manifests.isEmpty()) {
            val androidish = ctx.model.androidModules().isNotEmpty() ||
                CheckSupport.collectFiles(ctx.projectRoot) {
                    it.extension == "kt" || it.extension == "java"
                }.isNotEmpty()
            if (!androidish) return CheckOutcome(skipped = true)
            return CheckOutcome(
                listOf(
                    InspectionIssue(
                        InspectionSeverity.ERROR, InspectionCategory.MANIFEST,
                        null, 1, 1,
                        "AndroidManifest.xml not found anywhere in the project",
                        "MANIFEST_MISSING"
                    )
                )
            )
        }

        val sourceIndex = buildSourceIndex(ctx)
        val issues = mutableListOf<InspectionIssue>()
        for (manifest in manifests) {
            if (ctx.isCancelled()) break
            val text = runCatching { manifest.readText() }.getOrNull() ?: continue
            val parseError = wellFormedError(manifest)
            if (parseError != null) {
                issues += InspectionIssue(
                    InspectionSeverity.ERROR, InspectionCategory.MANIFEST,
                    manifest, parseError.first, parseError.second,
                    parseError.third, "MANIFEST_MALFORMED"
                )
                continue
            }
            issues += semanticIssues(ctx, manifest, text, sourceIndex)
        }
        return CheckOutcome(issues)
    }

    private fun wellFormedError(file: File): Triple<Int, Int, String>? {
        return try {
            val factory = SAXParserFactory.newInstance()
            runCatching {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val parser = factory.newSAXParser()
            parser.parse(file, object : DefaultHandler() {
                override fun error(e: SAXParseException) = throw e
            })
            null
        } catch (e: SAXParseException) {
            Triple(e.lineNumber ?: 0, e.columnNumber ?: 0, e.message ?: "Malformed XML")
        } catch (e: Exception) {
            Triple(1, 1, "Cannot parse manifest: ${e.message}")
        }
    }

    private fun semanticIssues(
        ctx: ValidationContext,
        manifest: File,
        text: String,
        sourceIndex: Map<String, List<File>>
    ): List<InspectionIssue> {
        val issues = mutableListOf<InspectionIssue>()
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            runCatching {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            factory.newDocumentBuilder().parse(manifest)
        } catch (e: Exception) {
            return listOf(
                InspectionIssue(
                    InspectionSeverity.ERROR, InspectionCategory.MANIFEST,
                    manifest, 1, 1, "Cannot read manifest: ${e.message}", "MANIFEST_MALFORMED"
                )
            )
        }
        val rootEl = doc.documentElement ?: return emptyList()
        val module = ctx.model.moduleFor(manifest)
        val namespace = module?.strProp("namespace")
            ?: ctx.model.androidModules().firstOrNull()?.strProp("namespace")
        val pkg = rootEl.getAttribute("package").takeIf { it.isNotBlank() }

        val targetSdk = (module?.intProp("targetSdk") ?: module?.intProp("targetSdkVersion"))
            ?: attrInTag(text, "uses-sdk", "android:targetSdkVersion")?.toIntOrNull()
        val minSdkGradle = module?.intProp("minSdk") ?: module?.intProp("minSdkVersion")
        val minSdkManifest = attrInTag(text, "uses-sdk", "android:minSdkVersion")?.toIntOrNull()

        // package / namespace rules
        if (pkg != null) {
            if (!Regex("""^[A-Za-z_][\w]*(\.[A-Za-z_][\w]*)*$""").matches(pkg)) {
                issues += InspectionIssue(
                    InspectionSeverity.ERROR, InspectionCategory.MANIFEST,
                    manifest, CheckSupport.findLine(text, "package="), 1,
                    "Invalid package name '$pkg'", "MANIFEST_PACKAGE_INVALID"
                )
            }
            if (namespace != null && namespace != pkg) {
                issues += InspectionIssue(
                    InspectionSeverity.WARNING, InspectionCategory.MANIFEST,
                    manifest, CheckSupport.findLine(text, "package="), 1,
                    "Manifest package '$pkg' is ignored; the build uses namespace '$namespace'",
                    "MANIFEST_PACKAGE_IGNORED"
                )
            }
        } else if (namespace == null && ctx.model.androidModules().isNotEmpty()) {
            issues += InspectionIssue(
                InspectionSeverity.ERROR, InspectionCategory.MANIFEST,
                manifest, 1, 1,
                "No package attribute in the manifest and no namespace in build.gradle",
                "MANIFEST_PACKAGE_MISSING"
            )
        }

        if (rootEl.getElementsByTagName("uses-sdk").length > 0) {
            issues += InspectionIssue(
                InspectionSeverity.WARNING, InspectionCategory.MANIFEST,
                manifest, CheckSupport.findLine(text, "uses-sdk"), 1,
                "<uses-sdk> is overridden by minSdk/targetSdk in build.gradle",
                "MANIFEST_USES_SDK"
            )
            if (minSdkManifest != null && minSdkGradle != null && minSdkManifest != minSdkGradle) {
                issues += InspectionIssue(
                    InspectionSeverity.WARNING, InspectionCategory.MANIFEST,
                    manifest, CheckSupport.findLine(text, "uses-sdk"), 1,
                    "minSdk mismatch: manifest=$minSdkManifest, build.gradle=$minSdkGradle",
                    "MANIFEST_SDK_MISMATCH"
                )
            }
        }

        val basePackage = pkg ?: namespace ?: return issues
        val application = firstChildElement(rootEl, "application") ?: return issues
        val seen = mutableMapOf<String, Element>()
        val usesKaptLike = ctx.model.modules.any { m ->
            m.pluginIds.any { it.contains("hilt") || it.contains("kapt") || it.contains("ksp") || it.contains("dagger") }
        }
        val componentTags = setOf("activity", "activity-alias", "service", "receiver", "provider")
        val children = application.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node !is Element || node.tagName !in componentTags) continue
            val component = node
            val rawName = component.getAttribute("android:name").takeIf { it.isNotBlank() } ?: continue
            val fqcn = when {
                rawName.startsWith(".") -> basePackage + rawName
                rawName.contains(".") -> rawName
                else -> "$basePackage.$rawName"
            }
            val nameLine = CheckSupport.findLine(text, rawName)

            val key = "${component.tagName}:$fqcn"
            val prev = seen.put(key, component)
            if (prev != null) {
                issues += InspectionIssue(
                    InspectionSeverity.ERROR, InspectionCategory.MANIFEST,
                    manifest, nameLine, 1,
                    "Duplicate ${component.tagName} '$fqcn' in manifest",
                    "MANIFEST_DUPLICATE_COMPONENT"
                )
                continue
            }

            if (!resolveComponent(fqcn, basePackage, sourceIndex)) {
                val severity = if (usesKaptLike) InspectionSeverity.INFO else InspectionSeverity.WARNING
                issues += InspectionIssue(
                    severity, InspectionCategory.MANIFEST,
                    manifest, nameLine, 1,
                    "Class for ${component.tagName} '$fqcn' was not found in project sources",
                    "MANIFEST_COMPONENT_MISSING"
                )
            }

            if (targetSdk != null && targetSdk >= 31 &&
                component.tagName in setOf("activity", "activity-alias", "service", "receiver") &&
                hasChild(component, "intent-filter") &&
                !component.hasAttribute("android:exported")
            ) {
                issues += InspectionIssue(
                    InspectionSeverity.ERROR, InspectionCategory.MANIFEST,
                    manifest, nameLine, 1,
                    "${component.tagName} '$fqcn' has an intent-filter but no android:exported " +
                        "(required since Android 12 / targetSdk $targetSdk)",
                    "MANIFEST_EXPORTED_MISSING"
                )
            }
        }

        // Empty names are always mistakes: android:name=""
        Regex("android:name\\s*=\\s*\"\"").findAll(text).forEach { m ->
            issues += InspectionIssue(
                InspectionSeverity.WARNING, InspectionCategory.MANIFEST,
                manifest, GradleCodeScanner.lineOfIndex(text, m.range.first), 1,
                "Empty android:name attribute", "MANIFEST_EMPTY_NAME"
            )
        }
        return issues
    }

    private fun hasChild(element: Element, tag: String): Boolean = firstChildElement(element, tag) != null

    private fun firstChildElement(parent: Element, tag: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == tag) return node
        }
        return null
    }

    private fun attrInTag(text: String, tag: String, attr: String): String? {
        val tagIdx = text.indexOf("<$tag")
        if (tagIdx < 0) return null
        val end = text.indexOf('>', tagIdx)
        if (end < 0) return null
        val seg = text.substring(tagIdx, end)
        val m = Regex("""$attr\s*=\s*"([^"]+)"""").find(seg) ?: return null
        return m.groupValues[1]
    }

    private fun buildSourceIndex(ctx: ValidationContext): Map<String, List<File>> {
        val index = mutableMapOf<String, MutableList<File>>()
        CheckSupport.collectFiles(ctx.projectRoot) {
            it.extension == "kt" || it.extension == "java"
        }.forEach { f ->
            index.getOrPut(f.nameWithoutExtension) { mutableListOf() }.add(f)
        }
        return index
    }

    private fun resolveComponent(
        fqcn: String,
        basePackage: String,
        index: Map<String, List<File>>
    ): Boolean {
        val simple = fqcn.substringAfterLast('.').substringBefore('$')
        val candidates = index[simple] ?: return false
        if (candidates.size == 1) return true
        val pkgPath = fqcn.substringBeforeLast('.', "").replace('.', '/')
        if (pkgPath.isEmpty()) return true
        for (candidate in candidates) {
            val path = candidate.absolutePath.replace('\\', '/')
            if (path.contains("/$pkgPath/")) return true
            // Fall back to the file's declared package (first ~10 lines).
            val head = runCatching {
                candidate.bufferedReader().use { reader ->
                    buildString {
                        repeat(10) {
                            val line = reader.readLine() ?: return@buildString
                            appendLine(line)
                        }
                    }
                }
            }.getOrDefault("")
            if (Regex("""(?m)^\s*package\s+${Regex.escape(fqcn.substringBeforeLast('.'))}\b""")
                    .containsMatchIn(head)
            ) return true
        }
        return false
    }
}
