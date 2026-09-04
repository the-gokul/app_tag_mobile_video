package com.nordic.tagmobile.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import com.nordic.tagmobile.protocol.TagCommand
import com.nordic.tagmobile.protocol.TagUuids
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import no.nordicsemi.android.ble.data.Data
import java.util.UUID

/**
 * Nordic BleManager for Tag GATT: MTU, discover, notify, START/STOP writes.
 */
class TagBleManager(context: Context) : BleManager(context) {

    interface Listener {
        fun onReady(device: BluetoothDevice)
        fun onDisconnected()
        fun onPacket(data: ByteArray)
        fun onError(message: String)
    }

    var listener: Listener? = null

    private val streamUuid = UUID.fromString(TagUuids.STREAM_SERVICE)
    private val sensorUuid = UUID.fromString(TagUuids.SENSOR_DATA)
    private val commandUuid = UUID.fromString(TagUuids.COMMAND)

    private var sensorChar: BluetoothGattCharacteristic? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var ready = false

    val isTagReady: Boolean get() = ready && isConnected

    init {
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) = Unit
            override fun onDeviceConnected(device: BluetoothDevice) = Unit
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                ready = false
                val msg = when (reason) {
                    ConnectionObserver.REASON_NOT_SUPPORTED ->
                        "Not a Tag device (TAG_STREAM missing)"
                    ConnectionObserver.REASON_TIMEOUT ->
                        "Connect timed out"
                    else ->
                        "Connect failed ($reason)"
                }
                listener?.onError(msg)
            }

            override fun onDeviceReady(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                ready = false
                listener?.onDisconnected()
            }
        })
    }

    override fun getGattCallback(): BleManagerGattCallback = object : BleManagerGattCallback() {
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = gatt.getService(streamUuid) ?: return false
            sensorChar = service.getCharacteristic(sensorUuid)
            commandChar = service.getCharacteristic(commandUuid)
            val sensorOk = sensorChar != null &&
                (sensorChar!!.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            val cmdOk = commandChar != null &&
                (
                    (commandChar!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                        (commandChar!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    )
            return sensorOk && cmdOk
        }

        override fun initialize() {
            requestMtu(247).enqueue()
            setNotificationCallback(sensorChar).with { _, data ->
                val bytes = data.value ?: return@with
                listener?.onPacket(bytes)
            }
            enableNotifications(sensorChar)
                .fail { _, status ->
                    ready = false
                    listener?.onError("Notify enable failed ($status)")
                }
                .done {
                    ready = true
                    bluetoothDevice?.let { listener?.onReady(it) }
                }
                .enqueue()
        }

        override fun onServicesInvalidated() {
            ready = false
            sensorChar = null
            commandChar = null
        }
    }

    fun connectTag(device: BluetoothDevice) {
        ready = false
        // Prefer LE 1M — matches tag advertising; avoid Coded during connect.
        connect(device)
            .retry(3, 200)
            .useAutoConnect(false)
            .timeout(20_000)
            .enqueue()
    }

    fun disconnectTag() {
        disconnect().enqueue()
        ready = false
    }

    fun startRecording(unixMs: Long) {
        val char = commandChar
        if (char == null || !ready) {
            listener?.onError("Not connected")
            return
        }
        writeCharacteristic(
            char,
            Data(TagCommand.startPayload(unixMs)),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
            .fail { _, status -> listener?.onError("START failed ($status)") }
            .enqueue()
    }

    fun stopRecording() {
        val char = commandChar ?: return
        writeCharacteristic(
            char,
            Data(TagCommand.stopPayload()),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
            .fail { _, status -> listener?.onError("STOP failed ($status)") }
            .enqueue()
    }
}
