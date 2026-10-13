package bayern.kickner.knote.config

import bayern.kickner.knote.cli.generateSessionKey
import kotnexlib.ResultOf2
import java.io.File
import kotlin.test.*

private const val KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

/** A valid config as JSON. Every parameter replaces one value, `null` leaves the field out to test its default. */
internal fun configJson(
    listenHost: String? = null,
    listenPort: Int? = null,
    publicBaseUrl: String = "https://notes.example.org",
    dataDir: String? = null,
    trustedProxyIp: String = "10.0.0.2",
    sessionSignKey: String = KEY,
    sessionHours: Int? = null,
    unlockAttemptsPerMinute: Int? = null,
    loginAttemptsPerMinute: Int? = null,
    cryptoParallelism: Int? = null,
    minSheetPasswordLength: Int? = null,
    maxSheetBytes: Int? = null,
    language: String? = null,
    authorizeUrl: String = "https://id.example.org/authorize",
    tokenUrl: String = "https://id.example.org/api/oidc/token",
    userinfoUrl: String = "https://id.example.org/api/oidc/userinfo",
    clientId: String = "knote",
    clientSecret: String = "secret-value",
    // null leaves the field out
    allowedSubjects: String? = """["sub-1"]""",
): String {
    val optional = listOfNotNull(
        listenHost?.let { "\"listenHost\": \"$it\"" },
        listenPort?.let { "\"listenPort\": $it" },
        dataDir?.let { "\"dataDir\": \"$it\"" },
        sessionHours?.let { "\"sessionHours\": $it" },
        unlockAttemptsPerMinute?.let { "\"unlockAttemptsPerMinute\": $it" },
        loginAttemptsPerMinute?.let { "\"loginAttemptsPerMinute\": $it" },
        cryptoParallelism?.let { "\"cryptoParallelism\": $it" },
        minSheetPasswordLength?.let { "\"minSheetPasswordLength\": $it" },
        maxSheetBytes?.let { "\"maxSheetBytes\": $it" },
        language?.let { "\"language\": \"$it\"" },
    )
    return """
        {
          ${optional.joinToString("") { "$it,\n" }}
          "publicBaseUrl": "$publicBaseUrl",
          "trustedProxyIp": "$trustedProxyIp",
          "sessionSignKey": "$sessionSignKey",
          "oidc": {
            "authorizeUrl": "$authorizeUrl",
            "tokenUrl": "$tokenUrl",
            "userinfoUrl": "$userinfoUrl",
            "clientId": "$clientId",
            "clientSecret": "$clientSecret"${allowedSubjects?.let { ",\n\"allowedSubjects\": $it" }.orEmpty()}
          }
        }
    """.trimIndent()
}

class ConfigLoaderTest {

    @Test
    fun `loads a complete config and fills the defaults`() {
        val config = assertIs<ResultOf2.Success<AppConfig>>(load(configJson())).value

        assertEquals("127.0.0.1", config.listenHost)
        assertEquals(8080, config.listenPort)
        assertEquals("data", config.dataDir)
        assertEquals(12, config.sessionHours)
        assertEquals(5, config.unlockAttemptsPerMinute)
        assertEquals(10, config.loginAttemptsPerMinute)
        assertEquals(1, config.cryptoParallelism)
        assertEquals(12, config.minSheetPasswordLength)
        assertEquals(1_048_576, config.maxSheetBytes)
        assertEquals("en", config.language)
        assertTrue(config.showPoweredBy)
        assertEquals(listOf("sub-1"), config.oidc.allowedSubjects)
    }

    @Test
    fun `the example config only reports the placeholders`() {
        val message = failure(loadConfig("config.example.json", cpuCount = 4))

        val issues = message.lines().drop(1).map { it.trim() }
        assertEquals(3, issues.size, message)
        assertContains(issues[0], "sessionSignKey")
        assertContains(issues[1], "oidc.clientId still holds the placeholder")
        assertContains(issues[2], "oidc.clientSecret still holds the placeholder")
    }

    @Test
    fun `a placeholder in allowedSubjects is only a warning`() {
        val config = assertIs<ResultOf2.Success<AppConfig>>(load(configJson(allowedSubjects = """["REPLACE_ME"]"""))).value

        assertContains(configWarnings(config).single(), "allowedSubjects")
    }

    @Test
    fun `allowedSubjects is optional`() {
        val missing = assertIs<ResultOf2.Success<AppConfig>>(load(configJson(allowedSubjects = null))).value
        val empty = assertIs<ResultOf2.Success<AppConfig>>(load(configJson(allowedSubjects = "[]"))).value

        assertEquals(emptyList(), missing.oidc.allowedSubjects)
        assertEquals(emptyList(), empty.oidc.allowedSubjects)
        assertTrue(configWarnings(missing).isEmpty())
    }

    @Test
    fun `a valid config has no warnings`() {
        val config = assertIs<ResultOf2.Success<AppConfig>>(load(configJson())).value

        assertTrue(configWarnings(config).isEmpty())
    }

    @Test
    fun `a generated key is accepted, upper case hex as well`() {
        assertIs<ResultOf2.Success<AppConfig>>(load(configJson(sessionSignKey = generateSessionKey())))
        assertIs<ResultOf2.Success<AppConfig>>(load(configJson(sessionSignKey = KEY.uppercase())))
    }

    @Test
    fun `every rule is checked`() {
        val cases = mapOf(
            configJson(listenHost = " ") to "listenHost",
            configJson(listenPort = 0) to "listenPort",
            configJson(listenPort = 65536) to "listenPort",
            configJson(publicBaseUrl = "http://notes.example.org") to "publicBaseUrl",
            configJson(publicBaseUrl = "https://notes.example.org/") to "publicBaseUrl",
            configJson(publicBaseUrl = "https://notes.example.org/notes") to "publicBaseUrl",
            configJson(publicBaseUrl = "https://notes.example.org?a=b") to "publicBaseUrl",
            configJson(publicBaseUrl = "notes.example.org") to "publicBaseUrl",
            configJson(dataDir = " ") to "dataDir",
            configJson(trustedProxyIp = "10.1.1") to "trustedProxyIp",
            configJson(trustedProxyIp = "256.1.1.1") to "trustedProxyIp",
            configJson(trustedProxyIp = "01.2.3.4") to "trustedProxyIp",
            configJson(trustedProxyIp = "fd42::1") to "trustedProxyIp",
            configJson(trustedProxyIp = "10.1.1.1 ") to "trustedProxyIp",
            configJson(sessionSignKey = KEY.dropLast(1)) to "sessionSignKey",
            configJson(sessionSignKey = KEY.dropLast(1) + "g") to "sessionSignKey",
            configJson(sessionHours = 0) to "sessionHours",
            configJson(sessionHours = 721) to "sessionHours",
            configJson(unlockAttemptsPerMinute = 0) to "unlockAttemptsPerMinute",
            configJson(loginAttemptsPerMinute = 0) to "loginAttemptsPerMinute",
            configJson(cryptoParallelism = 0) to "cryptoParallelism",
            configJson(cryptoParallelism = 5) to "cryptoParallelism must be between 1 and 4",
            configJson(minSheetPasswordLength = 0) to "minSheetPasswordLength",
            configJson(minSheetPasswordLength = 1025) to "minSheetPasswordLength",
            configJson(maxSheetBytes = 1023) to "maxSheetBytes",
            configJson(maxSheetBytes = 10_485_761) to "maxSheetBytes",
            configJson(language = "fr") to "language must be one of en, de",
            configJson(language = "DE") to "language must be one of en, de",
            configJson(authorizeUrl = "http://id.example.org/authorize") to "oidc.authorizeUrl",
            configJson(tokenUrl = "ftp://id.example.org/token") to "oidc.tokenUrl",
            configJson(userinfoUrl = "/api/oidc/userinfo") to "oidc.userinfoUrl",
            configJson(clientId = " ") to "oidc.clientId must not be blank",
            configJson(clientSecret = "REPLACE_ME") to "oidc.clientSecret still holds the placeholder",
            configJson(allowedSubjects = """["sub-1", " "]""") to "oidc.allowedSubjects must not contain blank entries",
        )

        cases.forEach { (json, expected) -> assertContains(failure(load(json)), expected) }
    }

    @Test
    fun `values on the edge of each rule are accepted`() {
        val json = configJson(
            listenPort = 65535,
            sessionHours = 720,
            cryptoParallelism = 4,
            minSheetPasswordLength = 1,
            maxSheetBytes = 10_485_760,
            language = "de",
            tokenUrl = "http://10.0.0.5:1411/api/oidc/token",
            userinfoUrl = "http://10.0.0.5:1411/api/oidc/userinfo",
        )

        assertIs<ResultOf2.Success<AppConfig>>(load(json))
    }

    @Test
    fun `plain http is accepted for localhost only`() {
        val local = configJson(publicBaseUrl = "http://localhost:8080", authorizeUrl = "http://127.0.0.1:9090/authorize")

        assertIs<ResultOf2.Success<AppConfig>>(load(local))
    }

    @Test
    fun `all problems are reported at once`() {
        val message = failure(load(configJson(listenPort = 0, sessionHours = 0, clientId = "")))

        assertContains(message, "listenPort")
        assertContains(message, "sessionHours")
        assertContains(message, "oidc.clientId")
    }

    @Test
    fun `unknown keys are rejected without the developer hint`() {
        val message = failure(load(configJson().replaceFirst("{", """{ "sessionHour": 3,""")))

        assertContains(message, "sessionHour")
        assertFalse(message.contains("ignoreUnknownKeys"), message)
    }

    @Test
    fun `invalid json is reported without echoing the file content`() {
        val message = failure(load("""{"oidc": {"clientSecret": "super-secret-value" """))

        assertFalse(message.contains("super-secret-value"), message)
    }

    @Test
    fun `a missing required field is reported`() {
        assertContains(failure(load("""{"publicBaseUrl": "https://notes.example.org"}""")), "trustedProxyIp")
    }

    @Test
    fun `a missing file is reported`() {
        assertContains(failure(loadConfig("/nonexistent/knote-config.json")), "/nonexistent/knote-config.json")
    }

    @Test
    fun `an unreadable file is reported as such`() {
        val directory = File.createTempFile("knote-config", "").apply {
            delete()
            mkdir()
            deleteOnExit()
        }

        assertContains(failure(loadConfig(directory.absolutePath)), "could not be read")
    }

    @Test
    fun `secrets are masked in toString`() {
        val config = assertIs<ResultOf2.Success<AppConfig>>(load(configJson())).value

        val printed = config.toString()
        assertFalse(printed.contains(KEY), printed)
        assertFalse(printed.contains("secret-value"), printed)
    }

    private fun load(json: String): ResultOf2<AppConfig, String> {
        val file = File.createTempFile("knote-config", ".json").apply { deleteOnExit() }
        file.writeText(json)
        return loadConfig(file.absolutePath, cpuCount = 4)
    }

    private fun failure(result: ResultOf2<AppConfig, String>): String = assertIs<ResultOf2.Failure<String>>(result).value
}
