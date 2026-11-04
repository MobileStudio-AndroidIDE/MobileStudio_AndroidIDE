package com.aide.mobilestudio.ai.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageRegistryTest {

    @Test
    fun detectsKotlinByExtension() {
        val detection = LanguageRegistry.detect("Main.kt")
        assertEquals("kotlin", detection.spec?.id)
        assertEquals(1.0, detection.confidence, 0.0001)
        assertFalse(detection.ambiguous)
    }

    @Test
    fun detectsKotlinScriptByExtension() {
        assertEquals("kotlin", LanguageRegistry.detect("build.gradle.kts").spec?.id)
    }

    @Test
    fun detectsDockerfileByFilename() {
        assertEquals("dockerfile", LanguageRegistry.detect("Dockerfile").spec?.id)
    }

    @Test
    fun detectsPythonByShebang() {
        val detection = LanguageRegistry.detect("run", firstLine = "#!/usr/bin/env python3")
        assertEquals("python", detection.spec?.id)
    }

    @Test
    fun detectsBashByShebang() {
        val detection = LanguageRegistry.detect("deploy", firstLine = "#!/bin/bash")
        assertEquals("bash", detection.spec?.id)
    }

    @Test
    fun ambiguousExtensionIsFlaggedNotPinned() {
        // ".m" is both Objective-C and MATLAB — must not be silently decided.
        val detection = LanguageRegistry.detect("matrix.m")
        assertTrue(detection.ambiguous)
        assertTrue(detection.candidates.size >= 2)
        assertEquals(0.4, detection.confidence, 0.0001)
    }

    @Test
    fun unknownExtensionHasNoSpec() {
        val detection = LanguageRegistry.detect("thing.zzzz")
        assertNull(detection.spec)
    }

    @Test
    fun projectFilesResolveLanguage() {
        val languages = LanguageRegistry.byProjectFiles(listOf("Cargo.toml"))
        assertTrue(languages.any { it.id == "rust" })
    }

    @Test
    fun capabilitiesReportUnavailableWithReason() {
        val rust = LanguageRegistry.byId("rust")!!
        val caps = LanguageRegistry.capabilities(rust, FixedToolProbe(setOf("rustc")))
        assertTrue(caps.compile.available)
        assertEquals("/usr/bin/rustc", caps.compile.resolvedPath)
        assertFalse(caps.format.available)
        assertEquals("not found on PATH", caps.format.reason)
    }

    @Test
    fun capabilityForLanguageWithoutDeclaredToolIsExplicit() {
        val spec = LanguageRegistry.byId("diff") ?: LanguageSpec(id = "custom", displayName = "Custom")
        val caps = LanguageRegistry.capabilities(spec, FixedToolProbe(emptySet()))
        assertFalse(caps.compile.available)
        assertEquals("no compiler tool declared", caps.compile.reason)
    }

    @Test
    fun registryCoversRequestedLanguages() {
        val expected = listOf(
            "kotlin", "java", "groovy", "scala", "c", "cpp", "rust", "go", "python",
            "javascript", "typescript", "dart", "lua", "ruby", "php", "sql", "yaml",
            "xml", "toml", "protobuf", "solidity", "fortran", "cobol", "verilog",
            "vhdl", "matlab", "haskell", "ocaml", "elixir", "erlang",
        )
        for (id in expected) {
            assertTrue("registry missing language: $id", LanguageRegistry.byId(id) != null)
        }
    }
}
