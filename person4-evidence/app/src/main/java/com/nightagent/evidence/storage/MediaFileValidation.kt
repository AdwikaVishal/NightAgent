package com.nightagent.evidence.storage

import java.io.File

/** Result of checking whether a stored recording is usable. */
sealed interface MediaValidation {
    data class Valid(val sizeBytes: Long) : MediaValidation
    data object Missing : MediaValidation
    data object Empty : MediaValidation
}

/**
 * Shared existence/size check for staged media files.
 *
 * Audio and video staging both use this check so success is never reported for a
 * file that was not created or is empty. This is not an integrity or authenticity
 * check; it is only a basic finalization check.
 */
fun validateMediaFile(file: File): MediaValidation = when {
    !file.exists() -> MediaValidation.Missing
    file.length() <= 0L -> MediaValidation.Empty
    else -> MediaValidation.Valid(file.length())
}
