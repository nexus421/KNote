# KNote

[![Tests](https://github.com/nexus421/KNote/actions/workflows/test.yml/badge.svg)](https://github.com/nexus421/KNote/actions/workflows/test.yml)
![Kotlin](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fraw.githubusercontent.com%2Fnexus421%2FKNote%2Fmaster%2Fbuild.gradle.kts&search=kotlin%5C%28%22jvm%22%5C%29%20version%20%22%28%5B%5E%22%5D%2B%29%22&replace=%241&label=Kotlin&logo=kotlin&logoColor=white&color=7F52FF)
![Ktor](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fraw.githubusercontent.com%2Fnexus421%2FKNote%2Fmaster%2Fbuild.gradle.kts&search=id%5C%28%22io%5C.ktor%5C.plugin%22%5C%29%20version%20%22%28%5B%5E%22%5D%2B%29%22&replace=%241&label=Ktor&logo=ktor&logoColor=white&color=087CFA)
![JDK](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fraw.githubusercontent.com%2Fnexus421%2FKNote%2Fmaster%2Fbuild.gradle.kts&search=JavaLanguageVersion%5C.of%5C%28%28%5Cd%2B%29%5C%29&replace=%241%20%28Corretto%29&label=JDK&logo=openjdk&logoColor=white&color=ED8B00)

**K**otlin **Note**s: a small self hosted note service. Every note (a sheet) lives at its own URL and is readable
without login. A sheet can have its own password, then it is stored encrypted. Managing sheets needs an admin login
through OIDC.

## Features

* Markdown with tables, strikethrough and task lists, preview in the editor
* Any path per sheet, e.g. `https://notes.example.org/projects/knote`
* Optional password per sheet, content encrypted with AES-GCM
* Admin login through an OIDC provider such as Pocket ID, several admin accounts possible
* Rate limits on password attempts and login
* No database, no JavaScript: one JSON file per sheet, one config file, one JAR

## Getting started

Requirements: Java 25 (Amazon Corretto recommended), an OIDC provider and a reverse proxy for TLS (e.g. Zoraxy,
Caddy or nginx).

```bash
./gradlew buildFatJar                      # build/libs/knote.jar
cp config.example.json config.json
java -jar build/libs/knote.jar key         # value for sessionSignKey
java -jar build/libs/knote.jar check       # validates config.json
java -jar build/libs/knote.jar
```

Fill in `config.json` before the check: your public URL, the session key, the OIDC client and the address of your
reverse proxy. For a local try without TLS, `http://localhost:8080` works as public URL.

Tests: `./gradlew test`, also run on every push.

## Documentation

* [Configuration and command line](docs/configuration.md)
* [Deployment](docs/deployment.md): systemd, reverse proxy, OIDC client, first login
* [How it works](docs/how-it-works.md): endpoints, encryption, login, security
