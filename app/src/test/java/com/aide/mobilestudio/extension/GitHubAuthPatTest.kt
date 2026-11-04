package com.aide.mobilestudio.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the PAT (Personal Access Token) login helpers.
 *
 * These are the pure decisions made before any network round-trip:
 *  - normalising whatever the user pasted (newlines, "Bearer " prefix),
 *  - recognising a token-shaped string (used for the clipboard auto-fill),
 *  - deciding whether a classic token's scope list can actually publish.
 */
class GitHubAuthPatTest {

    // ------------------------- normalizeToken -------------------------

    @Test
    fun `strips surrounding whitespace and newlines`() {
        assertEquals("ghp_1234567890abcdefghij1234567890abcd", GitHubAuth.normalizeToken(" \n ghp_1234567890abcdefghij1234567890abcd \t"))
    }

    @Test
    fun `strips a pasted Bearer prefix`() {
        assertEquals("ghp_1234567890abcdefghij1234567890abcd", GitHubAuth.normalizeToken("Bearer ghp_1234567890abcdefghij1234567890abcd"))
        assertEquals("ghp_1234567890abcdefghij1234567890abcd", GitHubAuth.normalizeToken("bearer\tghp_1234567890abcdefghij1234567890abcd"))
    }

    @Test
    fun `strips interior whitespace from wrapped tokens`() {
        assertEquals("ghp_1234567890abcdefghij1234567890abcd", GitHubAuth.normalizeToken("ghp_1234567890\nabcdefghij1234567890abcd"))
    }

    // ------------------------- looksLikeToken -------------------------

    @Test
    fun `accepts a classic PAT`() {
        assertTrue(GitHubAuth.looksLikeToken("ghp_1234567890abcdefghij1234567890abcd"))
    }

    @Test
    fun `accepts a fine-grained PAT`() {
        assertTrue(GitHubAuth.looksLikeToken("github_pat_11ABCDEFG0123456789_012345abcdefghijklmnopqrstuv"))
    }

    @Test
    fun `accepts other github token prefixes`() {
        assertTrue(GitHubAuth.looksLikeToken("gho_1234567890abcdefghij1234567890abcd"))
        assertTrue(GitHubAuth.looksLikeToken("ghu_1234567890abcdefghij1234567890abcd"))
        assertTrue(GitHubAuth.looksLikeToken("ghs_1234567890abcdefghij1234567890abcd"))
        assertTrue(GitHubAuth.looksLikeToken("ghr_1234567890abcdefghij1234567890abcd"))
    }

    @Test
    fun `accepts a legacy 40-char token`() {
        assertTrue(GitHubAuth.looksLikeToken("0123456789abcdef0123456789abcdef01234567"))
    }

    @Test
    fun `rejects non-token clipboard content`() {
        assertFalse(GitHubAuth.looksLikeToken(""))
        assertFalse(GitHubAuth.looksLikeToken("hello world"))
        assertFalse(GitHubAuth.looksLikeToken("https://github.com/settings/tokens"))
        assertFalse(GitHubAuth.looksLikeToken("ghp_tooshort"))
        assertFalse(GitHubAuth.looksLikeToken("not a token at all, just some long sentence"))
    }

    @Test
    fun `recognises a token pasted with a Bearer prefix`() {
        assertTrue(GitHubAuth.looksLikeToken("Bearer ghp_1234567890abcdefghij1234567890abcd"))
    }

    // ------------------------- hasRepoScope -------------------------

    @Test
    fun `repo scope is sufficient`() {
        assertTrue(GitHubAuth.hasRepoScope("repo"))
        assertTrue(GitHubAuth.hasRepoScope("repo, gist"))
        assertTrue(GitHubAuth.hasRepoScope("repo,gist,workflow"))
    }

    @Test
    fun `public_repo scope is sufficient for public releases`() {
        assertTrue(GitHubAuth.hasRepoScope("public_repo"))
        assertTrue(GitHubAuth.hasRepoScope("public_repo, read:user"))
    }

    @Test
    fun `missing scope is detected`() {
        assertFalse(GitHubAuth.hasRepoScope(""))
        assertFalse(GitHubAuth.hasRepoScope("gist"))
        assertFalse(GitHubAuth.hasRepoScope("read:user, workflow"))
        assertFalse(GitHubAuth.hasRepoScope("repo1"))
    }

    // ------------------------- scope warnings -------------------------

    @Test
    fun `classic token with repo scope has no warning`() {
        assertEquals(null, GitHubAuth.TokenInfo("octocat", "repo, gist").scopeWarning())
    }

    @Test
    fun `fine-grained token without scopes is flagged`() {
        val warning = GitHubAuth.TokenInfo("octocat", "").scopeWarning()
        assertTrue(warning!!.contains("fine-grained"))
    }

    @Test
    fun `classic token without repo scope is flagged`() {
        val warning = GitHubAuth.TokenInfo("octocat", "gist, read:user").scopeWarning()
        assertTrue(warning!!.contains("repo"))
    }
}
