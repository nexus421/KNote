# KNote: Konzept v1

Stand: 12.10.2026. Status: Konzept, noch kein Code im Repository.

KNote ist ein kleiner, selbst gehosteter Notizdienst in Kotlin und Ktor. Er speichert beliebig viele **Blätter**. Jedes Blatt hat einen frei wählbaren Pfad und ist unter `https://<domain>/<pfad>` ohne Login erreichbar. Ein Blatt kann optional mit einem eigenen Passwort geschützt werden und wird dann verschlüsselt gespeichert. Anlegen, Bearbeiten, Löschen und die Übersicht erfordern einen Admin-Login per OIDC. Inhalte sind Markdown.

Leitlinie: KISS. Basis und Konventionen: [DemoAiProject](https://github.com/nexus421/DemoAiProject) (Stil, Toolchain, Bibliotheken) und [KNot](https://github.com/nexus421/KNot) (Aufbau eines Ktor-Dienstes).

---

## 0. Wie dieses Konzept geprüft wurde

Jede technische Aussage trägt eine Markierung:

| Markierung | Bedeutung |
| --- | --- |
| **[T]** | Kompiliert und ausgeführt. Der Testcode liegt in `knote-verifikation.zip` (Anhang B). |
| **[Q]** | Gegen den Quellcode der genannten Version geprüft, aber nicht kompiliert. |
| **[S]** | Folgt aus einer Spezifikation (RFC, OIDC). |
| **[!]** | Hier nicht prüfbar. Vor der Umsetzung selbst prüfen, der Prüfbefehl steht dabei. |

Einschränkung der Prüfumgebung: Maven Central, `maven.kickner.bayern` und `services.gradle.org` waren vom Netzwerk aus gesperrt. Deshalb lief **kein Gradle-Build**. Stattdessen wurden kotlinc 2.4.20 (von GitHub) sowie KotNexLib 4.4.1 (nur die AES-Teile), commonmark-java 0.30.1 und kotlinx.serialization 1.11.0 aus den Quellen kompiliert. Ktor ließ sich so nicht bauen, alle Ktor-Aussagen sind daher [Q] gegen den Tag `3.6.0`. Die Laufzeit-Tests liefen auf OpenJDK 21, nicht auf Corretto 25. Die gemessenen Zeiten stammen von einer Maschine mit 2 vCPUs.

---

## 1. Umfang

### 1.1 In v1

1. Blätter anlegen, anzeigen, bearbeiten, umbenennen (Pfad ändern) und löschen.
2. Jedes Blatt hat einen Pfad, einen Titel und Markdown-Inhalt.
3. Jedes Blatt ist ohne Login unter seinem Pfad erreichbar.
4. Ein Passwort pro Blatt ist optional. Ein geschütztes Blatt wird mit diesem Passwort verschlüsselt gespeichert.
5. Übersicht, Anlegen, Bearbeiten und Löschen nur nach Admin-Login per OIDC (Pocket ID).
6. Markdown mit Tabellen, Durchstreichen und Aufgabenlisten.
7. Speicherung im Dateisystem, eine JSON-Datei pro Blatt, keine Datenbank.
8. Rate-Limit über das Ktor-Plugin.
9. Betrieb als Fat-JAR mit systemd in einem Incus-LXC-Container auf dem VPS, TLS über Zoraxy.

### 1.2 Bewusst nicht in v1 (vorgemerkt für v2)

| Thema | Grund für später |
| --- | --- |
| Zugriff per curl (`/raw`, `PUT`) | Von dir zurückgestellt. |
| Bilder und Anhänge | Von dir zurückgestellt. |
| Ablaufdatum für Blätter | Von dir zurückgestellt. |
| Session nach dem Entsperren | KISS. Nach einem Reload ist das Passwort erneut nötig (Entscheidung E12). |
| Mehrere Nutzer mit eigenen Blättern | `createdBy` wird schon gespeichert, siehe Abschnitt 9. |
| PKCE beim Login | v1 nutzt einen vertraulichen Client mit Secret und State-Cookie (E19). |
| Logout auch bei Pocket ID | v1 meldet nur bei KNote ab (E21). |
| JavaScript im Browser | v1 kommt ganz ohne aus (E15). |

---

## 2. Begriffe

| Begriff | Bedeutung |
| --- | --- |
| Blatt | Eine Notiz: `id`, `path`, `title`, Inhalt, Metadaten. |
| Pfad | URL-Teil nach der Domain, z. B. `projekte/knote`. Regeln in Abschnitt 5.3. |
| Geschütztes Blatt | Blatt mit Passwort. Der Inhalt liegt nur verschlüsselt auf der Platte. |
| Admin | Ein per OIDC angemeldeter Nutzer, dessen `sub` in `allowedSubjects` steht. |
| `sub` | Die stabile Nutzer-ID aus dem OIDC-Provider. Bei Pocket ID ist das die User-ID. |

---

## 3. Entscheidungen

Status **B** = von dir in diesem Chat ausdrücklich bestätigt. Status **V** = mein Vorschlag, bitte bestätigen oder ändern. Abschnitt 14 listet alle V-Punkte noch einmal als Fragen.

| Nr. | Entscheidung | Begründung | Status |
| --- | --- | --- | --- |
| E1 | Kotlin/JVM und Ktor, Aufbau wie KNot, Konventionen wie DemoAiProject | Vorgabe | B |
| E2 | Dateisystem statt Datenbank | Vorgabe, Datenmenge klein | B |
| E3 | Eine JSON-Datei pro Blatt, Dateiname ist die UUID, der Pfad steht in der Datei | Die URL wird nie zum Dateinamen, Path Traversal ist ausgeschlossen. Keine Indexdatei, die aus dem Takt geraten kann. | B |
| E4 | Verschlüsselung nur für geschützte Blätter, ausschließlich mit KotNexLib (`AES.GCM`) | Vorgabe | B |
| E5 | Der Pfad ist beim Anlegen frei wählbar, das Blatt ist ohne Login aufrufbar | Vorgabe | B |
| E6 | Anlegen, Bearbeiten, Löschen und Übersicht nur als eingeloggter Admin | Vorgabe | B |
| E7 | v1 kennt genau eine Admin-Rolle. Mehrere OIDC-Accounts können Admin sein. | Vorgabe | B |
| E8 | Betrieb auf dem VPS in einem Incus-LXC-Container hinter Zoraxy | Vorgabe | B |
| E9 | Rate-Limit über `ktor-server-rate-limit` | Vorgabe | B |
| E10 | Markdown mit commonmark-java 0.30.1 plus den GFM-Erweiterungen Tabellen, Durchstreichen, Aufgabenlisten | Escaping und URL-Filter sind eingebaut [T]. Die Erweiterungen hängen nur vom Kern ab [Q]. | V |
| E11 | Ein Admin braucht zum Bearbeiten eines geschützten Blatts dessen Passwort | Zwingende Folge aus E4: Ohne Passwort ist der Inhalt nicht lesbar. | V |
| E12 | Entsperren ohne Session: Der `POST` mit dem Passwort liefert direkt die fertige Seite | Keine Passwörter oder Schlüssel im RAM, keine Cookie-Logik | V |
| E13 | Eigenes Feld `title`, im Klartext gespeichert | Die Übersicht muss Titel auch geschützter Blätter ohne Passwort zeigen. Folge: Titel und Pfad sind nicht vertraulich. | V |
| E14 | HTML mit kotlinx.html über `ktor-server-html-builder` | Text wird automatisch escaped [Q]. Bei Raw-String-Templates wäre jedes vergessene Escaping eine XSS-Lücke. | V |
| E15 | Kein JavaScript im Browser, strenge Content-Security-Policy | Kleinste Angriffsfläche für XSS | V |
| E16 | Die Client-IP wird selbst ermittelt, ohne das `XForwardedHeaders`-Plugin | Das Plugin nimmt standardmäßig den **ersten** Eintrag aus `X-Forwarded-For`, und den setzt der Client selbst [Q]. Abschnitt 8.2. | V |
| E17 | `/` leitet auf `/_admin` weiter | Bequem für dich. Fremde landen beim Pocket-ID-Login. | V |
| E18 | `compress = false` beim Verschlüsseln | Bei kurzen Notizen ist der Speicherwert mit Kompression größer (304 statt 236 Zeichen für das Testblatt) [T]. | V |
| E19 | Login als vertraulicher OIDC-Client mit Client-Secret und State-Cookie, ohne PKCE | Ktor 3.6.0 bietet kein fertiges PKCE [Q]. Der State-Cookie verhindert Login-CSRF, das Secret verhindert, dass ein abgefangener Code ohne Secret eingelöst wird. RFC 9700 empfiehlt PKCE trotzdem auch für vertrauliche Clients [S]. Das ist ein bewusster KISS-Kompromiss für v1, nachrüstbar über `authorizeUrlInterceptor` und `extraTokenParametersProvider` [Q]. | V |
| E20 | Mindestlänge für Blatt-Passwörter: 12 Zeichen (konfigurierbar) | Gegen Online-Raten trotz Rate-Limit, Rechnung in Abschnitt 8.3 | V |
| E21 | Logout nur lokal: Das KNote-Cookie wird gelöscht, die Pocket-ID-Sitzung bleibt bestehen | KISS. Der nächste Login ist dann ein Klick. | V |
| E22 | Beim Bearbeiten eines geschützten Blatts steht das aktuelle Passwort in einem versteckten Formularfeld | So muss es beim Speichern nicht ein zweites Mal eingetippt werden. Die Seite wird mit `no-store` ausgeliefert. | V |
| E23 | Weboberfläche serverseitig gerendert statt Compose | Die Konvention sagt Compose, außer wenn es nicht passt. Ein Blatt muss in jedem Browser ohne Installation aufrufbar sein, dafür ist serverseitiges HTML die einfachste Lösung. | V |

---

## 4. Technik

### 4.1 Toolchain

Wie DemoAiProject und KNot [Q, gelesen aus beiden Repositories]:

- Kotlin JVM Plugin 2.4.20, Serialization Plugin 2.4.20
- Ktor Gradle Plugin 3.6.0 (es setzt die `ktor-bom` als Plattform, daher brauchen Ktor-Abhängigkeiten keine Versionsnummer [Q, `ktor-build-plugins`, `BomFile.kt`])
- Gradle 9.6.0 über den Wrapper
- JVM-Toolchain: Amazon Corretto 25, Vendor fest auf `JvmVendorSpec.AMAZON`
- `BuildConfig` mit `VERSION` und `BUILD_TIME` per Gradle-Task (aus KNot übernehmen)

### 4.2 `build.gradle.kts`

Abgeleitet aus KNots `build.gradle.kts`. Der Task `GenerateBuildConfig` wird unverändert aus KNot übernommen (nur das Paket auf `bayern.kickner.knote` ändern), deshalb steht er hier nicht.

```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("io.ktor.plugin") version "3.6.0"
}

group = "bayern.kickner"
version = "1.0.0"

repositories {
    mavenCentral()
    maven {
        name = "nexus421MavenReleases"
        url = uri("https://maven.kickner.bayern/releases")
    }
}

dependencies {
    implementation("bayern.kickner:Klogger:0.4.0")
    implementation("bayern.kickner:KotNexLib:4.4.1")

    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-cio")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-sessions")
    implementation("io.ktor:ktor-server-rate-limit")
    implementation("io.ktor:ktor-server-csrf")
    implementation("io.ktor:ktor-server-body-limit")
    implementation("io.ktor:ktor-server-html-builder")
    implementation("io.ktor:ktor-client-cio")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("org.commonmark:commonmark:0.30.1")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.1")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.1")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.30.1")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host")
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.AMAZON)
    }
}

application {
    mainClass.set("bayern.kickner.knote.MainKt")
}

ktor {
    fatJar {
        archiveFileName.set("knote.jar")
    }
}

tasks.test {
    useJUnitPlatform()
}
```

### 4.3 Warum jede Abhängigkeit nötig ist

| Artefakt | Wofür | Ohne es |
| --- | --- | --- |
| `Klogger:0.4.0` | Logging. Enthält einen eigenen SLF4J-2-Provider, Ktors Logs landen automatisch in Klogger [Q, Klogger README v0.4.0]. Deshalb **kein** `slf4j-simple` (anders als die ältere Zeile im DemoAiProject-README, KNot macht es schon so). | eigene Logging-Lösung |
| `KotNexLib:4.4.1` | `AES.GCM`, `AESData`, `ArgsInterpreter`, `ResultOf2` | Vorgabe E4 |
| `ktor-server-core`, `-cio` | HTTP-Server auf CIO, wie KNot | |
| `ktor-server-auth` | OAuth2-Codefluss und Session-Authentifizierung | OIDC selbst bauen |
| `ktor-server-sessions` | Signiertes Admin-Cookie | Cookie-Signatur selbst bauen |
| `ktor-server-rate-limit` | Vorgabe E9 | |
| `ktor-server-csrf` | Prüft den `Origin`-Header bei Admin-`POST`s | nur SameSite als Schutz |
| `ktor-server-body-limit` | Begrenzt die Request-Größe, wie in KNot | Speicher füllbar |
| `ktor-server-html-builder` | `respondHtml` und kotlinx.html 0.12.0 (als `api`-Abhängigkeit [Q]) | Escaping von Hand (E14) |
| `ktor-client-cio` | Der OAuth-Provider braucht einen `HttpClient` für Token- und Userinfo-Aufruf [Q, `OAuthProcedure.kt`: `lateinit var client`] | kein Login |
| `kotlinx-serialization-json:1.11.0` | Blatt-Dateien, Config, Session, Userinfo | Vorgabe der Konventionen |
| `commonmark` plus 3 Erweiterungen | Markdown zu HTML (E10) | |

### 4.4 Paketstruktur

Eine Datei oder ein Paket pro Aufgabe unter `bayern.kickner.knote`, `Main.kt` verdrahtet nur:

```
bayern/kickner/knote/
  Main.kt                     Einstieg, Argumente, Exit-Codes, Serverstart
  cli/Commands.kt             key, check
  config/AppConfig.kt         Datenklassen der Config
  config/ConfigLoader.kt      Laden und Validieren
  storage/Sheet.kt            Sheet, SheetContent
  storage/SheetStore.kt       Laden, Speichern, Löschen
  crypto/SheetCrypto.kt       encryptContent, decryptContent, CryptoGate
  markdown/MarkdownRenderer.kt
  web/ClientIp.kt             Client-IP hinter Zoraxy
  web/SecurityHeaders.kt      Header-Plugin
  web/Pages.kt                HTML-Seiten (kotlinx.html)
  web/PublicRoutes.kt         Anzeigen und Entsperren
  web/AdminRoutes.kt          Übersicht, Anlegen, Bearbeiten, Löschen
  auth/Oidc.kt                OAuth-Konfiguration, Userinfo, State-Cookie
  auth/AdminSession.kt        Session-Klasse und Prüfung
resources/static/knote.css    einziges Stylesheet
```

---

## 5. Daten

### 5.1 Datenmodell [T]

```kotlin
/** One sheet as stored on disk, one JSON file per sheet named after [id]. */
@Serializable
data class Sheet(
    val id: String,          // random UUID, also the file name
    val path: String,        // e.g. "projekte/knote"
    val title: String,       // plain text, never secret (E13)
    val content: SheetContent,
    val createdBy: String,   // OIDC sub of the creating admin
    val created: Long,       // Unix millis
    val updated: Long,       // Unix millis, also the optimistic lock
)

/** Content of a sheet. A protected sheet can only exist encrypted. */
@Serializable
sealed interface SheetContent {
    @Serializable @SerialName("plain")
    data class Plain(val markdown: String) : SheetContent

    /** `AES.GCM.encryptWithPassword(markdown, password, compress = false).toString()` */
    @Serializable @SerialName("encrypted")
    data class Encrypted(val aesData: String) : SheetContent
}
```

`@SerialName` legt die Werte des Feldes `type` fest. Ohne die Annotation stünde dort der volle Klassenname, und ein Umbenennen der Klasse würde alle Dateien unlesbar machen.

### 5.2 So sieht eine Datei aus [T, echte Ausgabe des Tests]

`data/sheets/ac26b981-1987-48c4-9b96-1efa9f1876e8.json`:

```json
{
    "id": "ac26b981-1987-48c4-9b96-1efa9f1876e8",
    "path": "einkauf",
    "title": "Einkauf",
    "content": {
        "type": "plain",
        "markdown": "# Einkauf\n- Brot"
    },
    "createdBy": "sub-123",
    "created": 1791799486402,
    "updated": 1791799486402
}
```

Ein geschütztes Blatt unterscheidet sich nur im Inhalt:

```json
    "content": {
        "type": "encrypted",
        "aesData": "R0NNHEVjY282MVRHakdOM2dUOE8cNjAwMDAwHG1KaGd5..."
    },
```

`aesData` enthält Base64-kodiert: Modus `GCM`, Nonce, Iterationen `600000`, Salt, Kompressions-Flag und Chiffretext [Q, `AES.kt` in KotNexLib v4.4.1, `AESData.toString()`].

### 5.3 Regeln für Pfade [T]

- Regex: `^[a-z0-9-]+(/[a-z0-9-]+)*$`
- Höchstens 128 Zeichen.
- Gültig: `einkauf`, `projekte/knote`, `a-b/c-d/e`
- Ungültig (getestet): leer, `/einkauf`, `einkauf/`, `../etc/passwd`, `a//b`, `_admin`, `Einkauf`, `ä`, `a b`, `a/./b`, `a%2fb`
- Systempfade beginnen mit `_` (`/_admin`, `/_auth`, `/_static`). Die Regex erlaubt `_` nicht, ein Blatt kann also nie mit einem Systempfad kollidieren.
- Ein Blatt wird nur über die Map im Speicher gefunden, nie über das Dateisystem. Selbst ein kaputter Pfad aus der URL kann deshalb nur ein vorhandenes Blatt treffen oder gar keins.

### 5.4 Regeln für Titel, Inhalt und Passwort

| Feld | Regel | Fehler im Formular |
| --- | --- | --- |
| Titel | nach `trim()` 1 bis 100 Zeichen, keine Steuerzeichen (`Char.isISOControl()`) | "Titel 1 bis 100 Zeichen" |
| Markdown | höchstens `maxSheetBytes` (Standard 1 MiB) in UTF-8 | "Inhalt zu groß" |
| Passwort | mindestens `minSheetPasswordLength` (Standard 12), höchstens 1024 Zeichen, Wiederholung muss gleich sein | jeweils eigene Meldung |

### 5.5 Speichern und Laden [T]

`SheetStore` hält alle Blätter in einer `ConcurrentHashMap<String, Sheet>` mit dem Pfad als Schlüssel.

**Laden beim Start:**

1. `dataDir/sheets` anlegen, falls nicht vorhanden.
2. Jede `*.json` lesen. Andere Dateien (z. B. übrig gebliebene `*.tmp` nach einem Absturz) werden ignoriert [T].
3. Alle Probleme sammeln, nicht beim ersten abbrechen:
   - Datei nicht parsebar oder mit unbekanntem Schlüssel. Unbekannte Schlüssel sind Fehler, weil `Json` standardmäßig `ignoreUnknownKeys = false` hat [T: "Encountered an unknown key 'extra'"].
   - Dateiname passt nicht zur `id` [T].
   - Ungültiger Pfad [T].
   - Ein Pfad kommt in mehr als einer Datei vor [T].
4. Gibt es Probleme: alle loggen und mit Exit-Code 65 beenden (Abschnitt 10). Sonst die Map füllen.

**Speichern (`save`), `@Synchronized`:**

1. Ist der Pfad schon von einem **anderen** Blatt belegt, abbrechen mit "Pfad bereits vergeben" [T].
2. JSON in eine Temp-Datei **im selben Verzeichnis** schreiben (`Files.createTempFile(dir, id, ".tmp")`).
3. `Files.move(tmp, ziel, ATOMIC_MOVE, REPLACE_EXISTING)` [T]. Nur im selben Dateisystem ist der Move atomar, deshalb liegt die Temp-Datei im Zielverzeichnis.
4. Erst nach erfolgreichem Schreiben die Map ändern. Bei einer Umbenennung wird der alte Pfad entfernt [T].

**Löschen (`delete`), `@Synchronized`:** Datei löschen, dann den Eintrag aus der Map entfernen [T].

Der vollständige getestete Code steht in `knote-verifikation.zip`, Datei `proto/SheetStore.kt`.

### 5.6 Verzeichnis auf dem Server

```
/home/knote/
  knote.jar
  config.json          Rechte 600, Besitzer knote
  data/sheets/*.json   Rechte 700 für data/, Besitzer knote
```

---

## 6. Verschlüsselung

### 6.1 API aus KotNexLib 4.4.1 [Q und T]

- `AES.GCM` trägt `@ExperimentalKotNexLibAPI`. Jede Datei, die `AES.GCM` nutzt, braucht `@file:OptIn(kotnexlib.ExperimentalKotNexLibAPI::class)` [Q].
- `AES.GCM.encryptWithPassword(text: String, password: String, salt = 16 Zufallsbytes, compress = false): AESData` [Q]. Pro Aufruf neues Salt und neue Nonce [T: zwei Verschlüsselungen desselben Texts ergeben verschiedene Werte].
- Schlüsselableitung: PBKDF2-HMAC-SHA256 mit fest 600000 Iterationen, 256-Bit-Schlüssel [Q].
- `AES.AESData.restore(s: String): Result<AESData>`. **Achtung:** liefert ein `Result`, nicht direkt `AESData` [Q]. Meine frühere Chat-Zeile `restore(s).decryptAsString(pw)` war falsch. Richtig ist die Verkettung unten.
- `AESData.decryptAsString(password): Result<String>`. Ein falsches Passwort ergibt `Result.failure` mit `javax.crypto.AEADBadTagException` [T].
- Kaputte Eingabe für `restore` ergibt `Result.failure`, keine Exception nach außen [T].
- Leerer Text funktioniert [T].

### 6.2 Kapselung in KNote [T]

```kotlin
@file:OptIn(kotnexlib.ExperimentalKotNexLibAPI::class)

/** Encrypts [markdown] with [password]. Costs one PBKDF2 derivation. */
fun encryptContent(markdown: String, password: String): SheetContent.Encrypted =
    SheetContent.Encrypted(AES.GCM.encryptWithPassword(markdown, password, compress = false).toString())

/** Decrypts [content]. A wrong password is an expected outcome, not an exception. */
fun decryptContent(content: SheetContent, password: String): ResultOf2<String, UnlockError> = when (content) {
    is SheetContent.Plain -> ResultOf2.Failure(UnlockError.NotProtected)
    is SheetContent.Encrypted -> {
        val data = AES.AESData.restore(content.aesData)
        if (data.isFailure) ResultOf2.Failure(UnlockError.Corrupt("stored data cannot be restored"))
        else {
            val plain = data.getOrThrow().decryptAsString(password)
            if (plain.isSuccess) ResultOf2.Success(plain.getOrThrow())
            else if (plain.exceptionOrNull() is AEADBadTagException) ResultOf2.Failure(UnlockError.WrongPassword)
            else ResultOf2.Failure(UnlockError.Corrupt(plain.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"))
        }
    }
}

sealed interface UnlockError {
    data object WrongPassword : UnlockError
    data object NotProtected : UnlockError
    data class Corrupt(val reason: String) : UnlockError
}
```

Hinweis zum Stil: Das verschachtelte `if` ist der getestete Stand. In der Umsetzung als `when`-Ausdruck schreiben, das passt besser zur Konvention.

### 6.3 Kosten und Begrenzung [T]

Gemessen (2 vCPUs, OpenJDK 21): Verschlüsseln 0,67 bis 1,04 s, Entschlüsseln 0,60 bis 0,66 s. Fast die ganze Zeit ist PBKDF2.

Daraus folgen zwei Regeln:

1. **Nie im Event-Loop rechnen.** Jede Ver- und Entschlüsselung läuft in `withContext(Dispatchers.Default)`.
2. **Parallelität begrenzen.** Ein globales `Semaphore(cryptoParallelism)` (Standard 1) lässt nur eine Ableitung gleichzeitig zu. Sonst könnte jemand mit vielen IPs die CPU auslasten. Getestet: 4 gleichzeitige Versuche bei 1 Permit liefen nacheinander, maximal 1 parallel, Gesamtdauer 2,6 s [T].

```kotlin
/** Runs at most [parallelism] PBKDF2 derivations at once, off the request threads. */
class CryptoGate(parallelism: Int) {
    private val semaphore = Semaphore(parallelism)

    suspend fun <T> run(block: () -> T): T =
        semaphore.withPermit { withContext(Dispatchers.Default) { block() } }
}
```

### 6.4 Was die Verschlüsselung schützt und was nicht

| Schützt | Schützt nicht |
| --- | --- |
| Inhalt geschützter Blätter in Backups, Snapshots, beim Hoster und bei Shell-Zugriff | Titel und Pfad (E13) |
| Inhalt bei Diebstahl der JSON-Dateien | Löschen: Der Admin kann geschützte Blätter ohne Passwort löschen |
| | Ein kompromittierter laufender Server sieht Passwörter beim Entsperren |
| | Vergessenes Passwort: Der Inhalt ist verloren, es gibt keine Wiederherstellung |

---

## 7. HTTP-Schnittstelle

### 7.1 Routen

| Methode | Pfad | Zugriff | Rate-Limit | Antwort |
| --- | --- | --- | --- | --- |
| GET | `/` | alle | nein | `302` auf `/_admin` (E17) |
| GET | `/_static/knote.css` | alle | nein | Stylesheet aus `resources/static` (`staticResources("/_static", "static")` [Q]) |
| GET | `/_auth/login` | alle | `login` | `302` zu Pocket ID, setzt State-Cookie |
| GET | `/_auth/callback` | alle | `login` | Abschnitt 7.4 |
| POST | `/_auth/logout` | Admin | nein | Cookie löschen, `303` auf `/` |
| GET | `/_admin` | Admin | nein | Übersicht |
| GET | `/_admin/new` | Admin | nein | Formular "Neues Blatt" |
| POST | `/_admin/new` | Admin | nein | Anlegen, `303` auf `/_admin` |
| GET | `/_admin/edit/{id}` | Admin | nein | ungeschützt: Editor. Geschützt: Passwortformular |
| POST | `/_admin/edit/{id}/unlock` | Admin | `unlock` | Editor mit entschlüsseltem Inhalt |
| POST | `/_admin/edit/{id}` | Admin | nein | Speichern, `303` auf `/_admin` |
| GET | `/_admin/delete/{id}` | Admin | nein | Bestätigungsseite |
| POST | `/_admin/delete/{id}` | Admin | nein | Löschen, `303` auf `/_admin` |
| GET | `/{path...}` | alle | nein | Abschnitt 7.2 |
| POST | `/{path...}` | alle | `unlock` | Abschnitt 7.3 |

Routing-Priorität [Q, `RouteSelector.kt`]: feste Segmente haben Qualität 1.0, der Tailcard `{path...}` 0.1. `/_admin` gewinnt also immer. Der Tailcard passt auch auf null Segmente. Deshalb behandelt der Handler einen leeren Pfad selbst, oder `/` bekommt eine eigene Route (oben so vorgesehen).

Pfad aus dem Tailcard: `call.parameters.getAll("path").orEmpty().joinToString("/")`.

Admin-Seiten tragen `{id}` (UUID) statt des Pfads, damit sich Blatt-Pfade mit Schrägstrichen nicht mit `/edit` und `/delete` überschneiden.

### 7.2 Blatt anzeigen (`GET /{path...}`)

1. Pfad aus dem Tailcard zusammensetzen.
2. Pfad ungültig oder kein Blatt vorhanden: `404` mit der Seite "Nicht gefunden". Beide Fälle sehen gleich aus.
3. `Plain`: Markdown rendern (Abschnitt 7.6), `200`.
4. `Encrypted`: Passwortformular mit Titel, `200`. Das Formular sendet `POST` an dieselbe URL mit dem Feld `password`.

### 7.3 Blatt entsperren (`POST /{path...}`)

1. Rate-Limit `unlock` (Abschnitt 8.3). Überschritten: `429` [Q, `RateLimitInterceptors.kt`: `respond(HttpStatusCode.TooManyRequests)`].
2. Kein Blatt: `404`. `Plain`: `303` auf dieselbe URL.
3. Feld `password` lesen (`call.receiveParameters()`).
4. `cryptoGate.run { decryptContent(...) }`.
5. Erfolg: gerenderte Seite, `200`, `Cache-Control: no-store`.
6. `WrongPassword`: Passwortformular mit "Falsches Passwort", `403`. Log auf WARN mit Pfad und Client-IP, nie mit Passwort.
7. `Corrupt`: `500` "Blatt beschädigt", Log auf ERROR mit `id`.

Nach einem Reload fragt der Browser, ob er das Formular erneut senden soll. Das ist die gewollte Folge von E12.

### 7.4 Admin-Login per OIDC

Ablauf:

1. `GET /_auth/login` liegt in `authenticate("oidc")`. Ktor erzeugt einen State und ruft `onStateCreated` auf [Q, `OAuthProcedure.kt` Zeile 197]. KNote setzt dabei das Cookie `knote_oauth_state` (Wert = State, `Path=/_auth/callback`, `Max-Age=600`, `HttpOnly`, `Secure`, `SameSite=Lax`). Danach leitet Ktor zu Pocket ID weiter.
2. Pocket ID leitet zurück auf `GET /_auth/callback?code=...&state=...`.
3. Ktor ruft `verifyState` **vor** dem Token-Abruf auf [Q, `OAuth2.kt` Zeile 71]. KNote vergleicht den State mit dem Cookie in konstanter Zeit (`MessageDigest.isEqual`). Ungleich oder fehlend: Login scheitert.
4. Ktor holt das Token per `POST` am Token-Endpoint. `requestMethod = HttpMethod.Post` **muss** gesetzt werden, Ktors Standard ist `GET` [Q, `OAuthCommon.kt` Zeile 84], RFC 6749 Abschnitt 3.2 verlangt `POST` [S].
5. KNote ruft den Userinfo-Endpoint mit `bearerAuth(accessToken)` auf und liest `sub`.
6. `sub` steht in `allowedSubjects`: Session-Cookie setzen, State-Cookie löschen, `302` auf `/_admin`. Log INFO "Admin login sub=...".
7. Sonst: `403` "Kein Zugriff", Log WARN "Login denied for sub=...". Diese Logzeile nutzt du bei der Einrichtung, um deine eigene `sub` zu finden (Abschnitt 11.6).

Warum der State-Cookie nötig ist: Ohne eigenes `verifyState` nutzt Ktor `GenerateOnlyNonceManager`, und dessen `verifyNonce` liefert **immer `true`**. Ktor nennt das selbst "recommended for testing only" [Q, `ktor-utils`, `NonceManager.kt`]. Der Cookie bindet den State an den Browser, der den Login gestartet hat. `SameSite=Lax` statt `Strict`, weil der Rücksprung von Pocket ID eine seitenübergreifende Navigation ist, bei der `Strict` das Cookie nicht mitschicken würde.

Warum Userinfo und nicht das ID-Token: Beim Userinfo-Aufruf prüft Pocket ID das Access-Token. Bei Nutzung des ID-Tokens müsste KNote selbst `iss`, `aud` und `exp` prüfen. Das ist mehr eigener Code für dasselbe Ergebnis.

Konfiguration [Q gegen `OAuthCommon.kt` und `OAuthProcedure.kt` in Ktor 3.6.0]:

```kotlin
install(Authentication) {
    oauth("oidc") {
        client = httpClient
        urlProvider = { "${config.publicBaseUrl}/_auth/callback" }
        settings = OAuthServerSettings.OAuth2ServerSettings(
            name = "pocket-id",
            authorizeUrl = config.oidc.authorizeUrl,
            accessTokenUrl = config.oidc.tokenUrl,
            requestMethod = HttpMethod.Post,
            clientId = config.oidc.clientId,
            clientSecret = config.oidc.clientSecret,
            defaultScopes = listOf("openid"),
            onStateCreated = { call, state -> call.response.cookies.append(stateCookie(state)) },
            verifyState = { call, state -> stateMatches(call.request.cookies[STATE_COOKIE], state) },
        )
    }
    session<AdminSession>("admin") {
        validate { session -> session.takeIf { it.expiresAt > clock() && it.sub in config.oidc.allowedSubjects } }
        challenge("/_auth/login")
    }
}
```

Im Callback: `call.principal<OAuthAccessTokenResponse.OAuth2>()?.accessToken` [Q, `OAuthProcedure.kt` Zeile 223, `Authentication.kt` Zeile 150].

Pocket ID liefert diese Endpunkte [Q, `well_known_controller.go`, Pocket ID HEAD vom 07.10.2026]:

| Zweck | URL |
| --- | --- |
| Authorize | `<AppURL>/authorize` |
| Token | `<InternalAppURL>/api/oidc/token` |
| Userinfo | `<InternalAppURL>/api/oidc/userinfo` |

Pocket ID unterstützt `client_secret_post` [Q]. Das ist Ktors Standard, solange `accessTokenRequiresBasicAuth = false` bleibt [Q].

### 7.5 Admin-Session

```kotlin
/** Admin login. Signed, not encrypted: the content is not secret. */
@Serializable
data class AdminSession(val sub: String, val expiresAt: Long)

install(Sessions) {
    cookie<AdminSession>("knote_admin") {
        cookie.path = "/"
        cookie.httpOnly = true
        cookie.secure = true
        cookie.maxAgeInSeconds = config.sessionHours * 3600L
        cookie.extensions["SameSite"] = "Lax"
        transform(SessionTransportTransformerMessageAuthentication(config.sessionSignKey.hexToByteArray()))
    }
}
```

- Ktors Standard-Serializer für Sessions ist kotlinx.serialization, die Klasse braucht `@Serializable` [Q, `SessionSerializer.kt`].
- `SessionTransportTransformerMessageAuthentication` signiert per HMAC-SHA256 [Q].
- `hexToByteArray()` ist in Kotlin 2.4.20 ohne Opt-in nutzbar [T].
- `expiresAt` wird bei jedem Request geprüft, nicht nur über `Max-Age`. Ein gestohlenes Cookie verfällt damit auch serverseitig.
- `allowedSubjects` wird bei jedem Request geprüft. Wer aus der Config entfernt wird, verliert nach dem Neustart sofort den Zugriff.
- Ktor 3.6.0 prüft beim Setzen eines `Secure`-Cookies nicht, ob die Verbindung HTTPS ist [Q, `ResponseCookies.kt`]. Zwischen Zoraxy und KNote läuft HTTP. Das Cookie funktioniert trotzdem, weil der Browser HTTPS zu Zoraxy spricht.
- Cookie-Pfad `/`: Das Cookie geht auch bei öffentlichen Blättern mit. Das ist harmlos, öffentliche Routen lesen es nicht.

### 7.6 Markdown [T]

```kotlin
private val extensions = listOf(
    TablesExtension.create(),
    StrikethroughExtension.create(),
    TaskListItemsExtension.create(),
)
private val parser = Parser.builder().extensions(extensions).build()
private val renderer = HtmlRenderer.builder()
    .extensions(extensions)
    .escapeHtml(true)
    .sanitizeUrls(true)
    .urlSanitizer(DefaultUrlSanitizer(listOf("http", "https", "mailto")))
    .build()

/** Renders [markdown] to HTML that is safe to embed. */
fun renderMarkdown(markdown: String): String = renderer.render(parser.parse(markdown))
```

Getestete Fälle:

| Eingabe | Ergebnis |
| --- | --- |
| Tabelle, `~~x~~`, `- [ ]`/`- [x]` | `<table>`, `<del>`, Checkboxen |
| `<script>alert(1)</script>` | als Text escaped |
| `<img src=x onerror=alert(1)>` mitten im Text | als Text escaped |
| `[klick](javascript:alert(1))` | `href=""` |
| `![x](data:text/html;base64,...)` | `src=""` |
| `[x](data:text/html;base64,...)` | `href=""` |
| `[intern](/projekte/knote)` | bleibt erhalten |
| `https://example.org/a?b=c&d=e` | `&amp;` korrekt escaped |
| Umlaute und Emoji | bleiben erhalten |

**Wichtiger Fund:** `sanitizeUrls(true)` allein lässt `data:`-URLs durch. Der eingebaute `DefaultUrlSanitizer` erlaubt standardmäßig `http`, `https`, `mailto` **und `data`** [Q und T: der erste Testlauf schlug genau hier fehl]. Deshalb setzt KNote den Sanitizer ausdrücklich mit drei Protokollen.

Eingebettet wird das HTML mit kotlinx.html über `unsafe { +html }` [Q, kotlinx.html 0.12.0, `api.kt` Zeile 111]. Das ist die einzige Stelle mit `unsafe`, alles andere wird escaped.

### 7.7 HTML-Seiten

Alle Seiten mit `call.respondHtml { }` [Q, `RespondHtml.kt`]. Keine Seite enthält JavaScript (E15).

| Seite | Inhalt |
| --- | --- |
| Blatt | `<title>` = Titel, Überschrift = Titel, gerendertes Markdown |
| Passwortformular | Titel, Feld `password` (`type=password`, `autocomplete=current-password`), Button "Entsperren", ggf. Fehlermeldung |
| Übersicht | Tabelle: Pfad (Link), Titel, Schutz ("geschützt" oder leer), Geändert (`dd.MM.yyyy HH:mm:ss z`, `Locale.ENGLISH`, wie die Konvention verlangt), Aktionen "Bearbeiten" und "Löschen". Darüber "Neues Blatt" und "Abmelden". Sortiert nach Pfad. |
| Neues Blatt | Pfad, Titel, Markdown (`<textarea>`), Passwort, Passwort wiederholen (beide leer = ungeschützt) |
| Bearbeiten | Pfad, Titel, Markdown, verstecktes `updated`. Bei geschützten Blättern verstecktes `currentPassword` (E22). Schutz-Auswahl (Radio): "unverändert", "neues Passwort", "Schutz entfernen" bzw. bei ungeschützten "ungeschützt lassen", "Passwort setzen". |
| Löschen | "Blatt `<pfad>` wirklich löschen?" mit Button (POST) |
| Fehler | 403, 404, 409, 500 mit kurzem Text |

Bekannte Eigenheit von kotlinx.html [Q, `stream.kt` Zeile 180]: Die Folge `\&` im Text wird als `&` ohne Backslash ausgegeben. Das ist harmlos (keine Lücke), ein Titel wie `a \& b` erscheint aber als `a & b`.

### 7.8 Bearbeiten im Detail

**Ungeschütztes Blatt:** `GET /_admin/edit/{id}` zeigt den Editor. `POST` speichert.

**Geschütztes Blatt:**

1. `GET /_admin/edit/{id}` zeigt nur ein Passwortfeld.
2. `POST /_admin/edit/{id}/unlock` (Rate-Limit `unlock`) entschlüsselt. Falsch: `403` mit Meldung. Richtig: Editor mit Inhalt und verstecktem `currentPassword`.
3. `POST /_admin/edit/{id}` speichert.

**Speichern (`POST /_admin/edit/{id}`):**

1. Blatt laden. Fehlt es: `404`.
2. Optimistische Sperre: Formularfeld `updated` ungleich `sheet.updated`: `409` "Das Blatt wurde zwischenzeitlich geändert". Das passiert z. B. bei zwei offenen Tabs.
3. Felder prüfen (Abschnitt 5.4). Neuer Pfad belegt: Fehlermeldung im Formular.
4. Inhalt bestimmen:

| Vorher | Auswahl | Neuer Inhalt |
| --- | --- | --- |
| ungeschützt | ungeschützt lassen | `Plain(markdown)` |
| ungeschützt | Passwort setzen | `encryptContent(markdown, neuesPasswort)` |
| geschützt | unverändert | `encryptContent(markdown, currentPassword)`, vorher `currentPassword` per `decryptContent` gegen den gespeicherten Inhalt prüfen |
| geschützt | neues Passwort | wie "unverändert" prüfen, dann mit `neuesPasswort` verschlüsseln |
| geschützt | Schutz entfernen | wie "unverändert" prüfen, dann `Plain(markdown)` |

5. `updated = now`, `store.save(...)`, `303` auf `/_admin`.

Schritt 4 prüft `currentPassword` erneut, weil das versteckte Feld vom Browser kommt und manipuliert sein kann. Das kostet eine zusätzliche PBKDF2-Ableitung (rund 0,6 s).

### 7.9 Grenzen pro Request

- `RequestBodyLimit` mit `maxSheetBytes + 64 KiB` für Formularfelder, wie in KNot über `install(RequestBodyLimit) { bodyLimit { ... } }` [Q, KNot `Main.kt`].

---

## 8. Sicherheit

### 8.1 Bedrohungen und Maßnahmen

| Bedrohung | Maßnahme | Abschnitt |
| --- | --- | --- |
| Path Traversal | URL wird nie Dateiname, Regex, Lookup nur über die Map | 5.3 |
| XSS über Markdown | `escapeHtml`, eigener URL-Sanitizer, CSP ohne Skripte | 7.6, 8.4 |
| XSS über Titel oder Pfad | kotlinx.html escaped automatisch | 7.7 |
| Passwort raten | Rate-Limit, Mindestlänge, PBKDF2, Gate | 8.3, 6.3 |
| CPU auslasten mit vielen IPs | `CryptoGate` mit 1 Permit | 6.3 |
| Rate-Limit umgehen mit gefälschtem `X-Forwarded-For` | Nur der letzte Eintrag zählt, und nur von Zoraxy | 8.2 |
| CSRF auf Admin-Aktionen | `SameSite=Lax` plus CSRF-Plugin mit `allowOrigin` | 8.5 |
| Login-CSRF | State-Cookie | 7.4 |
| Pfade geschützter Blätter über `Referer` an fremde Seiten | `Referrer-Policy: no-referrer` | 8.4 |
| Indexierung durch Suchmaschinen | `X-Robots-Tag: noindex, nofollow` | 8.4 |
| Speicher füllen | `RequestBodyLimit`, `maxSheetBytes` | 7.9 |
| Secrets im Log | Nie Passwörter, Markdown, Cookies, Client-Secret loggen | 8.6 |

### 8.2 Client-IP hinter Zoraxy

Geprüft im Zoraxy-Quellcode [Q, `dpcore/header.go`, HEAD vom 07.10.2026]: Zoraxy **hängt** die IP der eingehenden Verbindung **hinten** an ein vorhandenes `X-Forwarded-For` an. Alles davor kommt ungeprüft vom Client. `X-Real-Ip` übernimmt Zoraxy unverändert, wenn der Client es mitschickt, ist also ebenfalls fälschbar.

Ktors `XForwardedHeaders` nimmt standardmäßig den **ersten** Eintrag (`useFirstProxy()` im `init`) [Q, `XForwardedHeaders.kt` Zeile 64]. Damit könnte jeder Angreifer bei jedem Versuch eine neue IP vortäuschen und das Rate-Limit aushebeln.

KNote ermittelt die IP deshalb selbst:

```kotlin
/**
 * Client IP for rate limiting. Only the configured proxy may name the client: Zoraxy appends the peer it saw as
 * the last X-Forwarded-For entry, everything before it comes from the client and is ignored.
 */
fun ApplicationCall.clientIp(trustedProxyIp: String): String {
    val peer = request.local.remoteAddress
    if (peer != trustedProxyIp) return peer
    return request.headers.getAll("X-Forwarded-For").orEmpty()
        .flatMap { it.split(',') }
        .lastOrNull()?.trim()?.ifEmpty { null } ?: peer
}
```

- `request.local.remoteAddress` ist der echte Gegenüber der TCP-Verbindung. `request.origin` dagegen kann aus Headern stammen, Ktor warnt ausdrücklich davor [Q, `RequestConnectionPoint.kt`].
- `trustedProxyIp` muss eine IPv4-Adresse sein (Validierung in Abschnitt 9). Zoraxy verbindet sich zu der IPv4-Adresse, die du als Ziel einträgst, also kommt die Verbindung auch von seiner IPv4-Adresse.
- [!] Ob CIO `remoteAddress` genau im Format `10.x.x.x` liefert, ließ sich ohne Ktor-Build nicht testen. Deshalb loggt KNote beim ersten Request eines unbekannten Gegenübers einmalig auf WARN: "Request from untrusted peer <ip>". Prüfung in Abschnitt 11.8.
- Gilt nur, solange genau ein Proxy (Zoraxy) davor sitzt. Kommt später ein CDN davor, muss diese Funktion angepasst werden.

### 8.3 Rate-Limits

```kotlin
install(RateLimit) {
    register(RateLimitName("unlock")) {
        rateLimiter(limit = config.unlockAttemptsPerMinute, refillPeriod = 1.minutes)
        requestKey { call -> call.clientIp(config.trustedProxyIp) }
    }
    register(RateLimitName("login")) {
        rateLimiter(limit = config.loginAttemptsPerMinute, refillPeriod = 1.minutes)
        requestKey { call -> call.clientIp(config.trustedProxyIp) }
    }
}
```

- Ohne `requestKey` teilen sich **alle** Clients ein Limit, der Standardschlüssel ist `Unit` [Q, `RateLimitConfig.kt` Zeile 121].
- Einbindung mit `rateLimit(RateLimitName("unlock")) { post("/{path...}") { ... } }` [Q, `Routing.kt`].
- Ktor räumt die Zähler pro Schlüssel beim Auffüllen wieder ab (`clearOnRefillJobs`) [Q], die Map wächst also nicht endlos.

Rechnung für `unlock` mit 5 pro Minute: 7200 Versuche pro Tag und IP. Ein zufälliges 12-Zeichen-Passwort aus Klein- und Großbuchstaben und Ziffern hat 62^12, also rund 3,2 × 10^21 Möglichkeiten. Selbst mit 1000 IPs bleibt das außer Reichweite. Schwach sind nur Wörter oder bekannte Passwörter, dagegen hilft keine Technik.

### 8.4 Sicherheits-Header

Ein kleines eigenes Plugin (`createApplicationPlugin` mit `onCall` [Q, `CreatePluginUtils.kt`, `PluginBuilder.kt`]) setzt auf **jede** Antwort:

```
Content-Security-Policy: default-src 'none'; style-src 'self'; img-src 'self' https:; form-action 'self'; frame-ancestors 'none'; base-uri 'none'
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
X-Robots-Tag: noindex, nofollow
```

Die Semikolons im CSP-Wert sind Teil der Header-Syntax, also eine bewusste Ausnahme von der Konvention.

`img-src https:` erlaubt externe Bilder in Markdown. Folge: Der Bildserver sieht, wer das Blatt aufruft. Willst du das nicht, auf `img-src 'self'` stellen. Dann zeigen externe Bilder nichts an.

Zusätzlich `Cache-Control: no-store` auf allen Admin-Seiten und auf entsperrten Blättern.

### 8.5 CSRF

- Admin-Cookie mit `SameSite=Lax`: Der Browser schickt es bei seitenübergreifenden `POST`s nicht mit.
- Zusätzlich das CSRF-Plugin auf allen `/_admin`- und `/_auth/logout`-Routen mit `allowOrigin(config.publicBaseUrl)`. Es prüft nur `POST` und andere unsichere Methoden, `GET`, `HEAD`, `OPTIONS` lässt es durch [Q, `CSRF.kt`].
- `allowOrigin` statt `originMatchesHost()`, weil `Host` vom Proxy abhängt, die öffentliche URL aber fest in der Config steht.
- Alle ändernden Aktionen (Anlegen, Speichern, Löschen, Logout) sind `POST`, nie `GET`.

### 8.6 Logging

Klogger nach Konsole (journald), `slf4jBridge { minLevel = KLogger.Level.WARN }` wie in KNot [Q, KNot `Main.kt`].

| Ereignis | Level | Inhalt |
| --- | --- | --- |
| Start | INFO | Version, Host, Port, Anzahl Blätter |
| Admin-Login erfolgreich | INFO | `sub` |
| Admin-Login abgelehnt | WARN | `sub` |
| Blatt angelegt, geändert, gelöscht | INFO | `id`, Pfad, geschützt ja/nein |
| Falsches Blatt-Passwort | WARN | Pfad, Client-IP |
| Unbekannter Gegenüber (8.2) | WARN, einmal pro IP | IP |
| Beschädigtes Blatt | ERROR | `id` |

Nie im Log: Passwörter, Markdown-Inhalt, Cookies, Client-Secret, `sessionSignKey`.

---

## 9. Konfiguration

Eine Datei `config.json`, einmal beim Start gelesen. Unbekannte Schlüssel sind Fehler, alle Probleme werden gesammelt gemeldet, Fehlermeldungen enthalten nie den Dateiinhalt (übernommen aus KNots `ConfigLoader.kt` [Q]).

`config.example.json` (versioniert):

```json
{
  "listenHost": "127.0.0.1",
  "listenPort": 8080,
  "publicBaseUrl": "https://notes.example.org",
  "dataDir": "data",
  "trustedProxyIp": "10.0.0.2",
  "sessionSignKey": "REPLACE_ME_WITH_OUTPUT_OF_knote_key",
  "sessionHours": 12,
  "unlockAttemptsPerMinute": 5,
  "loginAttemptsPerMinute": 10,
  "cryptoParallelism": 1,
  "minSheetPasswordLength": 12,
  "maxSheetBytes": 1048576,
  "oidc": {
    "authorizeUrl": "https://id.example.org/authorize",
    "tokenUrl": "https://id.example.org/api/oidc/token",
    "userinfoUrl": "https://id.example.org/api/oidc/userinfo",
    "clientId": "REPLACE_ME",
    "clientSecret": "REPLACE_ME",
    "allowedSubjects": ["REPLACE_ME"]
  }
}
```

| Feld | Typ | Standard | Regel | Bedeutung |
| --- | --- | --- | --- | --- |
| `listenHost` | String | `127.0.0.1` | nicht leer | Interface. Im LXC auf die Container-IP oder `0.0.0.0` setzen (11.4). |
| `listenPort` | Int | `8080` | 1 bis 65535 | Port |
| `publicBaseUrl` | String | Pflicht | beginnt mit `https://`, ohne `/` am Ende | Für OAuth-Callback und CSRF-Origin |
| `dataDir` | String | `data` | nicht leer | Blätter liegen in `<dataDir>/sheets` |
| `trustedProxyIp` | String | Pflicht | IPv4-Literal (Regex [T]) | IP von Zoraxy aus Sicht von KNote |
| `sessionSignKey` | String | Pflicht | genau 64 Hex-Zeichen (32 Bytes) | HMAC-Schlüssel für das Admin-Cookie |
| `sessionHours` | Int | `12` | 1 bis 720 | Gültigkeit der Admin-Session |
| `unlockAttemptsPerMinute` | Int | `5` | mindestens 1 | Limit `unlock` pro IP |
| `loginAttemptsPerMinute` | Int | `10` | mindestens 1 | Limit `login` pro IP |
| `cryptoParallelism` | Int | `1` | 1 bis Anzahl CPUs | Gleichzeitige PBKDF2-Ableitungen |
| `minSheetPasswordLength` | Int | `12` | mindestens 8 | Mindestlänge Blatt-Passwort |
| `maxSheetBytes` | Int | `1048576` | 1024 bis 10485760 | Höchstgröße Markdown in Bytes |
| `oidc.authorizeUrl` | String | Pflicht | `https://` | Authorize-Endpoint |
| `oidc.tokenUrl` | String | Pflicht | `https://` oder `http://` (intern) | Token-Endpoint |
| `oidc.userinfoUrl` | String | Pflicht | wie `tokenUrl` | Userinfo-Endpoint |
| `oidc.clientId` | String | Pflicht | nicht leer | aus Pocket ID |
| `oidc.clientSecret` | String | Pflicht | nicht leer | aus Pocket ID, nie loggen |
| `oidc.allowedSubjects` | Liste | Pflicht | mindestens ein Eintrag | `sub`-Werte mit Admin-Recht |

`tokenUrl` und `userinfoUrl` dürfen `http://` sein, weil Pocket ID für diese beiden Endpunkte eine eigene interne URL (`InternalAppURL`) vorsieht [Q]. Liegt Pocket ID auf demselben Host, kann KNote es so direkt über die Bridge erreichen.

---

## 10. Kommandozeile und Exit-Codes

Wie KNot: `key=value`-Argumente über KotNexLibs `ArgsInterpreter`, einzelne Wörter über `args.contains(...)`.

| Aufruf | Wirkung |
| --- | --- |
| `java -jar knote.jar [config=<pfad>]` | Server starten, Standard `config=config.json` |
| `java -jar knote.jar key` | 32 Zufallsbytes (`SecureRandom`) als 64 Hex-Zeichen auf stdout, Hinweis auf stderr. Braucht keine Config. |
| `java -jar knote.jar [config=<pfad>] check` | Config **und** alle Blätter laden und prüfen, dann beenden |

| Code | Bedeutung | systemd startet neu? |
| --- | --- | --- |
| 0 | Erfolg | nein |
| 1 | Start fehlgeschlagen (z. B. Port belegt) | ja |
| 64 | Unbekanntes Argument (`EX_USAGE`) | nein |
| 65 | Blatt-Dateien fehlerhaft (`EX_DATAERR`) | nein |
| 78 | Config fehlerhaft (`EX_CONFIG`) | nein |

65 ist neu gegenüber KNot. Ein Neustart repariert kaputte Dateien nicht, systemd soll deshalb nicht in eine Schleife laufen.

---

## 11. Betrieb

### 11.1 Fat-JAR bauen (lokal)

```bash
./gradlew build
./gradlew buildFatJar
ls -l build/libs/knote.jar
```

### 11.2 Container anlegen (auf dem Host "Incus")

```bash
incus image list images:debian/13
incus launch images:debian/13 knote
incus list knote -c n4s
```

[!] Dass der Alias `debian/13` existiert, folgt hier nur indirekt: Das Image-Build-Repository `lxc/lxc-ci` baut `trixie` [Q, `jenkins/jobs/image-debian.yaml`]. Der erste Befehl oben prüft es direkt.

### 11.3 Java im Container

```bash
incus exec knote -- bash
apt update
apt install -y wget gpg
wget -O - https://apt.corretto.aws/corretto.key | gpg --dearmor -o /usr/share/keyrings/corretto-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/corretto-keyring.gpg] https://apt.corretto.aws stable main" > /etc/apt/sources.list.d/corretto.list
apt update
apt install -y java-25-amazon-corretto-jdk
/usr/lib/jvm/java-25-amazon-corretto/bin/java -version
```

- Paketname `java-25-amazon-corretto-jdk` und Installationspfad `/usr/lib/jvm/java-25-amazon-corretto` [Q, `corretto/corretto-25`, `installers/linux/universal/deb/build.gradle`]. Das Paket registriert `/usr/bin/java` per `update-alternatives` [Q, `postin_jdk.sh.template`].
- [!] Die beiden Zeilen für Schlüssel und Repository entsprechen der AWS-Anleitung, die Seite war von hier aus nicht abrufbar. Vorher gegen [die Anleitung](https://docs.aws.amazon.com/corretto/latest/corretto-25-ug/generic-linux-install.html) abgleichen.

### 11.4 Nutzer, Dateien, Rechte

Im Container:

```bash
useradd --system --create-home --home-dir /home/knote --shell /usr/sbin/nologin knote
install -d -o knote -g knote -m 700 /home/knote/data
```

Auf dem Host:

```bash
incus file push build/libs/knote.jar knote/home/knote/knote.jar
incus file push config.json knote/home/knote/config.json
incus file push knote.service knote/etc/systemd/system/knote.service
incus exec knote -- chown knote:knote /home/knote/knote.jar /home/knote/config.json
incus exec knote -- chmod 600 /home/knote/config.json
```

In `config.json`: `listenHost` auf die IPv4 des Containers aus `incus list knote -c n4` setzen (oder `0.0.0.0`). Bei `127.0.0.1` erreicht Zoraxy den Dienst nicht, weil Zoraxy nicht im selben Container läuft.

`sessionSignKey` erzeugen:

```bash
incus exec knote -- /usr/lib/jvm/java-25-amazon-corretto/bin/java -jar /home/knote/knote.jar key
```

### 11.5 systemd-Unit `knote.service`

Abgeleitet aus KNots `knote.service` [Q]:

```ini
[Unit]
Description=KNote, notes with optional password per sheet
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
Type=simple
User=knote
WorkingDirectory=/home/knote
ExecStart=/usr/lib/jvm/java-25-amazon-corretto/bin/java -jar /home/knote/knote.jar config=/home/knote/config.json
Restart=on-failure
RestartSec=5
# 64 usage, 65 broken sheet files, 78 broken config. A restart fixes none of them.
RestartPreventExitStatus=64 65 78
# 143 is the JVM exit code after SIGTERM, a clean stop
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

```bash
incus exec knote -- systemctl daemon-reload
incus exec knote -- runuser -u knote -- /usr/lib/jvm/java-25-amazon-corretto/bin/java -jar /home/knote/knote.jar config=/home/knote/config.json check
incus exec knote -- systemctl enable --now knote
incus exec knote -- journalctl -u knote -n 50 --no-pager
```

`runuser` statt `sudo`, weil `runuser` zu `util-linux` gehört und damit im Debian-Image vorhanden ist, `sudo` dagegen nicht unbedingt.

### 11.6 Pocket ID

1. In Pocket ID einen neuen OIDC-Client anlegen: Name `KNote`, Callback-URL `https://notes.example.org/_auth/callback`, **nicht** öffentlich (also mit Secret), PKCE aus (E19).
2. Optional: den Client auf eine Gruppe beschränken (Pocket ID kann Clients auf Gruppen einschränken [Q, `IsGroupRestricted`, `AllowedUserGroups`]). Das ist eine zweite Schranke zusätzlich zu `allowedSubjects`.
3. Client-ID und Secret in `config.json` eintragen. Endpunkte wie in Abschnitt 7.4.
4. Die Endpunkte deiner Instanz kannst du so nachsehen:

```bash
curl -s https://id.example.org/.well-known/openid-configuration | jq '{authorization_endpoint, token_endpoint, userinfo_endpoint}'
```

5. Deine `sub` finden: `allowedSubjects` vorerst auf `["REPLACE_ME"]` lassen, KNote starten, Login versuchen. Im Log steht `Login denied for sub=<wert>`. Diesen Wert eintragen, `systemctl restart knote`.

### 11.7 Zoraxy

Neue HTTP-Proxy-Regel: Domain `notes.example.org`, Ziel `<container-ipv4>:8080`, TLS über Zoraxy wie bei deinen anderen Diensten.

[!] Die genauen Feldnamen in der Zoraxy-Oberfläche habe ich nicht geprüft.

Danach `trustedProxyIp` auf die IPv4 setzen, mit der Zoraxy beim Container ankommt. Liegt Zoraxy selbst in einem Incus-Container, ist das dessen IPv4 aus `incus list -c n4`. Läuft Zoraxy direkt auf dem Host, ist es die Host-Adresse auf `incusbr0`:

```bash
ip -4 addr show incusbr0
```

### 11.8 Abnahme nach dem Deployment

Jeder Punkt mit erwartetem Ergebnis:

```bash
# 1. Header vorhanden
curl -sI https://notes.example.org/gibt-es-nicht | grep -i -E "content-security-policy|referrer-policy|x-robots-tag"
# erwartet: alle drei Zeilen, Status 404

# 2. Unbekanntes Blatt
curl -s -o /dev/null -w "%{http_code}\n" https://notes.example.org/gibt-es-nicht
# erwartet: 404

# 3. Rate-Limit greift (vorher ein geschütztes Blatt "test/geschuetzt" anlegen)
for i in $(seq 1 7); do curl -s -o /dev/null -w "%{http_code}\n" -X POST -d password=falsch https://notes.example.org/test/geschuetzt; done
# erwartet: 5 mal 403, dann 429

# 4. Gefälschtes X-Forwarded-For hilft nicht
for i in $(seq 1 7); do curl -s -o /dev/null -w "%{http_code}\n" -H "X-Forwarded-For: 1.2.3.$i" -X POST -d password=falsch https://notes.example.org/test/geschuetzt; done
# erwartet: weiterhin 429 (gleiche Minute) bzw. nach einer Minute 5 mal 403, dann 429

# 5. Admin ohne Login
curl -s -o /dev/null -w "%{http_code} %{redirect_url}\n" https://notes.example.org/_admin
# erwartet: 302 auf /_auth/login

# 6. Admin-POST ohne passenden Origin
curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Origin: https://evil.example" https://notes.example.org/_admin/new
# erwartet: 302 (kein Login) oder 403 (CSRF), niemals 200 oder 303

# 7. Kein WARN "untrusted peer" für Zoraxy-Anfragen
incus exec knote -- journalctl -u knote --no-pager | grep -i "untrusted peer"
# erwartet: leer. Steht dort die Zoraxy-IP, ist trustedProxyIp falsch.
```

### 11.9 Backup und Update

```bash
# Vor jedem Update
incus snapshot create knote vor-update-$(date +%Y%m%d)

# Update
./gradlew buildFatJar
incus file push build/libs/knote.jar knote/home/knote/knote.jar
incus exec knote -- chown knote:knote /home/knote/knote.jar
incus exec knote -- systemctl restart knote
```

Die Blätter sind normale Dateien in `/home/knote/data/sheets`. Geschützte Blätter bleiben auch im Backup verschlüsselt.

---

## 12. Tests

Mit `kotlin.test` und `ktor-server-test-host`, wie KNot. Der Aufbau `Application.knote(...)` wird getrennt vom Serverstart gehalten, damit Route-Tests ihn mit Fakes starten können (wie `Application.knot(...)` in KNot).

| Bereich | Fälle |
| --- | --- |
| Pfad | alle Fälle aus 5.3, Länge 128 und 129 [T] |
| SheetStore | speichern, neu laden, doppelter Pfad, umbenennen, löschen, unbekannter Schlüssel, Dateiname passt nicht, ungültiger Pfad, `.tmp` wird ignoriert [T] |
| Crypto | Hin und zurück, falsches Passwort, leerer Text, kaputte Daten, `CryptoGate` begrenzt [T] |
| Markdown | alle Fälle aus 7.6 [T] |
| Config | jede Regel aus Abschnitt 9 einzeln, mehrere Fehler auf einmal |
| `clientIp` | fremder Gegenüber ignoriert Header, Zoraxy mit einem, mehreren und keinem Eintrag, mehrere Header |
| Öffentliche Routen | 404 für unbekannt und ungültig, Plain rendert, Encrypted zeigt Formular, richtiges und falsches Passwort, 429 nach Limit, Header auf jeder Antwort |
| Admin-Routen | ohne Session Redirect, mit Session Übersicht, Anlegen, Speichern mit allen fünf Fällen aus 7.8, 409 bei altem `updated`, CSRF lehnt fremden Origin ab |
| Login | `stateMatches` (gleich, ungleich, fehlend), Auswertung der Userinfo-Antwort (`sub` erlaubt, nicht erlaubt, fehlt) |

Der echte Weg über Pocket ID wird einmal von Hand getestet (11.6 Schritt 5).

---

## 13. Umsetzung in Schritten

Jeder Schritt endet mit einer Prüfung. Erst wenn sie grün ist, kommt der nächste.

| Nr. | Schritt | Prüfung |
| --- | --- | --- |
| 1 | Repository aus KNot ableiten: `build.gradle.kts` (4.2), `settings.gradle.kts`, Wrapper, `BuildConfig`-Task, leeres `Main.kt` | `./gradlew build` grün |
| 2 | `config` und `cli` (`key`, `check`), Exit-Codes | Tests Config grün, `java -jar build/libs/knote.jar key` gibt 64 Hex-Zeichen aus |
| 3 | `storage` aus `proto/SheetStore.kt` übernehmen, in den Projektstil bringen | Tests SheetStore grün |
| 4 | `crypto` mit `CryptoGate` | Tests Crypto grün |
| 5 | `markdown` | Tests Markdown grün |
| 6 | `web`: `clientIp`, Sicherheits-Header, öffentliche Routen, Rate-Limit `unlock` | Tests `clientIp` und öffentliche Routen grün |
| 7 | `auth`: OAuth, State-Cookie, Userinfo, Session, Rate-Limit `login` | Tests Login grün, lokaler Login gegen Pocket ID klappt |
| 8 | Admin-Seiten mit CSRF | Tests Admin-Routen grün |
| 9 | README nach KNot-Vorlage, `config.example.json`, `knote.service` | `java -jar knote.jar check` mit Beispiel-Config meldet nur die `REPLACE_ME`-Felder |
| 10 | Deployment nach Abschnitt 11 | Alle Punkte aus 11.8 wie erwartet |

---

## 14. Offene Fragen an dich

Zu jedem V-Punkt aus Abschnitt 3. Ohne Antwort gilt der Vorschlag.

1. **E11:** Ist es okay, dass du zum Bearbeiten eines geschützten Blatts dessen Passwort eingeben musst?
2. **E12:** Reicht Entsperren ohne Session (Reload bedeutet Passwort erneut eingeben)?
3. **E13:** Titel im Klartext, damit die Übersicht ohne Passwort funktioniert. Einverstanden?
4. **E14:** kotlinx.html als zusätzliche Abhängigkeit statt Raw-String-Templates?
5. **E15:** Gar kein JavaScript in v1?
6. **E17:** `/` auf `/_admin` weiterleiten, oder lieber 404?
7. **E20:** Mindestlänge 12 für Blatt-Passwörter?
8. **E22:** Aktuelles Passwort im versteckten Feld während des Bearbeitens?
9. **E23:** Abweichung von der Compose-Konvention für die Weboberfläche?
10. **8.4:** Externe Bilder in Markdown erlauben (`img-src https:`)?
11. **E10:** Alle drei GFM-Erweiterungen, oder nur Tabellen?
12. **E19:** PKCE in v1 weglassen (Kompromiss, siehe Begründung), oder gleich mit einbauen?
13. Läuft Zoraxy auf dem Host "Incus" direkt oder in einem eigenen Container? Das bestimmt `trustedProxyIp` (11.7).
14. Wie soll die Domain heißen? Im Konzept steht der Platzhalter `notes.example.org`.

---

## Anhang A: Quellen und geprüfte Versionen

| Was | Version oder Stand | Geprüft wie |
| --- | --- | --- |
| [DemoAiProject](https://github.com/nexus421/DemoAiProject) | HEAD vom 12.10.2026 | `build.gradle.kts`, `CLAUDE.md`, `README.md` gelesen |
| [KNot](https://github.com/nexus421/KNot) | HEAD vom 12.10.2026 | `build.gradle.kts`, `Main.kt`, `ConfigLoader.kt`, `knot.service` gelesen |
| [KotNexLib](https://github.com/nexus421/KotNexLib) | Tag `v4.4.1` | `AES.kt` gelesen, AES-Teil kompiliert und getestet |
| [Klogger](https://github.com/nexus421/Klogger) | Tag `v0.4.0` | `build.gradle.kts`, README (SLF4J-Bridge) gelesen |
| [Ktor](https://github.com/ktorio/ktor) | Tag `3.6.0` | Auth, Sessions, Rate-Limit, CSRF, Forwarded-Header, HTML-Builder, Routing, `ktor-utils` gelesen |
| [ktor-build-plugins](https://github.com/ktorio/ktor-build-plugins) | HEAD vom 17.09.2026 | `BomFile.kt` gelesen |
| [commonmark-java](https://github.com/commonmark/commonmark-java) | Tag `commonmark-parent-0.30.1` | Kern und drei Erweiterungen kompiliert und getestet |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Tag `v1.11.0` | Core und JSON kompiliert, Datenmodell getestet |
| [kotlinx.html](https://github.com/Kotlin/kotlinx.html) | Tag `0.12.0` (die Version, die Ktor 3.6.0 mitbringt) | `api.kt`, `stream.kt` gelesen |
| [Zoraxy](https://github.com/tobychui/zoraxy) | HEAD vom 07.10.2026 | `dpcore/header.go` gelesen |
| [Pocket ID](https://github.com/pocket-id/pocket-id) | HEAD, letzter Tag `v2.18.0` | `well_known_controller.go`, `oidc_service.go` gelesen |
| [Corretto 25](https://github.com/corretto/corretto-25) | HEAD | Debian-Paketbau gelesen |
| [lxc-ci](https://github.com/lxc/lxc-ci) | HEAD | `image-debian.yaml` gelesen |
| Kotlin-Compiler | 2.4.20 von GitHub | für alle [T]-Tests |
| RFC 6749 | Abschnitt 3.2 | Token-Endpoint verlangt POST |
| RFC 9700 (OAuth 2.0 Security Best Current Practice) | Abschnitt 2.1.1 | PKCE für vertrauliche Clients empfohlen, nicht verpflichtend |

## Anhang B: Inhalt von `knote-verifikation.zip`

| Datei | Prüft | Ergebnis |
| --- | --- | --- |
| `CryptoCheck.kt` | AES-GCM hin und zurück mit und ohne Kompression, falsches Passwort, frisches Salt, kaputte Daten, leerer Text, atomarer Move, Pfad-Regex | alle PASS |
| `MarkdownCheck.kt` | Tabellen, Durchstreichen, Aufgaben, XSS-Fälle, URL-Filter, Unicode | alle PASS (erster Lauf scheiterte an `data:`, siehe 7.6) |
| `proto/SheetStore.kt` | Prototyp für Datenmodell und Speicherung | Grundlage für Schritt 3 |
| `proto/StoreCheck.kt` | alle Speicher-Fälle aus Abschnitt 12 | alle PASS |
| `proto/CryptoGateCheck.kt` | Semaphore begrenzt parallele Ableitungen | PASS |
| `hex/Hex.kt` | `hexToByteArray` ohne Opt-in, IPv4-Regex | PASS |
| `README.md` | Wie die Tests ohne Gradle gebaut wurden | |
