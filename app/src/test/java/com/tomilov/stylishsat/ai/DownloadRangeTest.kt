package com.tomilov.stylishsat.ai

import org.junit.Test
import java.io.IOException

class DownloadRangeTest {
    @Test fun exactRemainingRangeCanResume() {
        ModelDownloadManager.validateRange("bytes 100-999/1000", 100, 1000)
    }
    @Test(expected = IOException::class) fun staleObjectCannotBeAppended() {
        ModelDownloadManager.validateRange("bytes 100-1000/1001", 100, 1000)
    }
    @Test(expected = IOException::class) fun wrongOffsetCannotBeAppended() {
        ModelDownloadManager.validateRange("bytes 0-999/1000", 100, 1000)
    }
    @Test(expected = IOException::class) fun unboundedRangeCannotBeInstalled() {
        ModelDownloadManager.validateRange("bytes 100-999/*", 100, 1000)
    }
}
