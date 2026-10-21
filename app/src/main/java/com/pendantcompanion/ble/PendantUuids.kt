package com.pendantcompanion.ble

import java.util.UUID

/** Verified against MAkcanca/pendant-cli (firmware v1.1.20 tests). */
object PendantUuids {
    val SERVICE: UUID = UUID.fromString("632de001-604c-446b-a80f-7963e950f3fb")
    val WRITE: UUID = UUID.fromString("632de002-604c-446b-a80f-7963e950f3fb")   // phone -> pendant
    val NOTIFY: UUID = UUID.fromString("632de003-604c-446b-a80f-7963e950f3fb")  // pendant -> phone
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
}
