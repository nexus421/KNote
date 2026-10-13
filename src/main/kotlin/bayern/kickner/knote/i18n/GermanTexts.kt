package bayern.kickner.knote.i18n

import bayern.kickner.knote.web.ProtectionChoice

/** German texts. */
object GermanTexts : Texts {
    override val htmlLang = "de"

    override val landingText = "Seiten sind direkt über ihre Adresse erreichbar. Die Verwaltung braucht eine Anmeldung."
    override val logIn = "Anmelden"
    override val logOut = "Abmelden"
    override val loginFailedHeading = "Anmeldung fehlgeschlagen"
    override val loginAbortedText = "Die Anmeldung wurde abgebrochen oder ist abgelaufen."
    override val loginProviderErrorText = "Der Anmeldedienst hat nicht wie erwartet geantwortet."
    override val logInAgain = "Erneut anmelden"
    override val noAccessHeading = "Kein Zugriff"
    override val noAccessText = "Dieses Konto hat keinen Zugriff auf KNote."
    override val sessionExpiredHeading = "Sitzung abgelaufen"
    override val sessionExpiredText = "Bitte neu anmelden. Die Eingaben wurden nicht gespeichert."

    override val notFoundHeading = "Nicht gefunden"
    override val notFoundText = "Unter dieser Adresse gibt es keine Seite."
    override val lockedHint = "Diese Seite ist mit einem Passwort geschützt."
    override val password = "Passwort"
    override val unlock = "Entsperren"
    override val wrongPassword = "Falsches Passwort"
    override val damagedHeading = "Seite beschädigt"
    override val damagedText = "Die Seite kann nicht gelesen werden."

    override val sheets = "Seiten"
    override val newSheet = "Neue Seite"
    override val noSheetsYet = "Noch keine Seiten."
    override val columnPath = "Pfad"
    override val columnTitle = "Titel"
    override val columnProtection = "Schutz"
    override val columnChanged = "Geändert"
    override val columnActions = "Aktionen"
    override val protectedMarker = "geschützt"
    override val edit = "Bearbeiten"
    override val delete = "Löschen"

    override val editSheet = "Seite bearbeiten"
    override val previewHeading = "Vorschau (nicht gespeichert)"
    override val path = "Pfad"
    override val pathHint = "Kleinbuchstaben a bis z, Ziffern und Bindestrich, Abschnitte durch / getrennt."
    override val title = "Titel"
    override val content = "Inhalt (Markdown)"
    override val protection = "Schutz"
    override val create = "Anlegen"
    override val save = "Speichern"
    override val preview = "Vorschau"
    override val cancel = "Abbrechen"
    override val newSheetPasswordHint = "Beide Felder leer lassen für eine ungeschützte Seite."
    override val passwordRepeat = "Passwort wiederholen"
    override val editUnlockHint = "Zum Bearbeiten das Passwort der Seite eingeben."

    override fun editUnlockHeading(title: String) = "Bearbeiten: $title"

    override fun protectionChoice(choice: ProtectionChoice) = when (choice) {
        ProtectionChoice.KEEP -> "unverändert"
        ProtectionChoice.CHANGE -> "neues Passwort"
        ProtectionChoice.REMOVE -> "Schutz entfernen"
        ProtectionChoice.LEAVE_OPEN -> "ungeschützt lassen"
        ProtectionChoice.SET -> "Passwort setzen"
    }

    override fun passwordHint(minLength: Int) = "Mindestens $minLength Zeichen. Geht das Passwort verloren, ist auch der Inhalt verloren."

    override val deleteSheet = "Seite löschen"
    override val deleteQuestionStart = "Seite "
    override fun deleteQuestionEnd(title: String) = " ($title) wirklich löschen?"
    override val deleteProtectedWarning = "Die Seite ist geschützt. Nach dem Löschen ist der Inhalt endgültig verloren."

    override val conflictHeading = "Seite wurde geändert"
    override val conflictText = "Die Seite wurde zwischenzeitlich geändert. Bitte erneut öffnen und die Änderung wiederholen."
    override val toOverview = "Zur Übersicht"
    override val errorHeading = "Fehler"
    override val saveFailedText = "Die Änderung konnte nicht gespeichert werden."
    override val crossSiteHeading = "Abgelehnt"
    override val crossSiteText = "Die Anfrage kam nicht von KNote selbst."

    override val pathTaken = "Pfad bereits vergeben"
    override val invalidProtection = "Ungültige Auswahl beim Schutz"
    override val titleControlCharacters = "Titel darf keine Steuerzeichen enthalten"
    override val passwordsDiffer = "Passwörter stimmen nicht überein"
    override fun invalidPath(maxLength: Int) =
        "Pfad ungültig: erlaubt sind a bis z, 0 bis 9 und Bindestrich, Abschnitte durch / getrennt, höchstens $maxLength Zeichen"
    override fun titleLength(maxLength: Int) = "Titel 1 bis $maxLength Zeichen"
    override fun contentTooLarge(maxBytes: Int) = "Inhalt zu groß (höchstens $maxBytes Bytes)"
    override fun passwordTooShort(minLength: Int) = "Passwort mindestens $minLength Zeichen"
    override fun passwordTooLong(maxLength: Int) = "Passwort höchstens $maxLength Zeichen"
}
