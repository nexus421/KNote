# How it works

## Endpoints

| Method | Path                       | Access   | Rate limit | Response                                                                       |
|--------|----------------------------|----------|------------|--------------------------------------------------------------------------------|
| GET    | `/`                        | everyone |            | Start page with login button. A logged in admin is redirected to `/_admin`.    |
| GET    | `/_static/...`             | everyone |            | Stylesheet, logo and favicon.                                                  |
| GET    | `/_health`                 | everyone |            | `ok` as long as the process runs, for monitoring.                              |
| GET    | `/_auth/login`             | everyone | `login`    | `302` to the OIDC provider, sets the state cookie.                             |
| GET    | `/_auth/callback`          | everyone | `login`    | `302` to `/_admin` with the admin cookie, or `403`/`502` with a message.       |
| POST   | `/_auth/logout`            | everyone |            | Deletes the admin cookie, `303` to `/`.                                        |
| GET    | `/_admin`                  | admin    |            | Overview of all sheets, sorted by path. `?q=` filters by path and title.      |
| GET    | `/_admin/new`              | admin    |            | Form for a new sheet.                                                          |
| POST   | `/_admin/new`              | admin    |            | Creates the sheet, `303` to `/_admin`. `400` with the form on invalid input. With `action=preview`: the form again with the preview. |
| GET    | `/_admin/edit/{id}`        | admin    |            | Editor, or the password form for a protected sheet.                            |
| POST   | `/_admin/edit/{id}/unlock` | admin    | `unlock`   | Editor with the decrypted content.                                             |
| POST   | `/_admin/edit/{id}`        | admin    |            | Saves, `303` to `/_admin`. `409` if the sheet changed meanwhile. With `action=preview`: the form again with the preview. |
| GET    | `/_admin/delete/{id}`      | admin    |            | Confirmation page.                                                             |
| POST   | `/_admin/delete/{id}`      | admin    |            | Deletes the sheet, `303` to `/_admin`.                                         |
| GET    | `/{path}`                  | everyone |            | The rendered sheet, or the password form. `404` for unknown and invalid paths alike. |
| POST   | `/{path}`                  | everyone | `unlock`   | Field `password`. The rendered sheet (`200`), or the form with `403`.          |
| GET    | `/{path}.md`               | everyone |            | The raw Markdown as a file. For a protected sheet the password form.           |
| POST   | `/{path}.md`               | everyone | `unlock`   | Field `password`. The raw Markdown of a protected sheet as a file.            |

Admin pages address a sheet by its id, so sheet paths containing slashes never collide with `/edit` or `/delete`.
System paths start with `_`, which a sheet path can never contain. A sheet path consists of segments of `a` to `z`,
`0` to `9` and the hyphen, separated by single slashes, at most 128 characters. Titles are 1 to 100 characters
without control characters. The pages are English or German, see `language` in [configuration.md](configuration.md).

## Storage

One JSON file per sheet in `<dataDir>/sheets`, named after the random id of the sheet. The path is a field inside
the file, so no URL ever becomes a file name. All sheets are kept in memory and found only through that map. A
change is written to a temp file in the same directory and moved over the old file atomically, and only then
applied in memory. At startup every file is checked: unknown keys, a file name that does not match the id, an
invalid path and a path used twice are errors (exit code 65). Leftover `*.tmp` files are ignored.

## Protected sheets

The Markdown is encrypted with KotNexLib's `AES.GCM.encryptWithPassword` (PBKDF2 with HMAC SHA256 and 600000
iterations, fresh salt and nonce per save, no compression). Path and title stay plain text, so the overview works
without passwords. Opening a protected sheet shows a password form. The POST answers with the rendered sheet
directly, there is no session: a reload asks for the password again. Editing a protected sheet needs its password
too. The unlock is rate limited like the public one. The editor keeps the password in a hidden field together
with a proof from the unlock (HMAC with `sessionSignKey` over sheet id, version and password). Every save checks that
proof before encrypting again, so a save never tells whether a guessed password is right. A forgotten password means
the content is lost. The admin can still delete the sheet.

All PBKDF2 derivations run off the request threads and at most `cryptoParallelism` at once, so requests from many
IPs cannot occupy every core.

Every sheet page shows when the sheet was last changed and offers its Markdown as a file. Printing a sheet uses a
print layout: black on white, without header, footer and background.

## Editor

A plain text field for the raw Markdown. The second button "Preview" posts the form to the same URL and gets it
back with the rendered Markdown above it. Nothing is validated or saved, only the size is checked. After a preview
or a validation error the form comes back with every value, the password fields included. With empty password
fields the next click on "Create" would otherwise create the sheet without protection.

## Markdown

commonmark with tables, strikethrough and task lists. Raw HTML is escaped. Links and images allow only `http`,
`https` and `mailto` (also relative URLs), everything else becomes an empty URL.

## Admin login

OIDC authorization code flow as confidential client (`client_secret_post`, token request as POST, scope `openid`).
The OAuth state is bound to the browser through the cookie `knote_oauth_state` (path `/_auth/callback`, 10
minutes, compared in constant time). KNote reads `sub` from the userinfo endpoint and accepts every account, or with a
non empty `allowedSubjects` only the listed ones. The admin cookie `knote_admin` is signed with HMAC SHA256, HttpOnly, Secure and
`SameSite=Lax`. Its expiry and the `sub` are checked on every request. Logout only ends the KNote login, the
session at the provider stays. Without login, a GET to an admin page redirects to the login, any other method gets
a `401` page.

## Client IP and rate limits

Behind a reverse proxy every connection comes from the proxy. The proxy appends the address it saw as the last
`X-Forwarded-For` entry, everything before it comes from the client. KNote therefore takes the last entry, and only
if the connection comes from `trustedProxyIp`. Every other peer is the client itself and its header is ignored.
The first request of every other peer is logged once as `Request from untrusted peer <ip>`. Excess requests get
`429`.

## Security headers

On every response:

| Header                    | Value                                                                                                                       |
|---------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| `Content-Security-Policy` | `default-src 'none'`, `style-src 'self'`, `img-src 'self' https:`, `form-action 'self'`, `frame-ancestors 'none'`, `base-uri 'none'` |
| `X-Content-Type-Options`  | `nosniff`                                                                                                                   |
| `Referrer-Policy`         | `same-origin`                                                                                                               |
| `X-Robots-Tag`            | `noindex, nofollow`                                                                                                         |

Admin pages and unlocked sheets additionally carry `Cache-Control: no-store`. External images in Markdown are
allowed, their server then sees who views the sheet. `Referrer-Policy` is `same-origin` and not `no-referrer`: with
`no-referrer` browsers send `Origin: null` on every form post, and the CSRF check would reject all admin forms.

## CSRF

Every POST below `/_admin` and to `/_auth/logout` must carry `Origin` (or, without it, `Referer`) equal to
`publicBaseUrl`, otherwise it gets `403`. This check runs before the login check.

## Request size

The body of a request is limited to three times `maxSheetBytes` plus 64 KiB, because a form post percent encodes
every byte outside ASCII letters and digits. The Markdown itself is checked against `maxSheetBytes` after decoding.

## Logging

stdout and stderr, collected by journald under systemd. Logged: start, admin login and denied login (`sub`),
created, changed and deleted sheets (id, path, protected), wrong sheet passwords (path, client IP), damaged sheets
(id), rejected cross site posts. Never logged: passwords, Markdown, cookies, the client secret, the session key.
