package bayern.kickner.knote.i18n

import bayern.kickner.knote.web.ProtectionChoice
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

/**
 * Every text the web pages show. One implementation per language, chosen with `language` in the config. A new
 * language is one more implementation plus an entry in [Language]: the compiler reports every missing text.
 */
interface Texts {
    /** Value of the `lang` attribute of every page. */
    val htmlLang: String

    /** Text before the linked "KNote" in the footer. */
    val poweredBy: String

    // Start page and login
    val landingText: String
    val logIn: String
    val logOut: String
    val loginFailedHeading: String
    val loginAbortedText: String
    val loginProviderErrorText: String
    val logInAgain: String
    val noAccessHeading: String
    val noAccessText: String
    val sessionExpiredHeading: String
    val sessionExpiredText: String

    // Sheets
    val notFoundHeading: String
    val notFoundText: String
    val lockedHint: String
    val password: String
    val unlock: String
    val wrongPassword: String
    val damagedHeading: String
    val damagedText: String
    val lastChanged: String
    val downloadMarkdown: String

    // Overview
    val sheets: String
    val newSheet: String
    val noSheetsYet: String
    val search: String
    val searchPlaceholder: String
    val noMatches: String
    val showAll: String
    val columnPath: String
    val columnTitle: String
    val columnProtection: String
    val columnChanged: String
    val columnActions: String
    val protectedMarker: String
    val edit: String
    val delete: String

    // Editor
    val editSheet: String
    val previewHeading: String
    val path: String
    val pathHint: String
    val title: String
    val content: String
    val protection: String
    val create: String
    val save: String
    val preview: String
    val cancel: String
    val newSheetPasswordHint: String
    val passwordRepeat: String
    val editUnlockHint: String

    /** Heading of the password form before the editor of a protected sheet opens. */
    fun editUnlockHeading(title: String): String

    /** Label of a radio button in the protection fieldset. */
    fun protectionChoice(choice: ProtectionChoice): String

    /** Hint below the protection choice. */
    fun passwordHint(minLength: Int): String

    // Delete
    val deleteSheet: String
    val deleteQuestionStart: String

    /** Rest of the question after the sheet path, which is shown as code between [deleteQuestionStart] and this. */
    fun deleteQuestionEnd(title: String): String
    val deleteProtectedWarning: String

    // Errors after saving
    val conflictHeading: String
    val conflictText: String
    val toOverview: String
    val errorHeading: String
    val saveFailedText: String
    val crossSiteHeading: String
    val crossSiteText: String

    // Form validation
    val pathTaken: String
    val invalidProtection: String
    val titleControlCharacters: String
    val passwordsDiffer: String
    fun invalidPath(maxLength: Int): String
    fun titleLength(maxLength: Int): String
    fun contentTooLarge(maxBytes: Int): String
    fun passwordTooShort(minLength: Int): String
    fun passwordTooLong(maxLength: Int): String
}

/**
 * The languages KNote speaks.
 *
 * @property code Value of `language` in the config.
 */
enum class Language(val code: String, val texts: Texts) {
    ENGLISH("en", EnglishTexts),
    GERMAN("de", GermanTexts);

    companion object {
        /** The language for [code], or null. */
        fun of(code: String): Language? = entries.firstOrNull { it.code == code }
    }
}

/** Where the application keeps the texts of the configured language. */
val TextsKey = AttributeKey<Texts>("KNoteTexts")

/** Texts of the configured language. */
val ApplicationCall.texts: Texts get() = application.attributes[TextsKey]
