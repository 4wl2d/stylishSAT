package com.tomilov.stylishsat.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomilov.stylishsat.ai.DownloadState
import com.tomilov.stylishsat.ai.ModelSpec
import com.tomilov.stylishsat.ai.RuntimeServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RuntimeDownloadTest {
    @Test fun httpsRecoveryAndChecksumRejection(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runtimeDownloadValidation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = RuntimeServices.get(context)
        assertSame("Recreated UI owners must share downloads and recordings", runtime, RuntimeServices.get(context))
        val source = File(context.noBackupFilesDir, "testing/jfk.wav").readBytes()
        val suffix = System.currentTimeMillis()
        val spec = ModelSpec("download_test_$suffix", "Pinned download test", "jfk-download-$suffix.wav",
            "https://raw.githubusercontent.com/ggml-org/whisper.cpp/371b5a7561823ab2bb32142d2751e35e7534727b/samples/jfk.wav",
            352078, "59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e")
        // Simulate a process-interrupted download with a genuine prefix; the server may
        // honor Range or send a full200 response. Both must reconstruct exact original bytes.
        File(context.noBackupFilesDir, "models/${spec.fileName}.part").writeBytes(source.copyOfRange(0, 10_000))
        runtime.downloads.start(spec)
        val result = withTimeout(60_000) { runtime.downloads.state.first {
            (it is DownloadState.Installed && it.modelId == spec.id) || (it is DownloadState.Failed && it.modelId == spec.id)
        } }
        assertTrue("HTTPS recovery should install: $result", result is DownloadState.Installed)
        assertArrayEquals(source, runtime.downloads.modelFile(spec)!!.readBytes())
        val negativeRuntime = RuntimeServices.get(context)
        val bad = spec.copy(id = "bad_checksum_$suffix", fileName = "bad-checksum-$suffix.wav", sha256 = "0".repeat(64))
        negativeRuntime.downloads.start(bad)
        val rejected = withTimeout(60_000) { negativeRuntime.downloads.state.first {
            (it is DownloadState.Failed && it.modelId == bad.id) || (it is DownloadState.Installed && it.modelId == bad.id)
        } }
        assertTrue("Corrupt or mismatched artifacts must be rejected", rejected is DownloadState.Failed)
        assertFalse(negativeRuntime.downloads.isInstalled(bad))
        assertFalse(File(context.noBackupFilesDir, "models/${bad.fileName}").exists())
    }
}
