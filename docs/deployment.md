# Deployment

KNote is one fat JAR that speaks plain HTTP. TLS is the job of a reverse proxy in front of it (Zoraxy, Caddy,
nginx or any other). The steps below assume Linux with systemd.

## 1. Get the JAR and copy

Every [release](https://github.com/nexus421/KNote/releases) has `knote.jar` attached:

```bash
curl -LO https://github.com/nexus421/KNote/releases/latest/download/knote.jar
```

To build it yourself instead:

```bash
./gradlew buildFatJar
```

Gradle itself runs on Corretto 25 as set in [gradle-daemon-jvm.properties](../gradle/gradle-daemon-jvm.properties),
so a newer default JDK on the build machine does not matter and IntelliJ follows the same setting. The JAR then is
`build/libs/knote.jar`.

Copy `knote.jar`, your `config.json` and [knote.service](../knote.service) to the server. The JAR needs a
Java 25 runtime, Amazon Corretto is recommended. Run KNote as its own unprivileged user and protect the config, it
holds the client secret and the session key:

```bash
useradd --system --create-home --home-dir /home/knote --shell /usr/sbin/nologin knote
install -d -o knote -g knote -m 700 /home/knote/data
chown knote:knote /home/knote/knote.jar /home/knote/config.json
chmod 600 /home/knote/config.json
```

## 2. OIDC provider

Create a confidential OIDC client (with secret, PKCE off) at your provider, e.g. Pocket ID. Callback URL:
`<publicBaseUrl>/_auth/callback`. Enter client ID, client secret and the three endpoints in `config.json`. The
provider lists its endpoints at `/.well-known/openid-configuration`.

## 3. Reverse proxy

Point a proxy rule for your domain to `<listenHost>:<listenPort>` of KNote. If the proxy runs on another machine or
container, set `listenHost` to an address it can reach (or `"0.0.0.0"`).

KNote keys its rate limits by client IP. The proxy must append the client address as the last entry of
`X-Forwarded-For`, which Zoraxy, nginx (`$proxy_add_x_forwarded_for`) and Caddy do. Set `trustedProxyIp` to the
address the proxy connects from. The easiest way to find it: start KNote, open the site once through the proxy and
look for `Request from untrusted peer <ip>` in the log. That IP is the proxy. Put it into `trustedProxyIp` and
restart. The line must not come back.

Do not let the proxy overwrite `X-Forwarded-For` with custom headers. If another proxy or CDN sits in front of this
one, the client IP detection has to be adapted (see [how-it-works.md](how-it-works.md)).

## 4. Start

```bash
sudo -u knote java -jar /home/knote/knote.jar config=/home/knote/config.json check
systemctl daemon-reload
systemctl enable --now knote
```

Adjust the Java path in `knote.service` to your installation. systemd restarts KNote after a failed start (exit
code 1), at most 5 times in 5 minutes, but not after exit codes 64, 65 or 78 (see
[configuration.md](configuration.md)).

On the first start KNote creates the sheet directory with an example sheet at `/example`. Delete it in the admin
area when you no longer need it, it does not come back. `check` changes nothing on disk.

## 5. Who may log in

Only the accounts listed in `oidc.allowedSubjects` are admin. To find your `sub`, log in once with the placeholder
from the example config: the login is denied and the log shows `Login denied for sub=<value>`. Replace the
placeholder with that value and restart KNote.

To let every account of the OIDC provider in, leave `allowedSubjects` out and set `"allowAnyAccount": true`. Do this
only with the KNote client restricted to a user group at the provider (in Pocket ID: the client's allowed user
groups), otherwise every Pocket ID account is admin. Without either setting, KNote refuses to start.

## Checks after the deployment

With a protected sheet `test/locked`:

| Check                            | Command                                                                                                         | Expected                              |
|----------------------------------|-----------------------------------------------------------------------------------------------------------------|---------------------------------------|
| Headers and 404                  | `curl -sI https://<domain>/does-not-exist`                                                                      | `404` with the security headers       |
| Rate limit                       | `seq 1 7 \| xargs -I{} curl -s -o /dev/null -w "%{http_code}\n" -d password=wrong https://<domain>/test/locked` | five times `403`, then `429`          |
| Forged `X-Forwarded-For`         | the same with `-H "X-Forwarded-For: 1.2.3.{}"`                                                                  | still `429` within the same minute    |
| Admin without login              | `curl -s -o /dev/null -w "%{http_code} %{redirect_url}\n" https://<domain>/_admin`                             | `302` to `/_auth/login`               |
| Admin POST from a foreign origin | `curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Origin: https://evil.example" https://<domain>/_admin/new` | `403`                                 |
| Proxy address correct            | `journalctl -u knote --no-pager \| grep -i "untrusted peer"`                                                   | empty                                 |

## Monitoring

`GET /_health` answers `200 ok` without login as long as KNote runs, e.g. for Uptime Kuma.

## Backup and update

Sheets are plain JSON files in `<dataDir>/sheets`, the admin settings are `<dataDir>/settings.json`. Protected
sheets stay encrypted in every backup. To update,
replace the JAR and restart the service.
