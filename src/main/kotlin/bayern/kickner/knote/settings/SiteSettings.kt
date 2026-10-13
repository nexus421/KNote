package bayern.kickner.knote.settings

import bayern.kickner.knote.config.firstLineOf
import bayern.kickner.knote.storage.Sheet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotnexlib.ResultOf2
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText

/** Settings files are readable on disk, show every value and reject unknown keys. */
private val settingsJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

/**
 * Which sheets the start page lists for visitors without login. Disabled sheets are never listed. Of a protected
 * sheet only title and path are shown, its content stays locked.
 */
@Serializable
enum class StartPageList {
    @SerialName("none") NONE,
    @SerialName("unprotected") UNPROTECTED,
    @SerialName("protected") PROTECTED,
    @SerialName("all") ALL;

    /** True if [sheet] belongs on the start page with this setting. */
    fun includes(sheet: Sheet): Boolean = sheet.enabled && when (this) {
        NONE -> false
        UNPROTECTED -> sheet.isProtected.not()
        PROTECTED -> sheet.isProtected
        ALL -> true
    }
}

/**
 * Settings changed in the admin area, unlike the config file, which is only read at startup.
 *
 * @property startPageList Sheets listed on the start page.
 */
@Serializable
data class SiteSettings(val startPageList: StartPageList = StartPageList.NONE)

/** Keeps the [SiteSettings] in memory and writes every change to [file] atomically. */
class SettingsStore private constructor(private val file: Path, initial: SiteSettings) {

    /** The current settings. */
    @Volatile
    var current: SiteSettings = initial
        private set

    /** Writes [settings] to disk first and only then makes them current. */
    @Synchronized
    fun save(settings: SiteSettings): Result<SiteSettings> = runCatching {
        Files.createDirectories(file.toAbsolutePath().parent)
        val tmp = Files.createTempFile(file.toAbsolutePath().parent, "settings", ".tmp")
        runCatching {
            Files.writeString(tmp, settingsJson.encodeToString(SiteSettings.serializer(), settings))
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { runCatching { Files.deleteIfExists(tmp) } }.getOrThrow()
        current = settings
        settings
    }

    companion object {
        /**
         * Reads [file]. A missing file means the defaults and is not created, so `check` changes nothing on disk.
         *
         * @return the store, or a problem that is safe to log.
         */
        fun load(file: Path): ResultOf2<SettingsStore, String> {
            if (Files.notExists(file)) return ResultOf2.Success(SettingsStore(file, SiteSettings()))
            return runCatching { settingsJson.decodeFromString(SiteSettings.serializer(), file.readText()) }
                .fold(
                    { ResultOf2.Success(SettingsStore(file, it)) },
                    { ResultOf2.Failure("${file.fileName}: ${firstLineOf(it.message)}") },
                )
        }
    }
}
