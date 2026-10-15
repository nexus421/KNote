# Security review

An external audit checked commit `8d022e5` (release `v1.2.0`) on 14.10.2026, partly in the source code, partly
against the running JAR. Every finding was then verified in the code and decided on its own on 15.10.2026. This page
lists what was fixed, what was left open on purpose and why, and what to do when updating from `v1.2.0`.

The audit found no critical issue. According to it, CSRF, login CSRF, XSS, path traversal, forged
`X-Forwarded-For` headers, the unlock proof, the file permissions and the Markdown parser held in its tests. CSRF,
the state check and the path handling are also covered by the test suite.

## Fixed

| Id | Finding                                                                 | Change                                                                                                                       | Commit    |
|----|-------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|-----------|
| M1 | Without `allowedSubjects` (the default) every account of the OIDC provider was admin | Fail closed: an empty list stops the start with exit code 78. Letting every account in needs `allowAnyAccount: true` | `84cdab2` |
| M2 | Rate limits keyed by the full IPv6 address, one /64 gave unlimited attempts | IPv6 clients share one limit per /64, IPv4-mapped addresses count as IPv4                                              | `160b4d1` |
| N1 | kotlinx.html writes `\&` as `&`: saving changed content and titles, a password with `\&` broke editing and the download, and a copy got a different password | Every user value goes through `keepBackslashAmp` | `2a08402` |
| N2 | Cookies without prefix, another service on a subdomain could set them   | `__Host-knote_admin` and `__Secure-knote_oauth_state`                                                                        | `fc34e1c` |
| N4 | Paths of unprotected sheets can be guessed, easy to misunderstand        | The editor, the README and [how-it-works.md](how-it-works.md) say that an unprotected sheet is public                       | `23d4c30` |
| N6 | The sheet password stood in the HTML of an unlocked sheet (download form) | The download link carries the Markdown itself as `data:` URL, the page holds no password                                    | `aa94e44` |
| N8 | `data` and `data/sheets` were created with 755                           | Created with 700, existing directories keep their permissions                                                                | `22c9777` |
| N9 | The own Maven repository was asked for every dependency                  | `exclusiveContent`: `bayern.kickner` only from there, and that repository only for `bayern.kickner`                         | `aa15e4f` |
| N9 | GitHub Actions referenced by movable tags                                | Pinned to commits, Dependabot proposes updates once a month in one pull request                                              | `d04544d` |

Related, without an audit finding: the session key moved from the config to `<dataDir>/session.key`, created on the
first start with 600 (`18df891`). Nobody has to create or copy it, and deleting it logs every admin out.

## Left open on purpose

| Id | Finding                                                                 | Reason                                                                                                                       |
|----|-------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| M3 | Unbounded queue in front of the PBKDF2 gate: a flood of wrong passwords delays every unlock (26 s with 40 requests in the audit) | Only availability, no data is exposed. A bounded queue would answer `503` instead, protected sheets stay unusable during such an attack either way. Not worth it for a small private service |
| N2 | An admin session cannot be revoked on the server, logout only deletes the cookie in the browser | The cookie is HttpOnly and the CSP allows no scripts. Emergency brakes exist: remove the `sub` from `allowedSubjects` and restart, which takes effect at once, or delete `session.key` and restart |
| N3 | A save that runs while the same sheet is disabled in a second tab enables it again | Needs one admin doing both within less than a second. A protected sheet stays encrypted                                    |
| N4 | Random default path for new sheets                                       | Would replace readable paths by random ones. A path that should not be guessed can already be chosen freely                                    |
| N5 | No PKCE                                                                  | Ktor 3.6.0 offers no PKCE, only the hooks to add it. Low risk with a confidential client and the state cookie. Waits for Ktor ([KTOR-5001](https://youtrack.jetbrains.com/issue/KTOR-5001), open since 2022). Until then PKCE stays off for the KNote client in Pocket ID, which would otherwise reject every login |
| N7 | `tokenUrl` and `userinfoUrl` accept `http://` to any host               | Only a configuration mistake causes harm. Use `https://`, or `http://` only inside a private network                       |
| N8 | `knote.service` without systemd sandboxing                              | Hardening a service is the operator's job and depends on the setup. Some options fail in unprivileged containers           |
| N9 | Gradle wrapper without checksum, releases without build provenance       | The wrapper downloads Gradle over HTTPS from gradle.org. Provenance only helps whoever verifies it                         |
| I1 | No HSTS, no `Cache-Control` on sheet pages                               | HSTS belongs to the reverse proxy, which terminates TLS. Admin variants of sheet pages already send `no-store`             |

## Updating from v1.2.0

Three things change, the first two need an edit of `config.json` before the first start:

1. **Who is admin.** Without `oidc.allowedSubjects`, KNote now refuses to start (exit code 78). List the `sub` values of
   the admins, or set `"allowAnyAccount": true` if the client is restricted to a group at the provider. See
   [Deployment](deployment.md#5-who-may-log-in).
2. **Session key.** Remove the line `sessionSignKey`, KNote refuses to start until then (exit code 78). The first start
   creates `<dataDir>/session.key`. The command `java -jar knote.jar key` is gone.
3. **One new login.** The new cookie names and the new key log every admin out once.

`java -jar knote.jar config=config.json check` reports 1 and 2 before the restart, the session key first.

## Recommendations for operators

* Restrict the KNote client to a user group at the OIDC provider, also when `allowedSubjects` is set.
* Enable HSTS in the reverse proxy. In Zoraxy: edit the proxy rule, Headers, tab Security Headers, "Enable HSTS".
* Keep `dataDir` private and include it in backups, `session.key` signs admin logins.
* Point `tokenUrl` and `userinfoUrl` to `https://`, or to `http://` only inside a private network.
