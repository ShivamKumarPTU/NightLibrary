package com.example.nightlibrary.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaDownloadWorkerTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testIncognitoWipeTracesOnFailure() = runBlocking {
        // 1. Setup fake trace file
        val vaultDownloadsDir = File(context.filesDir, "vault_downloads")
        vaultDownloadsDir.mkdirs()
        val mediaId = 999L
        val fakeTempFile = File(vaultDownloadsDir, "dl_${mediaId}_fake.mp4")
        fakeTempFile.writeText("fake content")

        // 2. Build worker with incognito = true
        val worker = TestListenableWorkerBuilder<MediaDownloadWorker>(
            context = context,
            inputData = workDataOf(
                "url" to "invalid-url", // Force immediate fail
                "mediaId" to mediaId,
                "incognito" to true
            )
        ).build()

        // 3. Run worker
        worker.doWork()

        // 4. Verify trace file is wiped by finally block
        assertFalse("Fake temp file should be deleted", fakeTempFile.exists())
    }
}
