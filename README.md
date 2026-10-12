# KNote

**K**otlin **Note**s: a small self hosted note service. KNote stores any number of sheets. Each sheet has a
freely chosen path and is reachable at `https://<domain>/<path>` without login. A sheet can be protected with its
own password and is then stored encrypted. Creating, editing, deleting and the overview need an admin login through
OIDC (Pocket ID). Content is Markdown.

KISS by design: no database, no JavaScript in the browser, no runtime configuration. One JSON config file, one JSON
file per sheet, one fat JAR, one systemd service. TLS termination is left to a reverse proxy (Zoraxy). KNote itself
listens on plain HTTP.

## Quick start

Requirements: JDK 25 to build (Amazon Corretto is the pinned toolchain, Gradle downloads it if missing) and a Java
25 runtime on the machine that runs `knote.jar`. Gradle itself also runs on Corretto 25, set by
[gradle/gradle-daemon-jvm.properties](gradle/gradle-daemon-jvm.properties). A newer default JDK on the machine (27
and up is too new for Gradle 9.6) therefore does not matter, and IntelliJ uses these criteria instead of its own
Gradle JVM setting. Recreate the file with `./gradlew updateDaemonJvm --jvm-version=25 --jvm-vendor=amazon`.

```bash
cp config.example.json config.json   # then fill in the OIDC client and a session key
java -jar build/libs/knote.jar key   # prints a value for sessionSignKey
./gradlew run                        # development: reads ./config.json
./gradlew buildFatJar                # production: build/libs/knote.jar
java -jar build/libs/knote.jar config=/path/to/config.json
```

Tests: `./gradlew test`. The workflow in `.github/workflows/test.yml` runs them on every push.

For a local try without TLS, `publicBaseUrl` and `oidc.authorizeUrl` may use `http://localhost` or
`http://127.0.0.1`. Browsers accept the Secure cookies of KNote on `localhost` as well.

## Command line

| Argument        | Default       | Description                                                                                                                     |
|-----------------|---------------|---------------------------------------------------------------------------------------------------------------------------------|
| `config=<path>` | `config.json` | Config file to load (see Configuration).                                                                                        |
| `check`         |               | Loads and validates the config and every sheet file, then exits. Useful before `systemctl restart knote`.                       |
| `key`           |               | Prints a fresh `sessionSignKey` (64 hex characters) and exits. Needs no config. The key goes to stdout, the hint to stderr.     |

Without `check` or `key`, KNote starts the server. Any other argument is rejected. Exit codes:

| Code  | Meaning                                                                                                             |
|-------|---------------------------------------------------------------------------------------------------------------------|
| `0`   | `check`: config and sheets are valid. `key`: key printed.                                                           |
| `1`   | The server could not start, e.g. the port is taken or the address is not assigned (yet). systemd retries.          |
| `64`  | Unknown command line argument. systemd does not retry.                                                              |
| `65`  | At least one sheet file is broken. The log lists every problem. systemd does not retry.                             |
| `78`  | The config file is missing or invalid. systemd does not retry.                                                      |
| `143` | The JVM's exit code after SIGTERM (`systemctl stop`). A clean stop, not a failure.                                  |

## Configuration

One JSON file, read once at startup, so restart after a change. Unknown keys are errors, all validation problems
are reported at once, and error messages never contain the file content. In every case KNote exits with code 78
instead of running with a half valid config. See [config.example.json](config.example.json).

| Field                     | Type   | Default       | Rule                                          | Description                                                                                         |
|---------------------------|--------|---------------|-----------------------------------------------|-----------------------------------------------------------------------------------------------------|
| `listenHost`              | String | `"127.0.0.1"` | not blank                                     | Interface to bind to. In a container: the container address or `"0.0.0.0"`, see Deployment.        |
| `listenPort`              | Int    | `8080`        | 1 to 65535                                    | Port to listen on.                                                                                  |
| `publicBaseUrl`           | String | required      | `https://` origin, no path, no trailing slash | Origin the browser sees. Builds the OAuth callback URL and is the only origin admin forms may come from. |
| `dataDir`                 | String | `"data"`      | not blank                                     | Sheets live in `<dataDir>/sheets`.                                                                  |
| `trustedProxyIp`          | String | required      | IPv4 address                                  | Address Zoraxy connects from. Only this peer may name the client in `X-Forwarded-For`.              |
| `sessionSignKey`          | String | required      | exactly 64 hex characters                     | HMAC key of the admin cookie. Create it with `java -jar knote.jar key`.                            |
| `sessionHours`            | Int    | `12`          | 1 to 720                                      | Lifetime of an admin login.                                                                         |
| `unlockAttemptsPerMinute` | Int    | `5`           | at least 1                                    | Password attempts per client IP and minute, public and admin unlock combined.                       |
| `loginAttemptsPerMinute`  | Int    | `10`          | at least 1                                    | Requests to `/_auth/login` and `/_auth/callback` per client IP and minute.                          |
| `cryptoParallelism`       | Int    | `1`           | 1 to the number of CPUs                       | PBKDF2 derivations that may run at the same time.                                                   |
| `minSheetPasswordLength`  | Int    | `12`          | 8 to 1024                                     | Minimum length of a sheet password.                                                                 |
| `maxSheetBytes`           | Int    | `1048576`     | 1024 to 10485760                              | Maximum size of the Markdown of one sheet in UTF-8 bytes.                                           |
| `oidc.authorizeUrl`       | String | required      | `https://`                                    | Authorization endpoint, opened by the browser.                                                      |
| `oidc.tokenUrl`           | String | required      | `https://` or `http://`                       | Token endpoint, called by KNote. May be an internal address of Pocket ID.                           |
| `oidc.userinfoUrl`        | String | required      | `https://` or `http://`                       | Userinfo endpoint, called by KNote. May be an internal address of Pocket ID.                        |
| `oidc.clientId`           | String | required      | not blank, not a placeholder                  | Client ID from Pocket ID.                                                                           |
| `oidc.clientSecret`       | String | required      | not blank, not a placeholder                  | Client secret from Pocket ID. Never logged.                                                         |
| `oidc.allowedSubjects`    | List   | required      | at least one entry                            | `sub` values of the admin accounts.                                                                 |

`http://` is accepted for `publicBaseUrl` and `oidc.authorizeUrl` only with the host `localhost` or `127.0.0.1`.
Values starting with `REPLACE_ME` in `clientId` and `clientSecret` are rejected, so `check` with the unchanged
example config reports exactly the three fields that must be filled in. A `REPLACE_ME` in `allowedSubjects` is
only a warning: it is how the own `sub` is found on first setup (see Deployment).

## Endpoints

| Method | Path                         | Access    | Rate limit | Response                                                                    |
|--------|------------------------------|-----------|------------|-----------------------------------------------------------------------------|
| GET    | `/`                          | everyone  |            | Start page with login button. A logged in admin is redirected to `/_admin`. |
| GET    | `/_static/knote.css`         | everyone  |            | The stylesheet.                                                             |
| GET    | `/_auth/login`               | everyone  | `login`    | `302` to Pocket ID, sets the state cookie.                                  |
| GET    | `/_auth/callback`            | everyone  | `login`    | `302` to `/_admin` with the admin cookie, or `403`/`502` with a message.    |
| POST   | `/_auth/logout`              | everyone  |            | Deletes the admin cookie, `303` to `/`.                                     |
| GET    | `/_admin`                    | admin     |            | Overview of all sheets, sorted by path.                                     |
| GET    | `/_admin/new`                | admin     |            | Form for a new sheet.                                                       |
| POST   | `/_admin/new`                | admin     |            | Creates the sheet, `303` to `/_admin`. `400` with the form on invalid input. With `action=preview`: the form again with the preview. |
| GET    | `/_admin/edit/{id}`          | admin     |            | Editor, or the password form for a protected sheet.                        |
| POST   | `/_admin/edit/{id}/unlock`   | admin     | `unlock`   | Editor with the decrypted content.                                          |
| POST   | `/_admin/edit/{id}`          | admin     |            | Saves, `303` to `/_admin`. `409` if the sheet changed meanwhile. With `action=preview`: the form again with the preview. |
| GET    | `/_admin/delete/{id}`        | admin     |            | Confirmation page.                                                          |
| POST   | `/_admin/delete/{id}`        | admin     |            | Deletes the sheet, `303` to `/_admin`.                                      |
| GET    | `/{path}`                    | everyone  |            | The rendered sheet, or the password form. `404` for unknown and invalid paths alike. |
| POST   | `/{path}`                    | everyone  | `unlock`   | Field `password`. The rendered sheet (`200`), or the form with `403`.       |

Admin pages address a sheet by its id, so sheet paths containing slashes never collide with `/edit` or `/delete`.
System paths start with `_`, which a sheet path can never contain. All pages are German.

A sheet path consists of segments of `a` to `z`, `0` to `9` and the hyphen, separated by single slashes, at most
128 characters. Titles are 1 to 100 characters without control characters.

## Behaviour

**Storage.** One JSON file per sheet in `<dataDir>/sheets`, named after the random id of the sheet. The path is a
field inside the file, so no URL ever becomes a file name. All sheets are kept in memory and found only through
that map. A change is written to a temp file in the same directory and moved over the old file atomically, and only
then applied in memory. At startup every file is checked: unknown keys, a file name that does not match the id, an
invalid path and a path used twice are errors (exit code 65). Leftover `*.tmp` files are ignored.

**Protected sheets.** The Markdown is encrypted with KotNexLib's `AES.GCM.encryptWithPassword` (PBKDF2 with
HMAC SHA256 and 600000 iterations, fresh salt and nonce per save, no compression). Path and title stay plain text,
so the overview works without passwords. Opening a protected sheet shows a password form. The POST answers with the
rendered sheet directly, there is no session: a reload asks for the password again. Editing a protected sheet needs
its password too. The editor keeps it in a hidden field and every save checks it against the stored content before
encrypting again. A forgotten password means the content is lost. The admin can still delete the sheet.

**PBKDF2 cost.** One derivation takes roughly 0.1 to 1 s of CPU depending on the machine. All derivations run off
the request threads and at most `cryptoParallelism` at once, so requests from many IPs cannot occupy every core.

**Admin login.** OIDC authorization code flow as confidential client (`client_secret_post`, token request as POST,
scope `openid`). The OAuth state is bound to the browser through the cookie `knote_oauth_state` (path
`/_auth/callback`, 10 minutes, compared in constant time). KNote reads `sub` from the userinfo endpoint and accepts
the login if `sub` is in `allowedSubjects`. The admin cookie `knote_admin` is signed with HMAC SHA256, HttpOnly,
Secure and `SameSite=Lax`. Its expiry and the `sub` are checked on every request, so a stolen cookie also expires on
the server and a `sub` removed from the config loses access with the next restart. Logout only ends the KNote
login, the Pocket ID session stays. Without login, a GET to an admin page redirects to the login, any other method
gets a `401` page.

**Markdown.** commonmark with tables, strikethrough and task lists. Raw HTML is escaped. Links and images allow
only `http`, `https` and `mailto` (also relative URLs), everything else becomes an empty URL. The editor is a plain
text field for the raw Markdown. Its second button "Vorschau" posts the form to the same URL and gets it back with
the rendered Markdown above it. Nothing is validated or saved, only the size is checked. A sheet page always shows
the rendered Markdown.

**Editor fields after a round trip.** After a preview or a validation error the form comes back with every value,
the password fields included. With empty password fields the next click on "Anlegen" would otherwise create the
sheet without protection.

**Client IP and rate limits.** Behind Zoraxy every connection comes from Zoraxy. Zoraxy appends the address it saw
as the last `X-Forwarded-For` entry, everything before it comes from the client. KNote therefore takes the last
entry, and only if the connection comes from `trustedProxyIp`. Every other peer is the client itself and its
header is ignored. Ktor's `XForwardedHeaders` plugin is not used, it takes the first entry. The first request of
every other peer is logged once on WARN as `Request from untrusted peer <ip>`. Excess requests get `429`.

**Security headers** on every response:

| Header                    | Value                                                                                                                       |
|---------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| `Content-Security-Policy` | `default-src 'none'`, `style-src 'self'`, `img-src 'self' https:`, `form-action 'self'`, `frame-ancestors 'none'`, `base-uri 'none'` |
| `X-Content-Type-Options`  | `nosniff`                                                                                                                   |
| `Referrer-Policy`         | `same-origin`                                                                                                               |
| `X-Robots-Tag`            | `noindex, nofollow`                                                                                                         |

Admin pages and unlocked sheets additionally carry `Cache-Control: no-store`. External images in Markdown are
allowed, their server then sees who views the sheet. `Referrer-Policy` is `same-origin` and not `no-referrer`:
with `no-referrer` browsers send `Origin: null` on every form post, and the CSRF check would reject all admin forms.

**CSRF.** Every POST below `/_admin` and to `/_auth/logout` must carry `Origin` (or, without it, `Referer`) equal to
`publicBaseUrl`, otherwise it gets `403`. This check runs before the login check. Together with `SameSite=Lax`
and `form-action 'self'` this covers cross site forms.

**Request size.** The body of a request is limited to three times `maxSheetBytes` plus 64 KiB, because a form post
percent encodes every byte outside ASCII letters and digits. The Markdown itself is checked against
`maxSheetBytes` after decoding.

**Logging.** stdout and stderr through Klogger, collected by journald. Logged: start (version, address, number of
sheets), admin login (`sub`), denied login (`sub`), created, changed and deleted sheets (id, path, protected),
wrong sheet passwords (path, client IP), damaged sheets (id), rejected cross site posts. Never logged: passwords,
Markdown, cookies, the client secret, the session key.

## Deployment

The example targets an Incus LXC container on a VPS behind Zoraxy. [knote.service](knote.service) is the systemd
unit.

1. Build the fat JAR: `./gradlew buildFatJar`, result `build/libs/knote.jar`.
2. Create the container and install Java:

   ```bash
   incus launch images:debian/13 knote
   incus exec knote -- bash
   apt update && apt install -y wget gpg
   wget -O - https://apt.corretto.aws/corretto.key | gpg --dearmor -o /usr/share/keyrings/corretto-keyring.gpg
   echo "deb [signed-by=/usr/share/keyrings/corretto-keyring.gpg] https://apt.corretto.aws stable main" > /etc/apt/sources.list.d/corretto.list
   apt update && apt install -y java-25-amazon-corretto-jdk
   useradd --system --create-home --home-dir /home/knote --shell /usr/sbin/nologin knote
   install -d -o knote -g knote -m 700 /home/knote/data
   ```

   Check the repository lines against the [Corretto install guide](https://docs.aws.amazon.com/corretto/latest/corretto-25-ug/generic-linux-install.html).
3. Copy the files from the host and protect the config. It holds the client secret and the session key:

   ```bash
   incus file push build/libs/knote.jar knote/home/knote/knote.jar
   incus file push config.json knote/home/knote/config.json
   incus file push knote.service knote/etc/systemd/system/knote.service
   incus exec knote -- chown knote:knote /home/knote/knote.jar /home/knote/config.json
   incus exec knote -- chmod 600 /home/knote/config.json
   ```

4. In `config.json`, set `listenHost` to the container address from `incus list knote -c n4` (or `"0.0.0.0"`).
   With `127.0.0.1`, Zoraxy cannot reach KNote. Set `sessionSignKey` to the output of `java -jar knote.jar key`.
5. In Pocket ID, create an OIDC client: callback URL `https://<domain>/_auth/callback`, not public (with secret),
   PKCE off. Enter client ID and secret in `config.json`. The endpoints of your instance:

   ```bash
   curl -s https://id.example.org/.well-known/openid-configuration | jq '{authorization_endpoint, token_endpoint, userinfo_endpoint}'
   ```

6. In Zoraxy, add a proxy rule from the domain to `<container address>:8080`. Set `trustedProxyIp` to the address
   Zoraxy connects from: the container address of Zoraxy, or the host address on `incusbr0` if Zoraxy runs on the
   host (`ip -4 addr show incusbr0`).
7. Check and start:

   ```bash
   incus exec knote -- runuser -u knote -- /usr/lib/jvm/java-25-amazon-corretto/bin/java -jar /home/knote/knote.jar config=/home/knote/config.json check
   incus exec knote -- systemctl daemon-reload
   incus exec knote -- systemctl enable --now knote
   ```

8. Find your own `sub`: keep `allowedSubjects` at `["REPLACE_ME"]`, log in once and read the line
   `Login denied for sub=<value>` with `journalctl -u knote`. Put the value into `allowedSubjects` and run
   `systemctl restart knote`.

systemd restarts KNote after a failed start (exit code 1), at most 5 times in 5 minutes. It does not restart after a
wrong command line (64), broken sheet files (65) or a rejected config (78).

**Acceptance checks** after the deployment, with a protected sheet `test/geschuetzt`:

| Check                                   | Command                                                                                                                         | Expected                                   |
|-----------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------|
| Headers and 404                         | `curl -sI https://<domain>/gibt-es-nicht`                                                                                        | `404` with the four security headers       |
| Rate limit                              | `seq 1 7 \| xargs -I{} curl -s -o /dev/null -w "%{http_code}\n" -d password=falsch https://<domain>/test/geschuetzt`            | five times `403`, then `429`               |
| Forged `X-Forwarded-For`                | the same with `-H "X-Forwarded-For: 1.2.3.{}"`                                                                                   | still `429` within the same minute         |
| Admin without login                     | `curl -s -o /dev/null -w "%{http_code} %{redirect_url}\n" https://<domain>/_admin`                                              | `302` to `/_auth/login`                    |
| Admin POST from a foreign origin        | `curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Origin: https://evil.example" https://<domain>/_admin/new`                 | `403`                                      |
| Proxy address correct                   | `journalctl -u knote --no-pager \| grep -i "untrusted peer"`                                                                    | empty. The Zoraxy address here means `trustedProxyIp` is wrong |

**Backup and update.** Sheets are plain files in `/home/knote/data/sheets`. Protected sheets stay encrypted in every
backup. Before an update, take a snapshot (`incus snapshot create knote before-update`), then push the new JAR and
run `systemctl restart knote`.

## Not in scope (deliberately)

No access by curl (`/raw`, `PUT`), no images or attachments, no expiry date, no session after unlocking a sheet, no
users with their own sheets (`createdBy` is already stored), no PKCE, no logout at Pocket ID, no JavaScript, no
database, no TLS in the app.
