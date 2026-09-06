package com.tvremocon.ir

/**
 * What became of an IR send.
 *
 * Four outcomes, not two, because "no response" and "the TV was not operated" are different
 * facts and the app cannot tell them apart. Collapsing [Unknown] into a failure would invite
 * the user to press again after a command that already went out.
 */
sealed interface SendResult {

    /** Nothing was transmitted — the problem was found before anything left the device. */
    data class NotSent(val reason: Reason) : SendResult

    /** The hub returned the acknowledgement shape for this exact method. */
    data object Accepted : SendResult

    /**
     * The request left the device and no usable answer came back. The hub may well have
     * transmitted. Never retried automatically.
     */
    data class Unknown(val detail: String) : SendResult

    /** The hub answered with an explicit error code, somewhere in the nested response. */
    data class Rejected(val code: Any?) : SendResult

    enum class Reason {
        /** ACCESS_LOCAL_NETWORK not granted (Android 17+). */
        NO_PERMISSION,

        /** No Wi-Fi network to pin the socket to. */
        NO_WIFI,

        /** Setup has not been completed, or the stored remote no longer exists. */
        NOT_CONFIGURED,

        /** The account does not match this hub; retrying cannot help. */
        BAD_CREDENTIALS,

        /** The hub did not answer, and rediscovery did not find it either. */
        HUB_UNREACHABLE,

        /** Gave up before sending because the operation's deadline passed. */
        DEADLINE_PASSED,
    }
}
