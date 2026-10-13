package bayern.kickner.knote.config

import kotlinx.serialization.Serializable

/**
 * Root configuration, loaded once at startup from the JSON config file (see [loadConfig]).
 *
 * @property listenHost Interface the HTTP server binds to. Inside a container this is the container address or
 * `0.0.0.0`, because the reverse proxy connects from outside.
 * @property listenPort Port the HTTP server listens on.
 * @property publicBaseUrl Origin of KNote as the browser sees it, e.g. `https://notes.example.org`. Builds the OAuth
 * callback URL and is the only origin admin forms are accepted from.
 * @property dataDir Data directory. Sheets live in `<dataDir>/sheets`.
 * @property trustedProxyIp IPv4 address the reverse proxy connects from. Only this peer may name the client in
 * `X-Forwarded-For`.
 * @property sessionSignKey 32 bytes as 64 hex characters. Signs the admin cookie with HMAC-SHA256.
 * @property sessionHours Lifetime of an admin login.
 * @property unlockAttemptsPerMinute Password attempts per client IP and minute, public and admin unlock combined.
 * @property loginAttemptsPerMinute Requests to the two login endpoints per client IP and minute.
 * @property cryptoParallelism PBKDF2 derivations that may run at the same time.
 * @property minSheetPasswordLength Minimum length of a sheet password.
 * @property maxSheetBytes Maximum size of the Markdown of one sheet in UTF-8 bytes.
 * @property language Language of the web pages, a code from [bayern.kickner.knote.i18n.Language].
 * @property showPoweredBy Shows "Powered by KNote" with a link to the repository at the bottom of every page.
 * @property oidc Admin login through the OIDC provider.
 */
@Serializable
data class AppConfig(
    val listenHost: String = "127.0.0.1",
    val listenPort: Int = 8080,
    val publicBaseUrl: String,
    val dataDir: String = "data",
    val trustedProxyIp: String,
    val sessionSignKey: String,
    val sessionHours: Int = 12,
    val unlockAttemptsPerMinute: Int = 5,
    val loginAttemptsPerMinute: Int = 10,
    val cryptoParallelism: Int = 1,
    val minSheetPasswordLength: Int = 12,
    val maxSheetBytes: Int = 1_048_576,
    val language: String = "en",
    val showPoweredBy: Boolean = true,
    val oidc: OidcConfig,
) {
    override fun toString() =
        "AppConfig(listenHost='$listenHost', listenPort=$listenPort, publicBaseUrl='$publicBaseUrl', dataDir='$dataDir', " +
            "trustedProxyIp='$trustedProxyIp', sessionHours=$sessionHours, oidc=$oidc)"
}

/**
 * The OIDC client registered at the provider (Pocket ID).
 *
 * @property authorizeUrl Authorization endpoint, opened by the browser.
 * @property tokenUrl Token endpoint, called by KNote itself. May be an internal `http://` address.
 * @property userinfoUrl Userinfo endpoint, called by KNote itself. May be an internal `http://` address.
 * @property clientId Client ID from the provider.
 * @property clientSecret Client secret from the provider. Never logged.
 * @property allowedSubjects Optional filter. Empty: every account the provider lets through is admin, access is
 * managed at the provider (e.g. a group restriction on the client). Otherwise only these `sub` values.
 */
@Serializable
data class OidcConfig(
    val authorizeUrl: String,
    val tokenUrl: String,
    val userinfoUrl: String,
    val clientId: String,
    val clientSecret: String,
    val allowedSubjects: List<String> = emptyList(),
) {
    override fun toString() =
        "OidcConfig(authorizeUrl='$authorizeUrl', tokenUrl='$tokenUrl', userinfoUrl='$userinfoUrl', clientId='$clientId', " +
            "allowedSubjects=$allowedSubjects)"
}
