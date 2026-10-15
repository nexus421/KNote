package bayern.kickner.knote

import bayern.kickner.knote.config.configJson
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertContains(run.stdout, "the first start creates ${File(directory, "data/session.key").absolutePath}")
        assertFalse(File(directory, "data/session.key").exists())
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
    fun `a broken settings file exits with 65`() {
        val directory = directoryWithConfig()
        File(directory, "data/sheets").mkdirs()
        File(directory, "data/settings.json").writeText("{")

        val run = knote("check", workingDirectory = directory)

        assertEquals(65, run.exitCode, run.stderr)
        assertContains(run.stderr, "settings.json")
    }

    @Test
    fun `a failed start exits with 1 so systemd restarts`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { taken ->
            val run = knote(workingDirectory = directoryWithConfig(configJson(listenPort = taken.localPort)))

            assertEquals(1, run.exitCode, run.stderr)
            assertContains(run.stderr, "Could not start on 127.0.0.1:${taken.localPort}")
        }
    }

    @Test
    fun `the first start creates the session key, owner only, and later starts keep it`() {
        val directory = directoryWithConfig()
        val keyFile = File(directory, "data/session.key")
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { taken ->
            val config = configJson(listenPort = taken.localPort)
            File(directory, "config.json").writeText(config)

            val first = knote(workingDirectory = directory)
            val key = keyFile.readText()
            val second = knote(workingDirectory = directory)

            assertContains(first.stdout, "First start: created the session key in ${keyFile.absolutePath}")
            assertTrue(Regex("[0-9a-f]{64}\n").matches(key), key)
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(keyFile.toPath()))
            assertFalse(second.stdout.contains("created the session key"), second.stdout)
            assertEquals(key, keyFile.readText())
        }
    }

    @Test
    fun `a broken session key exits with 65 and never prints it`() {
        val directory = directoryWithConfig()
        File(directory, "data/sheets").mkdirs()
        File(directory, "data/session.key").writeText("secret-but-not-hex")

        val run = knote("check", workingDirectory = directory)

        assertEquals(65, run.exitCode, run.stderr)
        assertContains(run.stderr, "session.key does not hold a key of 64 hex characters")
        assertFalse(run.stderr.contains("secret-but-not-hex"))
    }

    @Test
    fun `the former key command is an unknown argument`() {
        assertEquals(64, knote("key", workingDirectory = tempDir().toFile()).exitCode)
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
