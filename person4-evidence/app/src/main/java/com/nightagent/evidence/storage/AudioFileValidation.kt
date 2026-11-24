package com.nightagent.evidence.storage

import java.io.File

/** Audio keeps its existing name; validation logic is shared with video. */
typealias AudioValidation = MediaValidation

/** A recording is only reportable as successful when the file exists and is non-empty. */
fun validateAudioFile(file: File): AudioValidation = validateMediaFile(file)
