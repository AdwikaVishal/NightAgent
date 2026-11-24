package com.nightagent.evidence.testutil

import com.nightagent.evidence.integrity.Nge1ContainerVerifier
import java.io.File

/** Records NGE1 verification calls without doing any cryptographic work. */
class FakeNge1Verifier : Nge1ContainerVerifier {

    var calls: Int = 0
        private set

    var lastVerified: File? = null
        private set

    var failure: Exception? = null

    override fun verify(file: File) {
        calls++
        lastVerified = file
        failure?.let { throw it }
    }
}
