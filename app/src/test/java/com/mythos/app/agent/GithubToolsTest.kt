package com.mythos.app.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GithubToolsTest {
    private fun newSandbox(): ProjectSandbox =
        ProjectSandbox(Files.createTempDirectory("mythos-gh-test").toFile())

    private class FakeBuild : BuildService {
        var lastTitle: String? = null
        var lastBase: String? = null
        override suspend fun build(root: File, timeoutSec: Int): BuildOutcome = BuildOutcome(true, "ok")
        override suspend fun createPullRequest(root: File, title: String, body: String, base: String?, head: String?): BuildOutcome {
            lastTitle = title
            lastBase = base
            return BuildOutcome(true, "PR dibuat")
        }
        override suspend fun issues(action: String, number: Int?, state: String): BuildOutcome =
            BuildOutcome(true, "action=$action number=$number state=$state")
    }

    @Test
    fun registryWithBuildHasAllGithubToolsAndPullRequestAlwaysAsksApproval() {
        val reg = ToolRegistry.default(true)
        for (n in listOf("GithubBuild", "GithubImportRepo", "GithubCreatePR", "GithubIssues", "GithubDownloadArtifact")) {
            assertNotNull(n, reg.get(n))
        }
        assertEquals(Risk.DANGEROUS, reg.get("GithubCreatePR")!!.risk)
        assertEquals(Risk.SAFE, reg.get("GithubIssues")!!.risk)
        assertEquals(null, ToolRegistry.default(false).get("GithubCreatePR"))
    }

    @Test
    fun githubToolsExplainWhenGithubIsNotConfigured() = runBlocking {
        val env = ToolEnv(newSandbox(), SessionState(), { "" }, null)
        val reg = ToolRegistry.default(true)
        for (n in listOf("GithubImportRepo", "GithubCreatePR", "GithubIssues", "GithubDownloadArtifact")) {
            val res = reg.get(n)!!.run(JSONObject().put("title", "x"), env)
            assertFalse(n, res.ok)
        }
    }

    @Test
    fun pullRequestAndIssuesPassArgumentsToTheBuildService() = runBlocking {
        val fake = FakeBuild()
        val env = ToolEnv(newSandbox(), SessionState(), { "" }, fake)
        val reg = ToolRegistry.default(true)

        val pr = reg.get("GithubCreatePR")!!.run(JSONObject().put("title", "Perbaiki bug").put("base", "main"), env)
        assertTrue(pr.ok)
        assertEquals("Perbaiki bug", fake.lastTitle)
        assertEquals("main", fake.lastBase)

        val list = reg.get("GithubIssues")!!.run(JSONObject(), env)
        assertTrue(list.text.contains("action=list"))
        val read = reg.get("GithubIssues")!!.run(JSONObject().put("number", 7), env)
        assertTrue(read.text.contains("action=read number=7"))
    }

    @Test
    fun workflowDigestIncludesDeeplyNestedScriptsBeyond40Files() {
        val sb = newSandbox()
        File(sb.root, ".github/workflows/ci.yml").apply {
            parentFile.mkdirs()
            writeText("name: CI\non: push\njobs:\n  b:\n    runs-on: ubuntu-latest\n    steps:\n      - run: bash a/b/c/d/e/f/deep.sh\n")
        }
        for (i in 1..45) File(sb.root, "dummy_$i.sh").writeText("echo $i")
        File(sb.root, "a/b/c/d/e/f/deep.sh").apply {
            parentFile.mkdirs()
            writeText("echo SECRET_EXFIL")
        }
        val digest = workflowDigest(sb.root)
        assertTrue(digest.contains("a/b/c/d/e/f/deep.sh"))
        assertTrue(digest.contains("SECRET_EXFIL"))
    }
}
