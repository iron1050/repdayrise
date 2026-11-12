package com.repdayrise.app.data.cloud

import android.content.Context
import com.repdayrise.app.data.BackupSummary
import com.repdayrise.app.data.DataBackup
import com.repdayrise.app.data.sharing.ApiException
import com.repdayrise.app.data.sharing.SharingJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@Serializable
data class CloudBackupState(
    val serverUrl: String = "",
    /** The recovery code, grouped as the server gave it. Blank means cloud backup is off. */
    val code: String = "",
    /** The server's version this phone last wrote or restored. */
    val version: Int = 0,
    val lastDigest: String = "",
    val lastSyncedAt: Long = 0,
    val lastError: String? = null,
    /** Another device wrote to the same backup. Automatic uploads stop until the person picks a side. */
    val conflict: Boolean = false,
    /** The backup was deleted on the server. */
    val gone: Boolean = false,
) {
    val enabled: Boolean get() = code.isNotBlank()
}

/** A backup fetched from the server and checked, waiting for the person to confirm replacing their data. */
class RestorePreview internal constructor(
    val summary: BackupSummary,
    val updatedAt: Long,
    internal val serverUrl: String,
    internal val code: String,
    internal val version: Int,
    internal val json: String,
)

/**
 * Keeps a copy of the whole database on the backend, uploaded a little after each change.
 *
 * One phone is the source of truth: each upload replaces the stored copy. State lives in a private file
 * (not the auto-backed-up DataStore) because the recovery code is a credential.
 */
class CloudBackupRepository(
    context: Context,
    private val backup: DataBackup,
    private val serverUrl: () -> String,
    private val scope: CoroutineScope,
) {
    private val api = CloudBackupApi()
    private val file = File(context.filesDir, "cloud_backup.json")
    private val writeLock = Mutex()
    private val pushLock = Mutex()
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private var retryJob: Job? = null

    private val _state = MutableStateFlow(load())
    val state: StateFlow<CloudBackupState> = _state

    init {
        // The server refuses writes closer than 10 s apart, so batch bursts of changes well past that.
        @OptIn(FlowPreview::class)
        scope.launch { syncRequests.receiveAsFlow().debounce(DEBOUNCE_MS).collect { push() } }
        requestSync()
    }

    private fun load(): CloudBackupState =
        runCatching { SharingJson.decodeFromString<CloudBackupState>(file.readText()) }.getOrDefault(CloudBackupState())

    private suspend fun edit(transform: (CloudBackupState) -> CloudBackupState) {
        _state.update(transform)
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(SharingJson.encodeToString(_state.value))
                if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
            }
        }
    }

    /** Ask for an upload soon. Safe to call on every data change. */
    fun requestSync() {
        val s = _state.value
        if (s.enabled && !s.conflict && !s.gone) syncRequests.trySend(Unit)
    }

    /**
     * Uploads the current data. Skips the upload when nothing changed, unless [evenIfUnchanged].
     * [overwrite] replaces the server's copy even if another device wrote to it.
     */
    private suspend fun push(evenIfUnchanged: Boolean = false, overwrite: Boolean = false) = pushLock.withLock {
        val s = _state.value
        if (!s.enabled || s.gone || (s.conflict && !overwrite)) return@withLock
        try {
            val snapshot = backup.snapshot()
            val digest = sha256(snapshot.toString())
            if (!evenIfUnchanged && !overwrite && digest == s.lastDigest && s.lastError == null) return@withLock
            snapshot.put("exportedAt", System.currentTimeMillis())
            val data = SharingJson.parseToJsonElement(snapshot.toString())
            val result = api.write(s.serverUrl, s.code, data, if (overwrite) null else s.version)
            edit {
                it.copy(version = result.version, lastDigest = digest, lastSyncedAt = result.updatedAt, lastError = null, conflict = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            when (e.status) {
                409 -> edit { it.copy(conflict = true, lastError = "Another phone changed this backup.") }
                401 -> edit { it.copy(gone = true, lastError = "This backup no longer exists on the server.") }
                429 -> {
                    edit { it.copy(lastError = e.message) }
                    retryLater((e.retryAfterSeconds ?: 60) * 1000)
                }
                else -> edit { it.copy(lastError = e.message) }
            }
        } catch (e: Exception) {
            edit { it.copy(lastError = describe(e)) }
        }
    }

    private fun retryLater(delayMs: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs + 1_000)
            requestSync()
        }
    }

    private suspend fun attempt(block: suspend () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(Exception(describe(e)))
    }

    private fun describe(e: Exception): String = when (e) {
        is ApiException -> e.message
        is IOException -> "Couldn't reach the server. Check your connection and try again."
        else -> e.message ?: "Something went wrong."
    }

    private fun requireServer(): String = serverUrl().ifBlank { throw IllegalStateException("Add a server address first, under Partners.") }

    /** Creates a backup on the server and uploads this phone's data to it. */
    suspend fun turnOn(): Result<Unit> = attempt {
        val base = requireServer()
        val code = api.create(base)
        edit { CloudBackupState(serverUrl = base, code = code) }
        push(evenIfUnchanged = true)
    }

    /** Uploads now, without waiting for the next change. Returns the upload's error, if any. */
    suspend fun backUpNow(): Result<Unit> {
        push(evenIfUnchanged = true)
        return _state.value.lastError?.let { Result.failure(Exception(it)) } ?: Result.success(Unit)
    }

    /** After a conflict: make the server's copy match this phone again. */
    suspend fun keepThisPhone(): Result<Unit> {
        push(overwrite = true)
        return _state.value.lastError?.let { Result.failure(Exception(it)) } ?: Result.success(Unit)
    }

    /** Fetches and checks the backup behind [rawCode] (or this phone's own code) without changing anything. */
    suspend fun preview(rawCode: String = _state.value.code, server: String = ""): Result<RestorePreview> {
        var result: RestorePreview? = null
        return attempt {
            val code = formatRecoveryCode(rawCode)
            if (code.count { it.isLetterOrDigit() } != RECOVERY_LENGTH) throw IllegalStateException("A recovery code has $RECOVERY_LENGTH letters and numbers.")
            val own = code == _state.value.code && _state.value.serverUrl.isNotBlank()
            val base = when {
                server.isNotBlank() -> server // handed over by the other phone, see NfcTransfer
                own -> _state.value.serverUrl
                else -> requireServer()
            }
            val response = try {
                api.read(base, code)
            } catch (e: ApiException) {
                throw if (e.status == 401) IllegalStateException("That recovery code doesn't match a backup.") else e
            }
            val json = response.data?.toString() ?: throw IllegalStateException("That backup is empty.")
            result = RestorePreview(backup.inspect(json), response.updatedAt, base, code, response.version, json)
        }.map { result!! }
    }

    /** What a tap hands to another phone. */
    fun nfcPayload(): NfcPayload? = _state.value.takeIf { it.enabled && !it.gone }?.let { NfcPayload(it.code, it.serverUrl) }

    /** Replaces this phone's data with [preview] and keeps backing up to that code from now on. */
    suspend fun restore(preview: RestorePreview): Result<Unit> = attempt {
        backup.import(preview.json)
        val digest = sha256(backup.snapshot().toString())
        edit {
            CloudBackupState(
                serverUrl = preview.serverUrl, code = preview.code, version = preview.version,
                lastDigest = digest, lastSyncedAt = System.currentTimeMillis(),
            )
        }
    }

    /** Stops backing up. With [deleteFromServer] the stored copy is deleted first. */
    suspend fun turnOff(deleteFromServer: Boolean): Result<Unit> = attempt {
        val s = _state.value
        if (deleteFromServer && s.enabled && !s.gone) {
            try {
                api.delete(s.serverUrl, s.code)
            } catch (e: ApiException) {
                if (e.status != 401) throw e // already gone
            }
        }
        retryJob?.cancel()
        edit { CloudBackupState() }
    }

    companion object {
        const val RECOVERY_LENGTH = 30
        private const val DEBOUNCE_MS = 15_000L

        /** Tidies a typed or pasted code into ABCDE-FGHJK-… groups. */
        fun formatRecoveryCode(raw: String): String =
            raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.chunked(5).joinToString("-")

        internal fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
