package bayern.kickner.knote

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.slf4j.slf4jBridge
import bayern.kickner.klogger.staticLog
import bayern.kickner.knote.auth.LOGIN_RATE_LIMIT
import bayern.kickner.knote.auth.adminCookie
import bayern.kickner.knote.auth.adminSession
import bayern.kickner.knote.auth.authRoutes
import bayern.kickner.knote.auth.oidc
import bayern.kickner.knote.cli.generateSessionKey
import bayern.kickner.knote.config.AppConfig
import bayern.kickner.knote.config.configWarnings
import bayern.kickner.knote.config.loadConfig
import bayern.kickner.knote.crypto.CryptoGate
import bayern.kickner.knote.crypto.UnlockProof
import bayern.kickner.knote.i18n.Language
import bayern.kickner.knote.i18n.TextsKey
import bayern.kickner.knote.storage.SheetStore
import bayern.kickner.knote.web.PoweredByKey
import bayern.kickner.knote.web.SecurityHeaders
import bayern.kickner.knote.web.UNLOCK_RATE_LIMIT
import bayern.kickner.knote.web.adminRoutes
import bayern.kickner.knote.web.clientIp
import bayern.kickner.knote.web.healthRoute
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
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import kotnexlib.ArgsInterpreter
import kotnexlib.ResultOf2
import java.io.File
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
 * `EX_DATAERR` from sysexits.h: at least one sheet file is broken. knote.service does not restart on it, because a
 * restart repairs no file.
 */
private const val EXIT_DATA_ERROR = 65

/** `EX_CONFIG` from sysexits.h: the config file is missing or invalid. knote.service does not restart on it. */
private const val EXIT_CONFIG_ERROR = 78

private const val USAGE = "Usage: java -jar knote.jar [config=<path>] [check | key]"

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
 * `key` prints a fresh `sessionSignKey` and needs no config. `check` loads and validates config and sheets, then
 * exits. Exit codes: [EXIT_USAGE] for an unknown argument, [EXIT_CONFIG_ERROR] for a rejected config,
 * [EXIT_DATA_ERROR] for broken sheet files, [EXIT_START_FAILED] if the server cannot start.
 */
fun main(args: Array<String>) {
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

    // ArgsInterpreter only knows `key=value` pairs and `-flags`, a bare word is checked directly
    if (args.contains("key")) {
        // Key on stdout, hint on stderr, so `KEY=$(java -jar knote.jar key)` captures only the key
        System.err.println("Fresh sessionSignKey for config.json. Keep it secret:")
        println(generateSessionKey())
        return
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

    val sheetDir = Path.of(config.dataDir, "sheets").toAbsolutePath()
    val store = when (val result = SheetStore.load(sheetDir)) {
        is ResultOf2.Success -> result.value
        is ResultOf2.Failure -> {
            staticLog(KLogger.Level.ERROR, TAG) { "Sheet files in $sheetDir are broken, fix or move them:\n" + result.value.joinToString("\n") { "  $it" } }
            exitProcess(EXIT_DATA_ERROR)
        }
    }

    if (args.contains("check")) {
        staticLog(KLogger.Level.INFO, TAG) { "Config file ${File(configPath).absolutePath} is valid, ${store.size} sheet(s) in $sheetDir are readable" }
        return
    }

    val server = knoteServer(config, store, sheetDir)
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
fun knoteServer(config: AppConfig, store: SheetStore, sheetDir: Path): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
    staticLog(KLogger.Level.INFO, TAG) {
        "KNote $appVersion listening on ${config.listenHost}:${config.listenPort} with ${store.size} sheet(s) in $sheetDir"
    }
    val httpClient = HttpClient(ClientCIO) { engine { requestTimeout = 10_000 } }
    return embeddedServer(CIO, host = config.listenHost, port = config.listenPort) { knote(config, store, httpClient) }
}

/**
 * Ktor module wiring all plugins and routes. Kept apart from the server setup so route tests can host it with a
 * fake OIDC provider, a fixed clock and a temporary sheet directory.
 *
 * @param httpClient Calls the token and userinfo endpoints of the OIDC provider.
 * @param clock Current time in Unix milliseconds.
 * @param zone Time zone of the timestamps in the overview.
 */
fun Application.knote(
    config: AppConfig,
    store: SheetStore,
    httpClient: HttpClient,
    cryptoGate: CryptoGate = CryptoGate(config.cryptoParallelism),
    clock: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    // The config loader rejects unknown codes, English covers configs built in code
    attributes.put(TextsKey, (Language.of(config.language) ?: Language.ENGLISH).texts)
    attributes.put(PoweredByKey, config.showPoweredBy)
    install(SecurityHeaders)
    install(untrustedPeerWarning(config.trustedProxyIp))
    install(RequestBodyLimit) {
        bodyLimit { 3L * config.maxSheetBytes + FORM_OVERHEAD_BYTES }
    }
    install(Sessions) { adminCookie(config) }
    install(Authentication) {
        oidc(config, httpClient)
        adminSession(config, clock)
    }
    // Without requestKey all clients would share one limit
    install(RateLimit) {
        register(UNLOCK_RATE_LIMIT) {
            rateLimiter(limit = config.unlockAttemptsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.clientIp(config.trustedProxyIp) }
        }
        register(LOGIN_RATE_LIMIT) {
            rateLimiter(limit = config.loginAttemptsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.clientIp(config.trustedProxyIp) }
        }
    }
    routing {
        staticResources("/_static", "static")
        healthRoute()
        startPage(config, clock)
        authRoutes(config, httpClient, clock)
        adminRoutes(store, cryptoGate, UnlockProof(config.sessionSignKey.hexToByteArray()), config, clock, zone)
        sheetRoutes(store, cryptoGate, config)
    }
}

/** True for every argument KNote knows. */
private fun isKnownArgument(arg: String) = arg == "key" || arg == "check" || arg.startsWith("config=")

private fun Throwable.rootCause(): Throwable = generateSequence(this) { it.cause }.last()
