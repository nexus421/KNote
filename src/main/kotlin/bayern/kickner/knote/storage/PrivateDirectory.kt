package bayern.kickner.knote.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Creates [dir] readable by the owner only (700), like the files KNote writes into it (600). Missing parents get the
 * default permissions. An existing [dir] stays as it is, so a deliberate group permission survives an update.
 * Without POSIX permissions (Windows) [dir] is created plainly.
 */
fun createPrivateDirectory(dir: Path) {
    if (Files.isDirectory(dir)) return
    dir.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    if ("posix" in dir.fileSystem.supportedFileAttributeViews())
        Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    else Files.createDirectory(dir)
}
