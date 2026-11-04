package com.aide.mobilestudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub Releases 기반 동적 도구 발견 파싱 검증.
 * 도구 이름/버전은 릴리스 자산에서 파생되며 하드코딩이 없어야 한다.
 */
class StudioToolsIndexTest {

    @Test
    fun toolNameDerivedFromAssetName() {
        assertEquals("git", StudioToolsIndex.toolNameFromAsset("git-android-arm64.tar.gz"))
        assertEquals("grep", StudioToolsIndex.toolNameFromAsset("grep-android-arm64.tar.gz"))
        assertNull("non-tool assets are not tools", StudioToolsIndex.toolNameFromAsset("checksums.sha256"))
        assertNull("non-tool assets are not tools", StudioToolsIndex.toolNameFromAsset("manifest.json"))
        assertNull(StudioToolsIndex.toolNameFromAsset("random.zip"))
        assertNull("path traversal must be rejected", StudioToolsIndex.toolNameFromAsset("../evil-android-arm64.tar.gz"))
        assertNull(StudioToolsIndex.toolNameFromAsset(""))
    }

    @Test
    fun releasesJsonParsedWithToolAssetsOnly() {
        val json = """
            [
              {
                "tag_name": "tools-v1",
                "draft": false,
                "prerelease": false,
                "assets": [
                  {"name": "checksums.sha256", "browser_download_url": "https://example.com/checksums.sha256", "size": 100},
                  {"name": "manifest.json", "browser_download_url": "https://example.com/manifest.json", "size": 100},
                  {"name": "git-android-arm64.tar.gz", "browser_download_url": "https://example.com/git.tar.gz", "size": 2100369},
                  {"name": "grep-android-arm64.tar.gz", "browser_download_url": "https://example.com/grep.tar.gz", "size": 131564}
                ]
              },
              {
                "tag_name": "tools-v0",
                "draft": true,
                "prerelease": false,
                "assets": [
                  {"name": "evil-android-arm64.tar.gz", "browser_download_url": "https://example.com/evil.tar.gz", "size": 1}
                ]
              }
            ]
        """.trimIndent()

        val releases = StudioToolsIndex.parseReleasesJson(json)
        assertEquals("draft releases are skipped", 1, releases.size)
        val release = releases.single()
        assertEquals("tools-v1", release.tag)
        assertEquals("https://example.com/checksums.sha256", release.checksumsUrl)
        assertEquals(listOf("git", "grep"), release.entries.map { it.name })
        assertEquals(2100369L, release.entries[0].size)
        assertEquals("https://example.com/grep.tar.gz", release.entries[1].url)
        assertEquals("the release tag doubles as the version", "tools-v1", release.entries[0].version)
    }

    @Test
    fun releasesJsonWithoutToolAssetsYieldsEmpty() {
        val json = """
            [
              {
                "tag_name": "empty",
                "draft": false,
                "assets": [
                  {"name": "checksums.sha256", "browser_download_url": "https://example.com/checksums.sha256", "size": 100}
                ]
              }
            ]
        """.trimIndent()
        assertTrue(StudioToolsIndex.parseReleasesJson(json).single().entries.isEmpty())
        assertTrue(StudioToolsIndex.parseReleasesJson("not json").isEmpty())
    }

    @Test
    fun checksumsParsedPerAsset() {
        val text = """
            a658fb8a856af2720e2252fa18e993bec76676e2e91e25e1c53e0b4075586552  git-android-arm64.tar.gz
            706f30c78f74d4eb7f32dfcf17f1986a09ebad15a7c944fe15206a1b9b7b986c *curl-android-arm64.tar.gz
            notahash git-android-arm64.tar.gz
            a658fb8a856af2720e2252fa18e993bec76676e2e91e25e1c53e0b4075586552  ../traversal.tar.gz
        """.trimIndent()

        val hashes = StudioToolsIndex.parseChecksums(text)
        assertEquals(
            "a658fb8a856af2720e2252fa18e993bec76676e2e91e25e1c53e0b4075586552",
            hashes["git-android-arm64.tar.gz"]
        )
        assertEquals(
            "binary-mode marker (*) is handled",
            "706f30c78f74d4eb7f32dfcf17f1986a09ebad15a7c944fe15206a1b9b7b986c",
            hashes["curl-android-arm64.tar.gz"]
        )
        assertEquals("invalid hashes are skipped", 2, hashes.size)
    }

    @Test
    fun indexCacheRoundTrip() {
        val index = StudioToolsIndex.ToolIndex(
            tools = listOf(
                StudioToolsIndex.ToolEntry(
                    name = "git",
                    version = "2.45.2",
                    size = 2100369,
                    url = "https://example.com/git.tar.gz",
                    sha256 = "a".repeat(64),
                    releaseTag = "tools-v1"
                ),
                StudioToolsIndex.ToolEntry(
                    name = "grep",
                    version = "",
                    size = 131564,
                    url = "https://example.com/grep.tar.gz",
                    sha256 = "",
                    releaseTag = "tools-v1"
                )
            ),
            fetchedAt = 12345L
        )

        val parsed = StudioToolsIndex.parseIndexJson(StudioToolsIndex.indexToJson(index))
        assertEquals(index.tools, parsed?.tools)
        assertEquals(12345L, parsed?.fetchedAt)
        assertEquals("tools-v1", parsed?.find("git")?.releaseTag)
        assertNull(StudioToolsIndex.parseIndexJson("not json"))
    }
}
