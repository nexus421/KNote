package bayern.kickner.knote

import bayern.kickner.knote.config.configJson
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Run(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Runs KNote's `main` in a separate JVM, the only way to observe exit codes and the stdout and stderr split.
 * `-Xlog:disable` keeps JVM warnings of the host (e.g. about cgroups) out of the output.
 */
private fun knote(vararg args: String, workingDirectory: File): Run {
    val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    val stdout = File.createTempFile("knote-stdout", ".txt").apply { deleteOnExit() }
    val stderr = File.createTempFile("knote-stderr", ".txt").apply { deleteOnExit() }
    val process = ProcessBuilder(java, "-Xlog:disable", "-cp", System.getProperty("java.class.path"), "bayern.kickner.knote.MainKt", *args)
        .directory(workingDirectory)
        .redirectOutput(stdout)
        .redirectError(stderr)
        .start()
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "KNote did not exit within 60 s")
    return Run(process.exitValue(), stdout.readText(), stderr.readText())
}

private fun directoryWithConfig(json: String = configJson(listenPort = 1)): File =
    tempDir().toFile().also { File(it, "config.json").writeText(json) }

class MainTest {

    @Test
    fun `key prints only 64 hex characters to stdout and needs no config`() {
        val run = knote("key", workingDirectory = tempDir().toFile())

        assertEquals(0, run.exitCode, run.stderr)
        assertTrue(Regex("[0-9a-f]{64}").matches(run.stdout.trim()), "stdout was: ${run.stdout}")
        assertContains(run.stderr, "Keep it secret")
    }

    @Test
    fun `an unknown argument exits with 64`() {
        val run = knote("chek", workingDirectory = tempDir().toFile())

        assertEquals(64, run.exitCode, run.stderr)
        assertContains(run.stderr, "Unknown argument(s): chek")
    }

    @Test
    fun `a missing config exits with 78`() {
        val run = knote(workingDirectory = tempDir().toFile())

        assertEquals(78, run.exitCode, run.stderr)
        assertContains(run.stderr, "Config file not found")
    }

    @Test
    fun `check with the example config exits with 78 and names the placeholders`() {
        val run = knote("config=${File("config.example.json").absolutePath}", "check", workingDirectory = tempDir().toFile())

        assertEquals(78, run.exitCode, run.stderr)
        assertContains(run.stderr, "sessionSignKey")
        assertContains(run.stderr, "oidc.clientSecret still holds the placeholder")
    }

    @Test
    fun `check with a valid config and sheets exits with 0 without starting the server`() {
        val directory = directoryWithConfig()
        val sheets = File(directory, "data/sheets").apply { mkdirs() }
        File(sheets, "a.json").writeText("""{"id":"a","path":"einkauf","title":"t","content":{"type":"plain","markdown":""},"createdBy":"s","created":1,"updated":1}""")

        val run = knote("check", workingDirectory = directory)

        assertEquals(0, run.exitCode, run.stderr)
        assertContains(run.stdout, "is valid, 1 sheet(s)")
    }

    @Test
    fun `check on a fresh install changes nothing on disk`() {
        val directory = directoryWithConfig()

        val run = knote("check", workingDirectory = directory)

        assertEquals(0, run.exitCode, run.stderr)
        assertContains(run.stdout, "the first start creates it with an example sheet")
        assertTrue(File(directory, "data").exists().not())
    }

    @Test
    fun `broken sheet files exit with 65 and list every problem`() {
        val directory = directoryWithConfig()
        val sheets = File(directory, "data/sheets").apply { mkdirs() }
        File(sheets, "broken.json").writeText("{")
        File(sheets, "other.json").writeText("""{"id":"z","path":"a","title":"t","content":{"type":"plain","markdown":""},"createdBy":"s","created":1,"updated":1}""")

        val run = knote("check", workingDirectory = directory)

        assertEquals(65, run.exitCode, run.stderr)
        assertContains(run.stderr, "broken.json")
        assertContains(run.stderr, "other.json: file name does not match id z")
    }

    @Test
    fun `a failed start exits with 1 so systemd restarts`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { taken ->
            val run = knote(workingDirectory = directoryWithConfig(configJson(listenPort = taken.localPort)))

            assertEquals(1, run.exitCode, run.stderr)
            assertContains(run.stderr, "Could not start on 127.0.0.1:${taken.localPort}")
        }
    }
}

class BuildConfigTest {

    @Test
    fun `version and build time are usable`() {
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(BuildConfig.VERSION), BuildConfig.VERSION)
        assertTrue(BuildConfig.BUILD_TIME in 1_767_225_600_000L..System.currentTimeMillis(), "build time ${BuildConfig.BUILD_TIME}")
    }

    @Test
    fun `app version names version and formatted build time`() {
        val buildTime = Instant.ofEpochMilli(BuildConfig.BUILD_TIME).atZone(ZoneId.systemDefault()).format(timestampFormat)

        assertEquals("${BuildConfig.VERSION} (built $buildTime)", appVersion)
    }
}
