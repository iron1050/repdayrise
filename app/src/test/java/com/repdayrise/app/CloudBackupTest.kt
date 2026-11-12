package com.repdayrise.app

import com.repdayrise.app.data.cloud.CloudBackupApi
import com.repdayrise.app.data.cloud.CloudBackupRepository
import com.repdayrise.app.data.cloud.CloudBackupState
import com.repdayrise.app.data.sharing.ApiException
import com.repdayrise.app.data.sharing.SharingJson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CloudBackupTest {
    @Test
    fun recoveryCodesAreTidiedHoweverTheyAreTyped() {
        val code = "ABCDE-FGHJK-MNPQR-STUVW-XYZ23-45678"
        assertEquals(code, CloudBackupRepository.formatRecoveryCode(code))
        assertEquals(code, CloudBackupRepository.formatRecoveryCode(" abcde fghjk\nmnpqr-stuvw xyz23 45678 "))
        assertEquals(code, CloudBackupRepository.formatRecoveryCode("abcdefghjkmnpqrstuvwxyz2345678"))
    }

    @Test
    fun stateSurvivesARoundTrip() {
        val state = CloudBackupState(serverUrl = "https://x", code = "AAAAA", version = 3, lastDigest = "d", lastSyncedAt = 9, lastError = "e", conflict = true)
        assertEquals(state, SharingJson.decodeFromString<CloudBackupState>(SharingJson.encodeToString(state)))
        assertTrue(state.enabled)
        assertFalse(CloudBackupState().enabled)
    }

    /** Talks to a real backend started with `npm run dev`. Runs only when DAYRISE_TEST_SERVER is set. */
    @Test
    fun clientAndServerAgreeOnTheWireFormat() = runTest {
        val base = System.getenv("DAYRISE_TEST_SERVER").orEmpty()
        assumeTrue("DAYRISE_TEST_SERVER not set", base.isNotBlank())
        val api = CloudBackupApi()
        val code = api.create(base)
        val data = SharingJson.parseToJsonElement("""{"app":"dayrise","version":1,"habits":[],"entries":[],"groups":[],"reminders":[]}""")

        assertEquals(1, api.write(base, code, data, 0).version)
        val read = api.read(base, code)
        assertEquals(1, read.version)
        assertEquals(data, read.data)

        val throttled = runCatching { api.write(base, code, data, 1) }.exceptionOrNull() as ApiException
        assertEquals(429, throttled.status)
        assertTrue((throttled.retryAfterSeconds ?: 0) > 0)

        api.delete(base, code)
        assertEquals(401, (runCatching { api.read(base, code) }.exceptionOrNull() as ApiException).status)
    }
}
