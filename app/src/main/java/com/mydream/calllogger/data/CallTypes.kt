package com.mydream.calllogger.data

import android.provider.CallLog

/** Maps the integer call type stored by the system to a human-readable label. */
object CallTypes {
    fun label(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
        CallLog.Calls.MISSED_TYPE -> "Missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
        CallLog.Calls.REJECTED_TYPE -> "Rejected"
        CallLog.Calls.BLOCKED_TYPE -> "Blocked"
        else -> "Unknown"
    }
}
