package bayern.kickner.knote.storage

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import kotnexlib.ResultOf2
import java.nio.file.Files
import java.nio.file.Path

private const val TAG = "ExampleSheet"

/** Path of the example sheet created on the first start. */
const val EXAMPLE_PATH = "example"

/** Title of the example sheet. */
const val EXAMPLE_TITLE = "Welcome to KNote"

/** The example sheet, written at [now]. The text is the resource `example.md`. */
fun exampleSheet(now: Long): Sheet {
    val markdown = Sheet::class.java.getResource("/example.md")?.readText().orEmpty()
    return Sheet(SheetStore.newId(), EXAMPLE_PATH, EXAMPLE_TITLE, SheetContent.Plain(markdown), "knote", now, now)
}

/**
 * Loads the sheets from [dir] like [SheetStore.load]. A missing [dir] means a fresh install: it is created together
 * with the [exampleSheet] at [EXAMPLE_PATH], so the first look at KNote shows something. An existing [dir] is never
 * touched, so a deleted example does not come back.
 *
 * @param clock Current time in Unix milliseconds, injectable for tests.
 */
fun openStore(dir: Path, clock: () -> Long = System::currentTimeMillis): ResultOf2<SheetStore, List<String>> {
    val firstStart = Files.notExists(dir)
    val store = when (val loaded = SheetStore.load(dir)) {
        is ResultOf2.Success -> loaded.value
        is ResultOf2.Failure -> return loaded
    }
    if (firstStart) when (val saved = store.save(exampleSheet(clock()))) {
        is ResultOf2.Success -> staticLog(KLogger.Level.INFO, TAG) { "First start: created the example sheet at /$EXAMPLE_PATH" }
        is ResultOf2.Failure -> staticLog(KLogger.Level.WARN, TAG) { "First start: the example sheet could not be created: ${saved.value}" }
    }
    return ResultOf2.Success(store)
}
