package com.nightagent.evidence.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests the no-replace publication contract using the JVM publisher that mirrors
 * Android's exclusive-create-and-copy strategy. Real Os.open/O_EXCL behavior
 * remains device-only verification.
 */
class AtomicFilePublisherTest {

    @Test
    fun existingDestinationIsNotOverwritten() {
        val directory = Files.createTempDirectory("nge1_publisher_test").toFile()
        val temporary = File(directory, "candidate.part").apply { writeText("new") }
        val target = File(directory, "evidence.ng1").apply { writeText("original") }

        assertThrows(IOException::class.java) {
            JvmAtomicFilePublisher().publish(temporary, target)
        }

        assertEquals("original", target.readText())
        assertEquals("new", temporary.readText())
    }

    @Test
    fun temporaryPathEqualToTargetIsRejected() {
        val directory = Files.createTempDirectory("nge1_publisher_test").toFile()
        val file = File(directory, "evidence.ng1").apply { writeText("original") }

        assertThrows(IOException::class.java) {
            JvmAtomicFilePublisher().publish(file, file)
        }
        assertEquals("original", file.readText())
    }

    @Test
    fun concurrentPublicationsHaveExactlyOneWinner() {
        val directory = Files.createTempDirectory("nge1_publisher_test").toFile()
        val publisher = JvmAtomicFilePublisher()
        val target = File(directory, "evidence.ng1")
        val threadCount = 8
        val candidates = (0 until threadCount).map { index ->
            File(directory, "candidate-$index.part").apply {
                writeBytes(byteArrayOf(index.toByte()))
            }
        }
        val start = CountDownLatch(1)
        val successes = AtomicInteger()
        val failures = AtomicInteger()
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            val futures = candidates.map { candidate ->
                executor.submit {
                    start.await()
                    try {
                        publisher.publish(candidate, target)
                        successes.incrementAndGet()
                    } catch (e: IOException) {
                        failures.incrementAndGet()
                        errors.add(e.message ?: e.toString())
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(15, TimeUnit.SECONDS)
        }

        assertEquals("errors=$errors", 1, successes.get())
        assertEquals(threadCount - 1, failures.get())
        val winner = target.readBytes().single().toInt()
        assertTrue(winner in 0 until threadCount)
    }
}
