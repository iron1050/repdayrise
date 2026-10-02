package com.repdayrise.app.data.sharing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** The server answered, but not with success. [message] is safe to show. */
class ApiException(val status: Int, override val message: String) : Exception(message)

internal val SharingJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Thin client for the Dayrise sharing backend (see /backend). Every call names its server, since lists can live on different ones. */
internal class SharingApi {
    private class Reply(val status: Int, val body: String)

    private suspend fun call(base: String, method: String, path: String, token: String? = null, body: String? = null, etag: String? = null): Reply =
        withContext(Dispatchers.IO) {
            val conn = URL(base + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/json")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (etag != null) conn.setRequestProperty("If-None-Match", etag)
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                if (status in 200..299 || status == 304) return@withContext Reply(status, text)
                val message = runCatching { SharingJson.decodeFromString<ErrorBody>(text).message }.getOrNull()
                throw ApiException(status, message?.takeIf { it.isNotBlank() } ?: "The server said no ($status).")
            } finally {
                conn.disconnect()
            }
        }

    suspend fun createShare(base: String, name: String): CreateShareResponse =
        SharingJson.decodeFromString(call(base, "POST", "/v1/shares", body = SharingJson.encodeToString(NameBody(name))).body)

    suspend fun publish(base: String, shareId: String, token: String, name: String, snapshot: Snapshot) {
        call(base, "PUT", "/v1/shares/$shareId", token, SharingJson.encodeToString(PublishBody(name, snapshot)))
    }

    suspend fun status(base: String, shareId: String, token: String): StatusResponse =
        SharingJson.decodeFromString(call(base, "GET", "/v1/shares/$shareId", token).body)

    suspend fun rotateCode(base: String, shareId: String, token: String): String =
        SharingJson.decodeFromString<CodeResponse>(call(base, "POST", "/v1/shares/$shareId/rotate-code", token).body).code

    suspend fun removePartner(base: String, shareId: String, token: String, partnerId: String) {
        call(base, "DELETE", "/v1/shares/$shareId/subscribers/$partnerId", token)
    }

    suspend fun deleteShare(base: String, shareId: String, token: String) {
        call(base, "DELETE", "/v1/shares/$shareId", token)
    }

    suspend fun join(base: String, code: String, name: String): JoinResponse =
        SharingJson.decodeFromString(call(base, "POST", "/v1/join", body = SharingJson.encodeToString(JoinBody(code, name))).body)

    /** Returns null when the list hasn't changed since [version]. */
    suspend fun feed(base: String, shareId: String, token: String, version: Int): FeedResponse? {
        val reply = call(base, "GET", "/v1/feed", token, etag = "\"$shareId-$version\"")
        return if (reply.status == 304) null else SharingJson.decodeFromString(reply.body)
    }

    suspend fun unsubscribe(base: String, token: String) {
        call(base, "DELETE", "/v1/feed", token)
    }
}
