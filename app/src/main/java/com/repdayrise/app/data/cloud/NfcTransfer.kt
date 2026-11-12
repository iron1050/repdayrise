package com.repdayrise.app.data.cloud

import android.nfc.Tag
import android.nfc.tech.IsoDep
import com.repdayrise.app.data.sharing.SharingJson
import kotlinx.serialization.Serializable
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What one phone hands the other: the recovery code, and the server it lives on. */
@Serializable
data class NfcPayload(val code: String, val server: String = "")

/**
 * Phone-to-phone transfer of a recovery code over NFC.
 *
 * The phone that has the backup emulates a contactless card ([NfcTransferService]); the new phone
 * reads it in reader mode ([read]). Two exchanges:
 *
 *   1. SELECT the Dayrise AID                         -> 90 00
 *   2. GET CODE with the reader's ephemeral P-256 key -> sender's ephemeral key, nonce, AES-GCM(payload), 90 00
 *
 * Both sides derive the AES key from ECDH, so someone listening to the radio from further away
 * than the tap can't recover the code. The sender only answers while its "send" screen is open
 * ([offer]), for a short time, once, and only while the phone is unlocked.
 */
object NfcTransfer {
    /** Proprietary AID: F0 + "DAYRISE". Must match res/xml/nfc_transfer_service.xml. */
    val AID: ByteArray = hex("F044415952495345")

    private const val INS_GET_CODE: Byte = 0x10
    private val OK = byteArrayOf(0x90.toByte(), 0x00)
    private val NOT_READY = byteArrayOf(0x69.toByte(), 0x85.toByte()) // conditions of use not satisfied
    private val UNKNOWN = byteArrayOf(0x6D.toByte(), 0x00) // instruction not supported
    private val CONTEXT = "dayrise-nfc-v1".toByteArray()
    /** Every P-256 public key's X.509 encoding starts with these 26 bytes; only the 65-byte point goes over the air. */
    private val P256_HEADER = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200")
    private val random = SecureRandom()

    // ---- Sender ----------------------------------------------------------------------------

    private class Offer(val payload: ByteArray, val expiresAt: Long, val onSent: () -> Unit)

    @Volatile private var offer: Offer? = null

    /** Lets the next tap within [ttlMs] receive [payload]. [onSent] runs once it has been handed over. */
    fun offer(payload: NfcPayload, ttlMs: Long = 60_000, onSent: () -> Unit) {
        // A short APDU reply holds 256 bytes: 1 + 65 key + 12 nonce + 16 tag + 2 status leaves ~160 for the JSON.
        // Drop an unusually long server address rather than overflow; the reader then uses its own.
        val compact = if (payload.server.length > 100) payload.copy(server = "") else payload
        offer = Offer(SharingJson.encodeToString(compact).toByteArray(), System.currentTimeMillis() + ttlMs, onSent)
    }

    fun withdraw() {
        offer = null
    }

    /** Answers one command from the reader. Called by [NfcTransferService] on a binder thread. */
    internal fun respond(apdu: ByteArray): ByteArray {
        if (isSelect(apdu)) return OK
        if (apdu.size < 5 || apdu[1] != INS_GET_CODE) return UNKNOWN
        val current = offer?.takeIf { System.currentTimeMillis() < it.expiresAt } ?: return NOT_READY
        return try {
            val lc = apdu[4].toInt() and 0xFF
            val readerKey = decodeKey(apdu.copyOfRange(5, 5 + lc))
            val mine = newKeyPair()
            val nonce = ByteArray(12).also(random::nextBytes)
            val sealed = aes(Cipher.ENCRYPT_MODE, sharedKey(mine, readerKey), nonce, current.payload)
            val pub = point(mine.public)
            offer = null
            current.onSent()
            byteArrayOf(pub.size.toByte()) + pub + nonce + sealed + OK
        } catch (e: Exception) {
            NOT_READY
        }
    }

    // ---- Reader ----------------------------------------------------------------------------

    class TransferException(message: String) : Exception(message)

    /** Reads a code from a phone that is showing its "send" screen. Blocking: call off the main thread. */
    fun read(tag: Tag): NfcPayload {
        val iso = IsoDep.get(tag) ?: throw TransferException("That isn't a phone running Dayrise.")
        iso.use {
            it.connect()
            it.timeout = 5_000
            val select = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)
            if (!it.transceive(select).endsWithOk()) throw TransferException("Open Dayrise on the other phone first.")

            val mine = newKeyPair()
            val pub = point(mine.public)
            val get = byteArrayOf(0x80.toByte(), INS_GET_CODE, 0x00, 0x00, pub.size.toByte()) + pub + byteArrayOf(0x00)
            val reply = it.transceive(get)
            if (!reply.endsWithOk()) {
                throw TransferException("On the other phone, open Settings, Cloud backup, then Send to a phone by tapping.")
            }
            val body = reply.copyOfRange(0, reply.size - 2)
            val keyLen = body[0].toInt() and 0xFF
            val senderKey = decodeKey(body.copyOfRange(1, 1 + keyLen))
            val nonce = body.copyOfRange(1 + keyLen, 13 + keyLen)
            val sealed = body.copyOfRange(13 + keyLen, body.size)
            val plain = try {
                aes(Cipher.DECRYPT_MODE, sharedKey(mine, senderKey), nonce, sealed)
            } catch (e: Exception) {
                throw TransferException("The transfer was garbled. Try again.")
            }
            return SharingJson.decodeFromString(plain.decodeToString())
        }
    }

    // ---- Crypto ----------------------------------------------------------------------------

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    private fun point(key: PublicKey): ByteArray = key.encoded.copyOfRange(P256_HEADER.size, key.encoded.size)

    private fun decodeKey(point: ByteArray): PublicKey {
        require(point.size == 65 && point[0] == 0x04.toByte()) { "Not an uncompressed P-256 point" }
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(P256_HEADER + point))
    }

    private fun sharedKey(mine: KeyPair, theirs: PublicKey): SecretKeySpec {
        val secret = KeyAgreement.getInstance("ECDH").apply { init(mine.private); doPhase(theirs, true) }.generateSecret()
        val digest = MessageDigest.getInstance("SHA-256").apply { update(secret); update(CONTEXT) }.digest()
        return SecretKeySpec(digest, "AES")
    }

    private fun aes(mode: Int, key: SecretKeySpec, nonce: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(128, nonce)); updateAAD(CONTEXT) }.doFinal(data)

    private fun isSelect(apdu: ByteArray): Boolean =
        apdu.size >= 5 + AID.size && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte() && apdu[2] == 0x04.toByte() &&
            apdu.copyOfRange(5, 5 + AID.size).contentEquals(AID)

    private fun ByteArray.endsWithOk() = size >= 2 && this[size - 2] == OK[0] && this[size - 1] == OK[1]

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
