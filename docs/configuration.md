# Configuration and command line

## Command line

| Argument        | Default       | Description                                                                                                   |
|-----------------|---------------|---------------------------------------------------------------------------------------------------------------|
| `config=<path>` | `config.json` | Config file to load.                                                                                          |
| `check`         |               | Loads and validates the config and every sheet file, then exits. Useful before a restart.                    |
| `key`           |               | Prints a fresh `sessionSignKey` (64 hex characters) and exits. Needs no config. Key on stdout, hint on stderr. |

Without `check` or `key`, KNote starts the server. Any other argument is rejected.

| Exit code | Meaning                                                                                    |
|-----------|--------------------------------------------------------------------------------------------|
| `0`       | `check`: config and sheets are valid. `key`: key printed.                                  |
| `1`       | The server could not start, e.g. the port is taken. systemd retries.                       |
| `64`      | Unknown command line argument. systemd does not retry.                                     |
| `65`      | At least one sheet file is broken. The log lists every problem. systemd does not retry.    |
| `78`      | The config file is missing or invalid. systemd does not retry.                             |
| `143`     | The JVM's exit code after SIGTERM (`systemctl stop`). A clean stop, not a failure.         |

## Config file

One JSON file, read once at startup, so restart after a change. Unknown keys are errors, all validation problems
are reported at once, and error messages never contain the file content. See
[config.example.json](../config.example.json).

| Field                     | Type   | Default       | Rule                                          | Description                                                                                     |
|---------------------------|--------|---------------|-----------------------------------------------|-------------------------------------------------------------------------------------------------|
| `listenHost`              | String | `"127.0.0.1"` | not blank                                     | Interface to bind to. Use `"0.0.0.0"` or a specific address if the reverse proxy runs on another machine or container. |
| `listenPort`              | Int    | `8080`        | 1 to 65535                                    | Port to listen on.                                                                              |
| `publicBaseUrl`           | String | required      | `https://` origin, no path, no trailing slash | Address the browser sees, e.g. `https://notes.example.org`. Builds the OAuth callback URL and is the only origin admin forms may come from. |
| `dataDir`                 | String | `"data"`      | not blank                                     | Sheets live in `<dataDir>/sheets`.                                                              |
| `trustedProxyIp`          | String | required      | IPv4 address                                  | Address the reverse proxy connects from. Only this peer may name the client in `X-Forwarded-For`. |
| `sessionSignKey`          | String | required      | exactly 64 hex characters                     | HMAC key of the admin cookie. Create it with `java -jar knote.jar key`.                        |
| `sessionHours`            | Int    | `12`          | 1 to 720                                      | Lifetime of an admin login.                                                                     |
| `unlockAttemptsPerMinute` | Int    | `5`           | at least 1                                    | Password attempts per client IP and minute, public and admin unlock combined.                   |
| `loginAttemptsPerMinute`  | Int    | `10`          | at least 1                                    | Requests to `/_auth/login` and `/_auth/callback` per client IP and minute.                      |
| `cryptoParallelism`       | Int    | `1`           | 1 to the number of CPUs                       | PBKDF2 derivations that may run at the same time.                                               |
| `minSheetPasswordLength`  | Int    | `12`          | 1 to 1024                                     | Minimum length of a sheet password.                                                             |
| `maxSheetBytes`           | Int    | `1048576`     | 1024 to 10485760                              | Maximum size of the Markdown of one sheet in UTF-8 bytes.                                       |
| `language`                | String | `"en"`        | `"en"` or `"de"`                              | Language of the web pages.                                                                      |
| `showPoweredBy`           | Bool   | `true`        |                                               | Shows "Powered by KNote" with a link to the repository at the bottom of every page.             |
| `oidc.authorizeUrl`       | String | required      | `https://`                                    | Authorization endpoint, opened by the browser.                                                  |
| `oidc.tokenUrl`           | String | required      | `https://` or `http://`                       | Token endpoint, called by KNote. May be an internal address.                                    |
| `oidc.userinfoUrl`        | String | required      | `https://` or `http://`                       | Userinfo endpoint, called by KNote. May be an internal address.                                 |
| `oidc.clientId`           | String | required      | not blank, not a placeholder                  | Client ID from the OIDC provider.                                                               |
| `oidc.clientSecret`       | String | required      | not blank, not a placeholder                  | Client secret from the OIDC provider. Never logged.                                             |
| `oidc.allowedSubjects`    | List   | `[]`          | no blank entries                              | Optional. Empty: every account the provider lets through is admin. Otherwise only these `sub` values. |

`http://` is accepted for `publicBaseUrl` and `oidc.authorizeUrl` only with the host `localhost` or `127.0.0.1`,
for a local try without TLS. Values starting with `REPLACE_ME` in `clientId` and `clientSecret` are rejected, so
`check` with the unchanged example config reports exactly the fields that must be filled in.

## Adding a language

All texts of the web pages live in `src/main/kotlin/bayern/kickner/knote/i18n`. A new language is one more object
implementing `Texts` (copy `EnglishTexts.kt` and translate it) plus an entry in the `Language` enum with its code.
The compiler reports every text that is missing.
