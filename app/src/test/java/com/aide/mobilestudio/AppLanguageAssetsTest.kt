package com.aide.mobilestudio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the shipped JSON language packs under app/src/main/assets/i18n.
 *
 * A broken pack is invisible at compile time and only shows up as English text
 * on a user's device, so the structure is checked here.
 */
class AppLanguageAssetsTest {

    private fun i18nDir(): File {
        val candidates = listOf(
            File("src/main/assets/i18n"),
            File("app/src/main/assets/i18n")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("i18n assets directory not found")
    }

    @Test
    fun shipsAtLeastTwentyLanguagePacks() {
        val files = i18nDir().listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
        assertTrue("expected >= 20 packs, found ${files.size}", files.size >= 20)
    }

    @Test
    fun everyPackParsesAndMatchesItsFileName() {
        val files = i18nDir().listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
        files.forEach { f ->
            val code = f.name.removeSuffix(".json")
            val o = JSONObject(f.readText(Charsets.UTF_8))
            assertEquals("language.code must match the file name for ${f.name}", code, o.getJSONObject("language").getString("code"))
            assertTrue("nativeName missing in ${f.name}", o.getJSONObject("language").getString("nativeName").isNotBlank())
            val literals = o.getJSONObject("literals")
            assertTrue("literals empty in ${f.name}", literals.length() >= 50)
            val keys = literals.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                assertTrue("empty value for '$k' in ${f.name}", literals.getString(k).isNotBlank())
            }
        }
    }

    @Test
    fun coreMenuStringsAreTranslatedInEveryPack() {
        // The top menu is the most visible surface; every pack must cover it.
        val core = listOf("File", "Edit", "View", "Build", "Run", "Tools", "Extensions", "Settings", "Search")
        val files = i18nDir().listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
        files.forEach { f ->
            val literals = JSONObject(f.readText(Charsets.UTF_8)).getJSONObject("literals")
            core.forEach { key ->
                assertTrue("${f.name} is missing '$key'", literals.has(key))
            }
        }
    }
}
