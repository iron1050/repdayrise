package com.repdayrise.app.data.cloud

import com.repdayrise.app.data.sharing.DayriseHttp
import com.repdayrise.app.data.sharing.SharingJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable internal data class CreateBackupResponse(val code: String)
@Serializable internal data class WriteBackupBody(val data: JsonElement, val baseVersion: Int? = null)
@Serializable internal data class WriteBackupResponse(val version: Int, val updatedAt: Long)
@Serializable internal data class BackupResponse(val version: Int, val updatedAt: Long, val size: Int = 0, val data: JsonElement? = null)

/** Client for the backend's /v1/backups endpoints. The recovery code is the bearer token. */
internal class CloudBackupApi {
    suspend fun create(base: String): String =
        SharingJson.decodeFromString<CreateBackupResponse>(DayriseHttp.call(base, "POST", "/v1/backups").body).code

    /** [baseVersion] null overwrites whatever is there; otherwise the server answers 409 if someone else wrote first. */
    suspend fun write(base: String, code: String, data: JsonElement, baseVersion: Int?): WriteBackupResponse =
        SharingJson.decodeFromString(DayriseHttp.call(base, "PUT", "/v1/backups", code, SharingJson.encodeToString(WriteBackupBody(data, baseVersion))).body)

    suspend fun read(base: String, code: String): BackupResponse =
        SharingJson.decodeFromString(DayriseHttp.call(base, "GET", "/v1/backups", code).body)

    suspend fun delete(base: String, code: String) {
        DayriseHttp.call(base, "DELETE", "/v1/backups", code)
    }
}
