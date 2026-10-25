package com.pendantcompanion.protocol

import com.google.protobuf.InvalidProtocolBufferException
import com.pendantcompanion.ble.FragmentAssembler
import com.pendantcompanion.ble.FragmentSender
import com.pendantcompanion.ble.PendantGattClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import limitless.common.Common
import limitless.server.Server
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Request/response layer over the BLE transport, ported from the verified
 * reference (MAkcanca/pendant-cli session.py):
 *
 * - Every command is a ServerCommandMsg with a fresh request_id.
 * - Responses arrive as PendantAllMsg; match by response_data.request_id,
 *   with a oneof-name fallback because some firmware paths (notably
 *   get_device_info, case 14) do not echo the request id.
 * - Bounded timeout on every request. Nothing here is destructive:
 *   this phase only sends GetDeviceInfo, GetDeviceStatus and (fallback)
 *   SetCurrentTime.
 */
class PendantSession(
    private val client: PendantGattClient,
    private val timeSourceMs: () -> Long = System::currentTimeMillis,
) {
    private val assembler = FragmentAssembler(maxPending = 64)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Server.PendantAllMsg>>()
    private val pendingByOneof = ConcurrentHashMap<String, CompletableDeferred<Server.PendantAllMsg>>()
    private val nextRequestId = AtomicLong(1)
    private val nextMessageIndex = AtomicLong(0)
    @Volatile private var sender: FragmentSender? = null
    @Volatile var lastError: String? = null
        private set

    /** Set during a flash download; receives every StorageBufferMsg the pendant pushes. */
    @Volatile var storageListener: ((Server.StorageBufferMsg) -> Unit)? = null

    fun onBleReady(mtu: Int) { sender = FragmentSender(mtu) }

    fun onNotify(data: ByteArray) {
        val wrapper = try {
            Common.BLEMessageFromPendantToNative.parseFrom(data)
        } catch (e: InvalidProtocolBufferException) {
            lastError = "Undecodable fragment from pendant (${data.size} bytes)"
            return
        }
        when (val r = assembler.offer(wrapper)) {
            is FragmentAssembler.Result.Partial -> Unit
            is FragmentAssembler.Result.Rejected -> { lastError = "Fragment rejected: ${r.reason}" }
            is FragmentAssembler.Result.Complete -> {
                val msg = try { Server.PendantAllMsg.parseFrom(r.payload) }
                          catch (e: InvalidProtocolBufferException) {
                              lastError = "Undecodable pendant message (${r.payload.size} bytes)"; return
                          }
                dispatch(msg)
            }
        }
    }

    private fun dispatch(msg: Server.PendantAllMsg) {
        val rid = if (msg.hasResponseData()) msg.responseData.requestId else 0L
        if (rid != 0L) {
            pending.remove(rid)?.let { if (!it.isCompleted) it.complete(msg); return }
        }
        val oneof = oneofName(msg)
        if (oneof != null) {
            pendingByOneof.remove(oneof)?.let { if (!it.isCompleted) it.complete(msg) }
        }
        if (msg.hasStorageBuffer()) storageListener?.invoke(msg.storageBuffer)
    }

    fun oneofName(msg: Server.PendantAllMsg): String? = when {
        msg.hasDeviceInfo() -> "device_info"
        msg.hasStorageBuffer() -> "storage_buffer"
        msg.hasSetCurrentTimeResponse() -> "set_current_time_response"
        msg.hasAuthenticateIngest() -> "authenticate_ingest"
        msg.hasDeviceStatus() -> "device_status"
        msg.hasBatteryStatus() -> "battery_status"
        msg.hasButtonStatus() -> "button_status"
        msg.hasRealTimeAudioData() -> "real_time_audio_data"
        msg.hasRealTimeAudioAck() -> "real_time_audio_ack"
        else -> null
    }

    suspend fun request(expectedOneof: String, setOneof: (Server.ServerCommandMsg.Builder) -> Unit): Server.PendantAllMsg {
        val s = sender ?: throw IllegalStateException("BLE transport not ready (no negotiated MTU yet)")
        val requestId = nextRequestId.getAndIncrement()
        val cmd = Server.ServerCommandMsg.newBuilder().apply {
            requestData = Server.RequestData.newBuilder().setRequestId(requestId).build()
            setOneof(this)
        }.build()
        val deferred = CompletableDeferred<Server.PendantAllMsg>()
        pending[requestId] = deferred
        val existing = pendingByOneof.putIfAbsent(expectedOneof, deferred)
        if (existing != null) {
            pending.remove(requestId)
            throw IllegalStateException("Another $expectedOneof request is already in flight")
        }
        val sendOk = withContext(Dispatchers.Main) {
            s.fragments(nextMessageIndex.getAndIncrement().toInt(), cmd.toByteArray())
                .all { client.send(it.toByteArray()) }
        }
        if (!sendOk) {
            pending.remove(requestId)
            pendingByOneof.remove(expectedOneof, deferred)
            throw IllegalStateException("Could not queue fragments for send")
        }
        return try {
            withTimeout(15_000) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw TimeoutException("No response to $expectedOneof within 15s")
        } finally {
            pending.remove(requestId)
            pendingByOneof.remove(expectedOneof, deferred)
        }
    }

    suspend fun getDeviceInfo(): Server.PendantAllMsg =
        request("device_info") { it.getDeviceInfo = Server.GetDeviceInfo.getDefaultInstance() }

    suspend fun getDeviceStatus(): Server.PendantAllMsg =
        request("device_status") { it.getDeviceStatus = Server.GetDeviceStatus.getDefaultInstance() }

    /** Part of the verified post-pair handshake; sets the clock only, touches no recordings. */
    suspend fun setCurrentTime(): Server.PendantAllMsg =
        request("set_current_time_response") {
            it.setCurrentTime = Server.SetCurrentTime.newBuilder()
                .setUnixTimestampMs(timeSourceMs()).build()
        }

    /**
     * One-way command (no ack expected). Used for batch-download start/stop,
     * mirroring session.py's _send_oneway (do_not_ack=true).
     */
    suspend fun sendOneway(setOneof: (Server.ServerCommandMsg.Builder) -> Unit) {
        val s = sender ?: throw IllegalStateException("BLE transport not ready (no negotiated MTU yet)")
        val requestId = nextRequestId.getAndIncrement()
        val cmd = Server.ServerCommandMsg.newBuilder().apply {
            requestData = Server.RequestData.newBuilder().setRequestId(requestId).setDoNotAck(true).build()
            setOneof(this)
        }.build()
        val sendOk = withContext(Dispatchers.Main) {
            s.fragments(nextMessageIndex.getAndIncrement().toInt(), cmd.toByteArray())
                .all { client.send(it.toByteArray()) }
        }
        if (!sendOk) throw IllegalStateException("Could not queue fragments for send")
    }

    /**
     * Ack path ONLY: erase every flash page with an index at or below [index]
     * (DeleteFlashPage.older_than_or_equal_to_index). Destructive by design,
     * the official app's way of advancing its send pointer; called solely from
     * the opt-in acknowledge mode after a page is durably saved on the phone.
     */
    suspend fun deleteFlashPageUpTo(index: Int) = sendOneway {
        it.deleteFlashPage = Server.DeleteFlashPage.newBuilder()
            .setOlderThanOrEqualToIndex(index).build()
    }

    /**
     * Nuclear option: FactoryResetPendant wipes the pendant's ENTIRE flash,
     * including anything not yet synced to the phone, and may reset the
     * device keys so re-pairing is needed afterwards. One-way command, no
     * ack expected. Called only from the explicit factory-reset UI button.
     */
    suspend fun factoryResetPendant() = sendOneway {
        it.factoryResetPendant = Server.FactoryResetPendant.getDefaultInstance()
    }

    /** Download mode: pendant pushes stored flash pages as StorageBufferMsgs. */
    suspend fun beginBatchDownload() = sendOneway {
        it.downloadFlashPages = Server.DownloadFlashPages.newBuilder()
            .setBatchModeEnabled(true).setRealTimeModeEnabled(false).build()
    }

    /** Return the pendant to record-to-flash mode. */
    suspend fun stopBatchDownload() = sendOneway {
        it.downloadFlashPages = Server.DownloadFlashPages.newBuilder()
            .setBatchModeEnabled(false).setRealTimeModeEnabled(false).build()
    }

    fun close() {
        pending.values.forEach { it.cancel() }
        pending.clear(); pendingByOneof.clear(); assembler.reset()
    }
}
