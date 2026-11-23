package com.nightagent.evidence.crypto

import java.io.IOException

/** Base failure for structurally invalid or unreadable NGE1 containers. */
open class ContainerFormatException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/** Authentication failed: header, tags, ciphertext or key are wrong or altered. */
class AuthenticationFailedException(
    message: String,
    cause: Throwable? = null
) : ContainerFormatException(message, cause)
