package bayern.kickner.knote.config

import bayern.kickner.knote.i18n.Language
import kotlinx.serialization.json.Json
import kotnexlib.ResultOf2
import java.io.File
import java.net.URI

/** Values in config.example.json that must be replaced start with this. */
const val PLACEHOLDER_PREFIX = "REPLACE_ME"

/** Upper bound for a sheet password. A longer one is a mistake or an attempt to waste PBKDF2 time. */
const val MAX_SHEET_PASSWORD_LENGTH = 1024

private val IPV4_REGEX = Regex("^((25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])$")
private val HEX_KEY_REGEX = Regex("^[0-9a-fA-F]{64}$")

/** Plain HTTP is accepted for these hosts only, so KNote can be tried locally without TLS. */
private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1")

/**
 * Loads and validates the configuration file at [path].
 *
 * Unknown keys are errors, so a typo cannot silently fall back to a default. Every validation issue is collected
 * into one message, so a broken config needs only one restart to fix.
 *
 * @param cpuCount Upper bound for `cryptoParallelism`, injectable for tests.
 * @return the config, or a readable failure message that never contains the file content (it holds secrets).
 */
fun loadConfig(path: String, cpuCount: Int = Runtime.getRuntime().availableProcessors()): ResultOf2<AppConfig, String> {
    val file = File(path)
    if (file.exists().not()) return ResultOf2.Failure("Config file not found: ${file.absolutePath}")

    val text = runCatching { file.readText() }
        .getOrElse { return ResultOf2.Failure("Config file ${file.absolutePath} could not be read: ${it.message}") }
    val config = runCatching { Json.decodeFromString<AppConfig>(text) }
        .getOrElse { return ResultOf2.Failure("Config file ${file.absolutePath} could not be parsed: ${firstLineOf(it.message)}") }

    val issues = validate(config, cpuCount)
    if (issues.isNotEmpty()) return ResultOf2.Failure("Config file ${file.absolutePath} is invalid:\n" + issues.joinToString("\n") { "  $it" })

    return ResultOf2.Success(config)
}

/**
 * Problems that do not stop KNote but deserve a line in the log: a placeholder left in `allowedSubjects` locks
 * every account out.
 */
fun configWarnings(config: AppConfig): List<String> {
    val placeholderSubject = config.oidc.allowedSubjects.any { it.startsWith(PLACEHOLDER_PREFIX) }
    return if (placeholderSubject) listOf("oidc.allowedSubjects holds a placeholder, nobody can log in. Remove the field to allow every account of the OIDC provider.")
    else emptyList()
}

/**
 * Keeps only the first line of a kotlinx.serialization message: the following lines are either a developer hint
 * ("Use 'ignoreUnknownKeys = true' ...") or the offending document ("JSON input: ..."), which holds secrets.
 */
internal fun firstLineOf(message: String?): String =
    message?.substringBefore("JSON input")?.lineSequence()?.first()?.trim()?.ifEmpty { null } ?: "unknown error"

private fun validate(config: AppConfig, cpuCount: Int): List<String> {
    val issues = mutableListOf<String>()

    if (config.listenHost.isBlank()) issues += "listenHost must not be blank"
    if (config.listenPort !in 1..65535) issues += "listenPort must be between 1 and 65535"
    if (config.publicBaseUrl.isOrigin().not())
        issues += "publicBaseUrl must be an https:// origin without path and trailing slash, e.g. https://notes.example.org (http:// only for localhost)"
    if (config.dataDir.isBlank()) issues += "dataDir must not be blank"
    if (IPV4_REGEX.matches(config.trustedProxyIp).not()) issues += "trustedProxyIp must be an IPv4 address"
    if (HEX_KEY_REGEX.matches(config.sessionSignKey).not()) issues += "sessionSignKey must be exactly 64 hex characters, create one with 'java -jar knote.jar key'"
    if (config.sessionHours !in 1..720) issues += "sessionHours must be between 1 and 720"
    if (config.unlockAttemptsPerMinute < 1) issues += "unlockAttemptsPerMinute must be at least 1"
    if (config.loginAttemptsPerMinute < 1) issues += "loginAttemptsPerMinute must be at least 1"
    if (config.cryptoParallelism !in 1..cpuCount) issues += "cryptoParallelism must be between 1 and $cpuCount (the number of CPUs)"
    // At least 1: on a new sheet, empty password fields mean "no password"
    if (config.minSheetPasswordLength !in 1..MAX_SHEET_PASSWORD_LENGTH)
        issues += "minSheetPasswordLength must be between 1 and $MAX_SHEET_PASSWORD_LENGTH"
    if (config.maxSheetBytes !in 1024..10_485_760) issues += "maxSheetBytes must be between 1024 and 10485760"
    if (Language.of(config.language) == null) issues += "language must be one of ${Language.entries.joinToString { it.code }}"

    val oidc = config.oidc
    if (oidc.authorizeUrl.isUrl(local = true).not()) issues += "oidc.authorizeUrl must start with https:// (http:// only for localhost)"
    if (oidc.tokenUrl.isUrl(plainHttp = true).not()) issues += "oidc.tokenUrl must start with https:// or http://"
    if (oidc.userinfoUrl.isUrl(plainHttp = true).not()) issues += "oidc.userinfoUrl must start with https:// or http://"
    issues += requiredValue("oidc.clientId", oidc.clientId)
    issues += requiredValue("oidc.clientSecret", oidc.clientSecret)
    if (oidc.allowedSubjects.any { it.isBlank() }) issues += "oidc.allowedSubjects must not contain blank entries"

    return issues
}

/** A required string that must be neither blank nor the placeholder from config.example.json. */
private fun requiredValue(name: String, value: String): List<String> = when {
    value.isBlank() -> listOf("$name must not be blank")
    value.startsWith(PLACEHOLDER_PREFIX) -> listOf("$name still holds the placeholder $PLACEHOLDER_PREFIX")
    else -> emptyList()
}

private fun String.toUriOrNull(): URI? = runCatching { URI(this) }.getOrNull()?.takeIf { it.host.isNullOrEmpty().not() }

/** True for an absolute URL with a host. `https` always, `http` with [plainHttp] or for a local host with [local]. */
private fun String.isUrl(plainHttp: Boolean = false, local: Boolean = false): Boolean {
    val uri = toUriOrNull() ?: return false
    val httpAllowed = plainHttp || (local && uri.host in LOCAL_HOSTS)
    return uri.scheme == "https" || (uri.scheme == "http" && httpAllowed)
}

/** True for scheme, host and optional port, nothing else. The CSRF check compares the browser's Origin with it. */
private fun String.isOrigin(): Boolean {
    val uri = toUriOrNull() ?: return false
    val bare = uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null
    return bare && isUrl(local = true)
}
