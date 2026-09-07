package com.tomilov.stylishsat.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*
import org.junit.Test

class LocalAccelerationPolicyTest {
    @Test fun onlyTheMeasuredDeviceBuildAndMemoryClassAreEligible() {
        val fingerprint = LocalAcceleration.VERIFIED_FINGERPRINT
        fun eligible(model: String = "CPH2411", api: Int = 35, ram: Long = 11L shl 30,
            abis: Set<String> = setOf("arm64-v8a"), build: String = fingerprint) =
            LocalAcceleration.verifiedProfile(model, api, ram, abis, build)
        assertTrue(eligible())
        assertFalse(eligible(model = "RMX2002"))
        assertFalse(eligible(api = 34))
        assertFalse(eligible(ram = (10L shl 30) - 1))
        assertFalse(eligible(ram = 13L shl 30))
        assertFalse(eligible(abis = setOf("armeabi-v7a")))
        assertFalse(eligible(build = "$fingerprint-new-ota"))
    }

    @Test fun readinessIsBoundToEveryModelRuntimeAndDeviceIdentityField() {
        val identity = PreparedModelIdentity(ModelCatalog.gemma.fileName, ModelCatalog.gemma.sizeBytes, 123_000)
        val receipt = PreparedGpuReceipt(1, ModelCatalog.gemma.id, ModelCatalog.gemma.sha256,
            "0.16.1", 4096, LocalAcceleration.VERIFIED_FINGERPRINT, "CPH2411", 35,
            identity.fileName, identity.bytes, identity.modifiedMillis, emptyList(), 123, 30, 10, 20)
        fun matches(value: PreparedGpuReceipt = receipt, file: PreparedModelIdentity = identity,
            fingerprint: String = LocalAcceleration.VERIFIED_FINGERPRINT, model: String = "CPH2411", api: Int = 35) =
            value.matches(file, fingerprint, model, api)
        assertTrue(matches())
        listOf(receipt.copy(schemaVersion = 2), receipt.copy(modelId = "other"),
            receipt.copy(modelSha256 = "0".repeat(64)), receipt.copy(runtimeVersion = "0.16.2"),
            receipt.copy(contextTokens = 2048), receipt.copy(preparedAtUtcMillis = 0),
            receipt.copy(preparationMillis = -1), receipt.copy(verificationMillis = -1),
            receipt.copy(initializationMillis = -1)).forEach { assertFalse(matches(it)) }
        assertFalse(matches(file = identity.copy(fileName = "other.litertlm")))
        assertFalse(matches(file = identity.copy(bytes = identity.bytes - 1)))
        assertFalse(matches(file = identity.copy(modifiedMillis = identity.modifiedMillis + 1)))
        assertFalse(matches(fingerprint = "new-ota"))
        assertFalse(matches(model = "other"))
        assertFalse(matches(api = 36))
    }

    @Test fun anUnpreparedEngineNeverAttemptsGpu() {
        val calls = mutableListOf<String>()
        val result = initializeTutorBackend(false, { calls += "create:$it"; it },
            { calls += "init:$it" }, { calls += "release:$it" }, { calls += "invalid" })
        assertEquals("CPU(4)", result.second)
        assertEquals(listOf("create:CPU(4)", "init:CPU(4)"), calls)
    }

    @Test fun aFailedGpuIsReleasedAndInvalidatedBeforeCpuAllocation() {
        val calls = mutableListOf<String>()
        val result = initializeTutorBackend(true, { calls += "create:$it"; it },
            { calls += "init:$it"; if (it == "GPU") throw IllegalStateException("unsupported") },
            { calls += "release:$it" }, { calls += "invalid" })
        assertEquals("CPU(4)", result.second)
        assertEquals(listOf("create:GPU", "init:GPU", "release:GPU", "invalid", "create:CPU(4)", "init:CPU(4)"), calls)
    }

    @Test fun successfulGpuDoesNotAllocateCpuOrReleaseCallerOwnedEngine() {
        val calls = mutableListOf<String>()
        assertEquals("GPU", initializeTutorBackend(true, { calls += it; it }, {},
            { fail("Caller owns initialized engine") }, { fail("GPU succeeded") }).second)
        assertEquals(listOf("GPU"), calls)
    }

    @Test fun cancellationReleasesGpuWithoutStartingCpu() {
        val calls = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            initializeTutorBackend(true, { calls += it; it }, { throw CancellationException("cancelled") },
                { calls += "released" }, { fail("Cancellation is not a backend failure") })
        }
        assertEquals(listOf("GPU", "released"), calls)
    }

    @Test fun failedCleanupPreventsOverlappingBackendAllocation() {
        val calls = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            initializeTutorBackend(true, { calls += it; it }, { error("GPU init") },
                { error("GPU cleanup") }, { calls += "invalid" })
        }
        assertEquals(listOf("GPU", "invalid"), calls)
    }

    @Test fun cancelledJobDuringOrdinaryGpuFailureDoesNotInitializeCpu() {
        val job = Job()
        val calls = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            initializeTutorBackend(true, { calls += it; it },
                { job.cancel(); error("Native GPU initialization returned an ordinary error") },
                { calls += "released" }, { calls += "invalid" }, { job.ensureActive() })
        }
        assertEquals(listOf("GPU", "released", "invalid"), calls)
    }

    @Test fun cancellationDuringSuccessfulNativeInitializationReleasesTheOwner() {
        val job = Job()
        val calls = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            initializeTutorBackend(true, { calls += it; it }, { job.cancel() },
                { calls += "released" }, { fail("Cancellation is not a backend failure") }, { job.ensureActive() })
        }
        assertEquals(listOf("GPU", "released"), calls)
    }

    @Test fun unavailableGpuLibraryFallsBackAndCpuFailureIsNotRetried() {
        val calls = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            initializeTutorBackend(true, { calls += it; if (it == "GPU") throw UnsatisfiedLinkError("GPU"); it },
                { error("CPU init") }, { calls += "released" }, { calls += "invalid" })
        }
        assertEquals(listOf("GPU", "invalid", "CPU(4)", "released"), calls)
    }
}
