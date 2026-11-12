package com.repdayrise.app.data.cloud

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Lets another phone read this phone's recovery code with a tap. The system binds it when a reader
 * selects the Dayrise AID; it answers nothing useful unless [NfcTransfer.offer] is active.
 */
class NfcTransferService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray = NfcTransfer.respond(commandApdu)

    override fun onDeactivated(reason: Int) {}
}
