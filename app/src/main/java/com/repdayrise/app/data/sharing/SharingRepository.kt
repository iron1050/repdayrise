package com.repdayrise.app.data.sharing

import android.content.Context
import com.repdayrise.app.BuildConfig
import com.repdayrise.app.data.HabitRepository
import com.repdayrise.app.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * Publishes this phone's habit list to accountability partners and follows other people's.
 *
 * State lives in a private JSON file rather than Room or the backed-up DataStore: it holds
 * bearer tokens, and a restored backup on a second phone shouldn't silently share one identity.
 */
class SharingRepository(
    context: Context,
    private val habits: HabitRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val api = SharingApi()
    private val file = File(context.filesDir, "sharing.json")
    private val writeLock = Mutex()
    private val pushLock = Mutex()
    // A conflated channel holds a pending request even before the collector below has started.
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SharingState> = _state

    /** The address new shares and joins go to: the in-app override, else the build-time default. */
    val defaultServerUrl: String get() = normalizeUrl(BuildConfig.SHARING_URL)
    private fun serverUrl(): String = normalizeUrl(_state.value.serverUrl).ifBlank { defaultServerUrl }

    init {
        @OptIn(FlowPreview::class)
        scope.launch { syncRequests.receiveAsFlow().debounce(2_000).collect { push() } }
        requestSync()
    }

    private fun load(): SharingState =
        runCatching { SharingJson.decodeFromString<SharingState>(file.readText()) }.getOrDefault(SharingState())

    private suspend fun edit(transform: (SharingState) -> SharingState) {
        _state.update(transform)
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(SharingJson.encodeToString(_state.value))
                if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
            }
        }
    }

    private suspend fun editOutgoing(transform: (Outgoing) -> Outgoing) = edit { s -> s.copy(outgoing = s.outgoing?.let(transform)) }

    private suspend fun editFollowing(shareId: String, transform: (Following) -> Following) =
        edit { s -> s.copy(following = s.following.map { if (it.shareId == shareId) transform(it) else it }) }

    /** Runs a network action, turning failures into a sentence a person can act on. */
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

    private fun requireServer(): String = serverUrl().ifBlank { throw IllegalStateException("Add a server address first.") }

    suspend fun setServerUrl(url: String) = edit { it.copy(serverUrl = normalizeUrl(url)) }

    // ---- Publishing your own list ---------------------------------------------------------

    suspend fun startSharing(name: String): Result<Unit> = attempt {
        val base = requireServer()
        val clean = name.trim()
        val created = api.createShare(base, clean)
        edit { it.copy(displayName = clean, outgoing = Outgoing(base, created.id, created.token, created.code)) }
        push()
    }

    /** Ask for the list to be re-published soon. Safe to call on every data change. */
    fun requestSync() {
        if (_state.value.outgoing != null) syncRequests.trySend(Unit)
    }

    /** Publishes the current list if it differs from what the server already has. */
    suspend fun push(force: Boolean = false) = pushLock.withLock {
        val out = _state.value.outgoing ?: return@withLock
        try {
            val snapshot = Snapshot.build(
                habits.getAllHabitsOnce(), habits.getEntriesOnce(), out.excludedHabitIds,
                settings.current().weekStartsMonday, LocalDate.now(),
            )
            val name = _state.value.displayName
            val digest = (name + SharingJson.encodeToString(snapshot)).hashCode().toString()
            if (force || digest != out.lastPushedDigest || out.lastError != null) {
                api.publish(out.serverUrl, out.shareId, out.token, name, snapshot)
            }
            editOutgoing { it.copy(lastSyncedAt = System.currentTimeMillis(), lastPushedDigest = digest, lastError = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val gone = e is ApiException && e.status == 401
            editOutgoing { it.copy(lastError = if (gone) "This list no longer exists on the server. Stop sharing and start again." else describe(e)) }
        }
    }

    /** Refreshes the invite code and the list of partners from the server. */
    suspend fun refreshOutgoing(): Result<Unit> = attempt {
        val out = _state.value.outgoing ?: return@attempt
        val status = api.status(out.serverUrl, out.shareId, out.token)
        editOutgoing { it.copy(code = status.code, partners = status.subscribers) }
    }

    suspend fun setHabitShared(habitId: Long, shared: Boolean) {
        editOutgoing { it.copy(excludedHabitIds = if (shared) it.excludedHabitIds - habitId else it.excludedHabitIds + habitId) }
        requestSync()
    }

    suspend fun rotateCode(): Result<Unit> = attempt {
        val out = _state.value.outgoing ?: return@attempt
        val code = api.rotateCode(out.serverUrl, out.shareId, out.token)
        editOutgoing { it.copy(code = code) }
    }

    suspend fun removePartner(partnerId: String): Result<Unit> = attempt {
        val out = _state.value.outgoing ?: return@attempt
        api.removePartner(out.serverUrl, out.shareId, out.token, partnerId)
        editOutgoing { o -> o.copy(partners = o.partners.filter { it.id != partnerId }) }
    }

    /** Deletes the list from the server, which also ends every partner's subscription. */
    suspend fun stopSharing(): Result<Unit> = attempt {
        val out = _state.value.outgoing ?: return@attempt
        try {
            api.deleteShare(out.serverUrl, out.shareId, out.token)
        } catch (e: ApiException) {
            if (e.status != 401) throw e // 401: already gone, which is what we wanted
        }
        edit { it.copy(outgoing = null) }
    }

    // ---- Following someone else's list ----------------------------------------------------

    suspend fun follow(code: String, name: String): Result<Unit> = attempt {
        val base = requireServer()
        val clean = name.trim()
        val joined = api.join(base, code.trim(), clean)
        if (joined.shareId == _state.value.outgoing?.shareId) {
            runCatching { api.unsubscribe(base, joined.token) }
            throw IllegalStateException("That's your own code. Send it to a partner instead.")
        }
        val previous = _state.value.following.firstOrNull { it.shareId == joined.shareId }
        if (previous != null && !previous.ended) runCatching { api.unsubscribe(previous.serverUrl, previous.token) }
        val entry = Following(base, joined.shareId, joined.token, joined.ownerName)
        edit { s -> s.copy(displayName = s.displayName.ifBlank { clean }, following = s.following.filter { it.shareId != joined.shareId } + entry) }
        refresh(entry)
    }

    suspend fun unfollow(shareId: String) {
        val f = _state.value.following.firstOrNull { it.shareId == shareId } ?: return
        if (!f.ended) runCatching { api.unsubscribe(f.serverUrl, f.token) }
        edit { s -> s.copy(following = s.following.filter { it.shareId != shareId }) }
    }

    /** Fetches the latest snapshot for every list this phone follows. Reports the first failure. */
    suspend fun refreshFollowing(): Result<Unit> {
        var failure: Result<Unit>? = null
        for (f in _state.value.following) {
            if (f.ended) continue
            val result = attempt { refresh(f) }
            if (result.isFailure && failure == null) failure = result
        }
        return failure ?: Result.success(Unit)
    }

    private suspend fun refresh(f: Following) {
        try {
            val feed = api.feed(f.serverUrl, f.shareId, f.token, if (f.snapshot == null) -1 else f.version)
            val now = System.currentTimeMillis()
            editFollowing(f.shareId) {
                if (feed == null) it.copy(fetchedAt = now)
                else it.copy(ownerName = feed.ownerName, version = feed.version, updatedAt = feed.updatedAt, fetchedAt = now, snapshot = feed.snapshot ?: it.snapshot)
            }
        } catch (e: ApiException) {
            if (e.status != 401) throw e
            editFollowing(f.shareId) { it.copy(ended = true) }
        }
    }

    companion object {
        /** Tidies a typed address: trims, drops trailing slashes, assumes https when no scheme is given. */
        fun normalizeUrl(raw: String): String {
            val t = raw.trim().trimEnd('/')
            return when {
                t.isEmpty() -> ""
                t.startsWith("http://") || t.startsWith("https://") -> t
                else -> "https://$t"
            }
        }

        /** Pulls the invite code out of whatever was pasted: a bare code, a dayrise:// link or an https invite link. */
        fun extractCode(raw: String): String {
            val t = raw.trim()
            val tail = Regex("(?:/j/|://join/)([A-Za-z0-9-]+)").find(t)?.groupValues?.get(1) ?: t
            return tail.uppercase().filter { it.isLetterOrDigit() }
        }

        fun inviteLink(serverUrl: String, code: String): String = "$serverUrl/j/${code.filter { it.isLetterOrDigit() }}"
    }
}
