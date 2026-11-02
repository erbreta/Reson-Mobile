package org.reson.mobile

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CaptureSessionTest {
    private class Input : CaptureInput {
        override val sampleRate = 48000
        override val description = "test input"
        val readStarted = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var closed = false
        var first = true
        var fail = false
        var closeFails = false
        override fun read(samples: ShortArray): Int {
            if (first) { first = false; samples[0] = 123; return 1 }
            readStarted.countDown()
            check(stopped.await(3, TimeUnit.SECONDS))
            if (fail) error("Microphone disconnected")
            return -3
        }
        override fun stop() { stopped.countDown() }
        override fun close() { closed = true; check(!closeFails) { "Release failed" } }
    }

    @Test fun stopUnblocksReadFinalizesAndReleasesInput() {
        val directory = Files.createTempDirectory("reson-stop").toFile()
        try {
            val input = Input()
            val session = CaptureSession(directory, { input })
            var result: java.io.File? = null
            var failure: Throwable? = null
            val worker = thread { try { result = session.run { _, _, _ -> } } catch (e: Throwable) { failure = e } }
            assertTrue(input.readStarted.await(3, TimeUnit.SECONDS))
            session.stop()
            session.stop()
            worker.join(3000)
            assertFalse(worker.isAlive)
            assertNull(failure)
            assertTrue(input.closed)
            assertEquals(2L, WavFile.inspect(result!!).dataBytes)
        } finally { directory.deleteRecursively() }
    }

    @Test fun readFailureDiscardsPartialFileAndReleasesInput() {
        val directory = Files.createTempDirectory("reson-failure").toFile()
        try {
            val input = Input().apply { fail = true; stopped.countDown() }
            val session = CaptureSession(directory, { input })
            assertThrows(IllegalStateException::class.java) { session.run { _, _, _ -> } }
            assertTrue(input.closed)
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun backgroundStopBeforeInitializationNeverOpensMicrophone() {
        val directory = Files.createTempDirectory("reson-early-stop").toFile()
        try {
            val session = CaptureSession(directory, { error("Should not open microphone") })
            session.stop()
            assertNull(session.run { _, _, _ -> })
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun releaseFailureNeverPublishesSuccessfulRecording() {
        val directory = Files.createTempDirectory("reson-release-failure").toFile()
        try {
            val input = Input().apply { closeFails = true }
            val session = CaptureSession(directory, { input })
            var failure: Throwable? = null
            val worker = thread { try { session.run { _, _, _ -> } } catch (e: Throwable) { failure = e } }
            assertTrue(input.readStarted.await(3, TimeUnit.SECONDS))
            session.stop()
            worker.join(3000)
            assertFalse(worker.isAlive)
            assertNotNull(failure)
            assertTrue(input.closed)
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
