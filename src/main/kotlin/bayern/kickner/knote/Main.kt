package bayern.kickner.knote

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.slf4j.slf4jBridge
import bayern.kickner.klogger.staticLog
import bayern.kickner.knote.auth.LOGIN_RATE_LIMIT
import bayern.kickner.knote.auth.adminCookie
import bayern.kickner.knote.auth.adminSession
import bayern.kickner.knote.auth.authRoutes
import bayern.kickner.knote.auth.oidc
import bayern.kickner.knote.config.AppConfig
import bayern.kickner.knote.config.configWarnings
import bayern.kickner.knote.config.loadConfig
import bayern.kickner.knote.crypto.CryptoGate
import bayern.kickner.knote.crypto.SESSION_KEY_FILE
import bayern.kickner.knote.crypto.UnlockProof
import bayern.kickner.knote.crypto.createSessionKey
import bayern.kickner.knote.crypto.readSessionKey
import bayern.kickner.knote.settings.SettingsStore
import bayern.kickner.knote.i18n.Language
import bayern.kickner.knote.i18n.TextsKey
import bayern.kickner.knote.storage.SheetStore
import bayern.kickner.knote.storage.createPrivateDirectory
import bayern.kickner.knote.storage.openStore
import bayern.kickner.knote.web.PageSettings
import bayern.kickner.knote.web.PageSettingsKey
import bayern.kickner.knote.web.SecurityHeaders
import bayern.kickner.knote.web.UNLOCK_RATE_LIMIT
import bayern.kickner.knote.web.adminRoutes
import bayern.kickner.knote.web.clientIp
import bayern.kickner.knote.web.healthRoute
import bayern.kickner.knote.web.knoteErrorPages
import bayern.kickner.knote.web.rateLimitKey
import bayern.kickner.knote.web.sheetRoutes
import bayern.kickner.knote.web.startPage
import bayern.kickner.knote.web.untrustedPeerWarning
import io.ktor.client.HttpClient
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import kotnexlib.ArgsInterpreter
import kotnexlib.ResultOf2
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes
import io.ktor.client.engine.cio.CIO as ClientCIO

private const val TAG = "Main"

/**
 * The server could not start, typically because the address could not be bound. A plain failure, so systemd
 * restarts KNote: an address that is not assigned yet at boot usually is a few seconds later.
 */
private const val EXIT_START_FAILED = 1

/** `EX_USAGE` from sysexits.h: an unknown command line argument. A typo must not silently start the server. */
private const val EXIT_USAGE = 64

/**
 * `EX_DATAERR` from sysexits.h: a sheet file, the settings or the session key is broken or cannot be written.
 * knote.service does not restart on it, because a restart repairs no file.
 */
private const val EXIT_DATA_ERROR = 65

/** `EX_CONFIG` from sysexits.h: the config file is missing or invalid. knote.service does not restart on it. */
private const val EXIT_CONFIG_ERROR = 78

/** Settings changed in the admin area, next to the sheet directory in `dataDir`. */
private const val SETTINGS_FILE = "settings.json"

private const val USAGE = "Usage: java -jar knote.jar [config=<path>] [check]"

/**
 * Room for the form fields around the Markdown. The Markdown itself counts three times: a form post percent encodes
 * every byte outside ASCII letters and digits, which turns one byte into three.
 */
private const val FORM_OVERHEAD_BYTES = 64L * 1024

/** Every printed timestamp, e.g. `12.10.2026 18:40:12 CEST`. English, so the zone abbreviation never localizes. */
val timestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z", Locale.ENGLISH)

/** Version and build time for the log banner, e.g. `1.0.0 (built 12.10.2026 12:06:19 CEST)`. */
internal val appVersion: String =
    "${BuildConfig.VERSION} (built ${Instant.ofEpochMilli(BuildConfig.BUILD_TIME).atZone(ZoneId.systemDefault()).format(timestampFormat)})"

/**
 * Entry point. Loads the config (`config=<path>`, default `config.json` in the working directory) and all sheets,
 * then serves until the process is stopped.
 *
 * `check` loads and validates config, sheets, settings and session key, then exits without changing anything. On the
 * first start the sheet directory is created with an example sheet, and the session key file with a fresh key. Exit
 * codes: [EXIT_USAGE] for an unknown argument, [EXIT_CONFIG_ERROR] for a rejected config, [EXIT_DATA_ERROR] for
 * broken files in `dataDir`, [EXIT_START_FAILED] if the server cannot start.
 */
fun main(args: Array<String>) {
    // The QR codes are drawn with Java2D, a server has no display
    System.setProperty("java.awt.headless", "true")
    KLogger.configure {
        logToConsole()
        minLevel = KLogger.Level.DEBUG
        // Ktor logs through SLF4J, which Klogger picks up. Only its warnings and errors are worth the journal.
        slf4jBridge { minLevel = KLogger.Level.WARN }
    }

    val unknown = args.filterNot(::isKnownArgument)
    if (unknown.isNotEmpty()) {
        staticLog(KLogger.Level.ERROR, TAG) { "Unknown argument(s): ${unknown.joinToString(" ")}. $USAGE" }
        exitProcess(EXIT_USAGE)
    }

    val configPath = ArgsInterpreter(args).getValue("config") ?: "config.json"
    val config = when (val result = loadConfig(configPath)) {
        is ResultOf2.Success -> result.value
        is ResultOf2.Failure -> {
            staticLog(KLogger.Level.ERROR, TAG) { result.value }
            exitProcess(EXIT_CONFIG_ERROR)
        }
    }
    configWarnings(config).forEach { warning -> staticLog(KLogger.Level.WARN, TAG) { warning } }

    val keyFile = Path.of(config.dataDir, SESSION_KEY_FILE).toAbsolutePath()
    val existingKey = when (val result = readSessionKey(keyFile)) {
        is ResultOf2.Success -> result.value
        is ResultOf2.Failure -> {
            staticLog(KLogger.Level.ERROR, TAG) { result.value }
            exitProcess(EXIT_DATA_ERROR)
        }
    }

    val sheetDir = Path.of(config.dataDir, "sheets").toAbsolutePath()
    // `check` changes nothing on disk, the first real start creates the directory and the example sheet
    if (args.contains("check") && Files.notExists(sheetDir)) {
        staticLog(KLogger.Level.INFO, TAG) { "Config file ${File(configPath).absolutePath} is valid. $sheetDir does not exist yet, the first start creates it with an example sheet${if (existingKey == null) ", and $keyFile" else ""}" }
        return
    }
    runCatching { createPrivateDirectory(Path.of(config.dataDir)) }.onFailure { error ->
        staticLog(KLogger.Level.ERROR, TAG) { "${Path.of(config.dataDir).toAbsolutePath()} cannot be created: ${error.message}" }
        exitProcess(EXIT_DATA_ERROR)
    }
    val store = when (val result = openStore(sheetDir)) {
        is ResultOf2.Success -> result.value
        is ResultOf2.Failure -> {
            staticLog(KLogger.Level.ERROR, TAG) { "Sheet files in $sheetDir are broken, fix or move them:\n" + result.value.joinToString("\n") { "  $it" } }
            exitProcess(EXIT_DATA_ERROR)
        }
    }

    val settings = when (val result = SettingsStore.load(Path.of(config.dataDir, SETTINGS_FILE).toAbsolutePath())) {
        is ResultOf2.Success -> result.value
        is ResultOf2.Failure -> {
            staticLog(KLogger.Level.ERROR, TAG) { "The settings file is broken, fix or move it: ${result.value}" }
            exitProcess(EXIT_DATA_ERROR)
        }
    }

    // ArgsInterpreter only knows `key=value` pairs and `-flags`, a bare word is checked directly
    if (args.contains("check")) {
        val keyNote = if (existingKey == null) ", the first start creates $keyFile" else ""
        staticLog(KLogger.Level.INFO, TAG) { "Config file ${File(configPath).absolutePath} is valid, ${store.size} sheet(s) in $sheetDir are readable$keyNote" }
        return
    }

    val sessionKey = existingKey ?: when (val result = createSessionKey(keyFile)) {
        is ResultOf2.Success -> result.value.also { staticLog(KLogger.Level.INFO, TAG) { "First start: created the session key in $keyFile" } }
        is ResultOf2.Failure -> {
            staticLog(KLogger.Level.ERROR, TAG) { result.value }
            exitProcess(EXIT_DATA_ERROR)
        }
    }

    val server = knoteServer(config, store, settings, sessionKey, sheetDir)
    runCatching { server.start(wait = true) }.onFailure { error ->
        staticLog(KLogger.Level.ERROR, TAG) { "Could not start on ${config.listenHost}:${config.listenPort}: ${error.rootCause().message}" }
        exitProcess(EXIT_START_FAILED)
    }
}

/**
 * Builds the HTTP server on CIO and logs the banner. The HTTP client talks to the token and userinfo endpoints.
 *
 * @param sheetDir Only for the banner, [store] is already loaded from it.
 */
fun knoteServer(
    config: AppConfig,
    store: SheetStore,
    settings: SettingsStore,
    sessionKey: ByteArray,
    sheetDir: Path,
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
    staticLog(KLogger.Level.INFO, TAG) {
        "KNote $appVersion listening on ${config.listenHost}:${config.listenPort} with ${store.size} sheet(s) in $sheetDir"
    }
    val httpClient = HttpClient(ClientCIO) { engine { requestTimeout = 10_000 } }
    return embeddedServer(CIO, host = config.listenHost, port = config.listenPort) { knote(config, store, settings, sessionKey, httpClient) }
}

/**
 * Ktor module wiring all plugins and routes. Kept apart from the server setup so route tests can host it with a
 * fake OIDC provider, a fixed clock and a temporary sheet directory.
 *
 * @param settings The settings changed in the admin area.
 * @param sessionKey Signs the admin cookie and the unlock proofs, from `readSessionKey`.
 * @param httpClient Calls the token and userinfo endpoints of the OIDC provider.
 * @param clock Current time in Unix milliseconds.
 * @param zone Time zone of the timestamps in the overview.
 */
fun Application.knote(
    config: AppConfig,
    store: SheetStore,
    settings: SettingsStore,
    sessionKey: ByteArray,
    httpClient: HttpClient,
    cryptoGate: CryptoGate = CryptoGate(config.cryptoParallelism),
    clock: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    // The config loader rejects unknown codes, English covers configs built in code
    attributes.put(TextsKey, (Language.of(config.language) ?: Language.ENGLISH).texts)
    attributes.put(PageSettingsKey, PageSettings(config.showPoweredBy, config.publicBaseUrl))
    install(SecurityHeaders)
    install(untrustedPeerWarning(config.trustedProxyIp))
    install(RequestBodyLimit) {
        bodyLimit { 3L * config.maxSheetBytes + FORM_OVERHEAD_BYTES }
    }
    // HEAD answers like GET without a body, monitoring tools and `curl -I` use it
    install(AutoHeadResponse)
    install(StatusPages) { knoteErrorPages() }
    install(Sessions) { adminCookie(config, sessionKey) }
    install(Authentication) {
        oidc(config, httpClient)
        adminSession(config, clock)
    }
    // Without requestKey all clients would share one limit. IPv6 clients share one per /64, see rateLimitKey
    install(RateLimit) {
        register(UNLOCK_RATE_LIMIT) {
            rateLimiter(limit = config.unlockAttemptsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> rateLimitKey(call.clientIp(config.trustedProxyIp)) }
        }
        register(LOGIN_RATE_LIMIT) {
            rateLimiter(limit = config.loginAttemptsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> rateLimitKey(call.clientIp(config.trustedProxyIp)) }
        }
    }
    routing {
        staticResources("/_static", "static")
        healthRoute()
        startPage(store, settings, config, clock)
        authRoutes(config, httpClient, clock)
        adminRoutes(store, settings, cryptoGate, UnlockProof(sessionKey), config, clock, zone)
        sheetRoutes(store, cryptoGate, config, zone, clock)
    }
}

/** True for every argument KNote knows. */
private fun isKnownArgument(arg: String) = arg == "check" || arg.startsWith("config=")

private fun Throwable.rootCause(): Throwable = generateSequence(this) { it.cause }.last()
