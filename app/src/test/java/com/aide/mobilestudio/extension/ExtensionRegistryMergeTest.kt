package com.aide.mobilestudio.extension

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub-as-the-registry rules: the catalogue is the union of the official
 * repo's Releases plus every repo tagged `mobilestudio-extension`, discovered
 * through GitHub Search. These tests pin the merge semantics that keep that
 * union safe.
 */
class ExtensionRegistryMergeTest {

    private fun release(
        id: String,
        version: String,
        author: String = "someone",
        name: String = "Ext",
        description: String = "desc",
        sha: String = "sha-$id-$version"
    ): JSONObject {
        val body = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("version", version)
            .put("description", description)
            .put("author", author)
            .put("sha256", sha)
        val asset = JSONObject()
            .put("name", "$id-v$version.msext")
            .put("browser_download_url", "https://example.com/$id-v$version.msext")
            .put("size", 1024L)
        return JSONObject()
            .put("tag_name", "$id-v$version")
            .put("draft", false)
            .put("prerelease", false)
            .put("body", body.toString())
            .put("assets", JSONArray().put(asset))
    }

    private fun parse(vararg releases: JSONObject): List<ExtensionManager.RepoEntry> {
        val arr = JSONArray()
        releases.forEach { arr.put(it) }
        return ExtensionManager.parseReleases(arr.toString())
    }

    @Test
    fun `parse reads id, version and author from the release body`() {
        val entries = parse(release("com.example.a", "1.2.3", author = "alice"))
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("com.example.a", e.id)
        assertEquals("1.2.3", e.version)
        assertEquals("alice", e.author)
        assertTrue(e.download.endsWith(".msext"))
        assertEquals("sha-com.example.a-1.2.3", e.sha256)
    }

    @Test
    fun `parse skips draft and prerelease entries`() {
        val draft = release("com.example.a", "1.0.0").put("draft", true)
        val pre = release("com.example.b", "1.0.0").put("prerelease", true)
        assertEquals(emptyList<ExtensionManager.RepoEntry>(), parse(draft, pre))
    }

    @Test
    fun `parse skips releases with no msext asset`() {
        val noAsset = release("com.example.a", "1.0.0")
            .put("assets", JSONArray().put(JSONObject().put("name", "readme.md")))
        assertEquals(emptyList<ExtensionManager.RepoEntry>(), parse(noAsset))
    }

    @Test
    fun `parse falls back to the tag when the body is not json`() {
        val plain = JSONObject()
            .put("tag_name", "com.example.a-v2.0.0")
            .put("draft", false)
            .put("prerelease", false)
            .put("body", "not json at all")
            .put("assets", JSONArray().put(JSONObject()
                .put("name", "com.example.a-v2.0.0.msext")
                .put("browser_download_url", "https://example.com/x.msext")))
        val entries = parse(plain)
        assertEquals(1, entries.size)
        assertEquals("com.example.a", entries[0].id)
        assertEquals("2.0.0", entries[0].version)
    }

    @Test
    fun `parse rejects ids and versions that do not match the registry shape`() {
        val badId = release("Bad ID!", "1.0.0")
        val badVer = release("com.example.a", "not-a-version")
        assertEquals(emptyList<ExtensionManager.RepoEntry>(), parse(badId, badVer))
    }

    @Test
    fun `parse keeps the newest version when the same id appears twice`() {
        val entries = parse(
            release("com.example.a", "1.0.0"),
            release("com.example.a", "1.10.0"),
            release("com.example.a", "1.2.0")
        )
        assertEquals(1, entries.size)
        // 1.10.0 > 1.2.0 numerically, not lexicographically
        assertEquals("1.10.0", entries[0].version)
    }

    @Test
    fun `merge combines entries from the official repo and community repos`() {
        val official = parse(release("com.example.a", "1.0.0", author = "official"))
        val community = parse(
            release("com.example.b", "2.0.0", author = "bob"),
            release("com.example.c", "3.0.0", author = "carol")
        )
        val merged = ExtensionManager.mergeEntries(official, community)
        assertEquals(listOf("com.example.a", "com.example.b", "com.example.c"), merged.map { it.id })
    }

    @Test
    fun `merge lets a newer community version win over the official one`() {
        val official = parse(release("com.example.a", "1.0.0", author = "official"))
        val community = parse(release("com.example.a", "1.1.0", author = "bob"))
        val merged = ExtensionManager.mergeEntries(official, community)
        assertEquals(1, merged.size)
        assertEquals("1.1.0", merged[0].version)
        assertEquals("bob", merged[0].author)
    }

    @Test
    fun `merge keeps the official entry when versions tie so a squatted copy cannot shadow it`() {
        val official = parse(release("com.example.a", "1.0.0", author = "official"))
        val squatter = parse(release("com.example.a", "1.0.0", author = "mallory"))
        val merged = ExtensionManager.mergeEntries(official, squatter)
        assertEquals(1, merged.size)
        assertEquals("official", merged[0].author)
    }

    @Test
    fun `merge of empty lists is empty`() {
        assertEquals(
            emptyList<ExtensionManager.RepoEntry>(),
            ExtensionManager.mergeEntries(emptyList(), emptyList())
        )
    }
}
