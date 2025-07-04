package com.example.quick_blue

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.BluetoothGattDescriptor.PERMISSION_READ
import android.bluetooth.BluetoothGattDescriptor.PERMISSION_WRITE
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.util.isEmpty
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


private const val TAG = "QuickBluePlugin"

/** QuickBluePlugin */
@SuppressLint("MissingPermission")
class QuickBluePlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var method: MethodChannel
    private lateinit var eventAvailabilityChange: EventChannel
    private lateinit var eventScanResult: EventChannel
    private lateinit var messageConnector: BasicMessageChannel<Any>
    private var connected = false
    private val lock = ReentrantLock()
    private val writeCondition = lock.newCondition()
    private val readCondition = lock.newCondition()
    private val notificationCondition = lock.newCondition()

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        val taskQueue = flutterPluginBinding.binaryMessenger.makeBackgroundTaskQueue()
        method = MethodChannel(
            flutterPluginBinding.binaryMessenger, "quick_blue/method",
            StandardMethodCodec.INSTANCE,
            taskQueue
        )
        eventAvailabilityChange =
            EventChannel(
                flutterPluginBinding.binaryMessenger, "quick_blue/event.availabilityChange",
                StandardMethodCodec.INSTANCE,
                taskQueue
            )
        eventScanResult =
            EventChannel(
                flutterPluginBinding.binaryMessenger, "quick_blue/event.scanResult",
                StandardMethodCodec.INSTANCE,
                taskQueue
            )
        messageConnector = BasicMessageChannel(
            flutterPluginBinding.binaryMessenger,
            "quick_blue/message.connector",
            StandardMessageCodec.INSTANCE,
            taskQueue
        )
        method.setMethodCallHandler(this)
        eventAvailabilityChange.setStreamHandler(this)
        eventScanResult.setStreamHandler(this)

        context = flutterPluginBinding.applicationContext
        mainThreadHandler = Handler(Looper.getMainLooper())
        bluetoothManager =
            flutterPluginBinding.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        context.registerReceiver(
            broadcastReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        bluetoothManager.adapter.bluetoothLeScanner?.stopScan(scanCallback)

        context.unregisterReceiver(broadcastReceiver)
        eventAvailabilityChange.setStreamHandler(null)
        eventScanResult.setStreamHandler(null)
        method.setMethodCallHandler(null)
    }

    private lateinit var context: Context
    private lateinit var mainThreadHandler: Handler
    private lateinit var bluetoothManager: BluetoothManager

    private val knownGatts = mutableListOf<BluetoothGatt>()

    private fun sendMessage(messageChannel: BasicMessageChannel<Any>, message: Map<String, Any>) {
        mainThreadHandler.post { messageChannel.send(message) }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "isBluetoothAvailable" -> {
                Handler(Looper.getMainLooper()).post {
                    result.success(bluetoothManager.adapter.isEnabled)
                }
            }

            "startScan" -> {
                val advertisedServices = call.argument<List<String>?>("advertisedServices")
                bluetoothManager.adapter.bluetoothLeScanner?.startScan(advertisedServices?.map { it: String ->
                    ScanFilter.Builder().setServiceUuid(
                        ParcelUuid(UUID.fromString(it))
                    ).build()
                } ?: listOf(), ScanSettings.Builder().build(), scanCallback)
                Handler(Looper.getMainLooper()).post { result.success(null) }
            }

            "stopScan" -> {
                bluetoothManager.adapter.bluetoothLeScanner?.stopScan(scanCallback)
                Handler(Looper.getMainLooper()).post { result.success(null) }
            }

            "connect" -> {
                val deviceId = call.argument<String>("deviceId")!!
                if (knownGatts.find { it.device.address == deviceId } != null) {
                    Handler(Looper.getMainLooper()).post { result.success(null) }
                    return
                }
                val remoteDevice = bluetoothManager.adapter.getRemoteDevice(deviceId)
                val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    remoteDevice.connectGatt(
                        context,
                        false,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )
                } else {
                    remoteDevice.connectGatt(context, false, gattCallback)
                }
                knownGatts.add(gatt)
                connected = true
                Handler(Looper.getMainLooper()).post { result.success(null) }
            }

            "disconnect" -> {
                val deviceId = call.argument<String>("deviceId")!!
                val gatt = knownGatts.find { it.device.address == deviceId }
                if (gatt == null) {
                    Handler(Looper.getMainLooper()).post {
                        result.error(
                            "IllegalArgument",
                            "Unknown deviceId: $deviceId",
                            null
                        )
                    }
                } else {
                    Handler(Looper.getMainLooper()).post {
                        cleanConnection(gatt)
                        result.success(null)
                    }
                }
            }

            "discoverServices" -> {
                val deviceId = call.argument<String>("deviceId")!!
                val gatt = knownGatts.find { it.device.address == deviceId }
                if (gatt == null) {
                    Handler(Looper.getMainLooper()).post {
                        result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)
                    }
                    return
                }
                gatt.discoverServices()
                Handler(Looper.getMainLooper()).post { result.success(null) }
            }

            "setNotifiable" -> {
                lock.withLock<Unit> {
                    val deviceId = call.argument<String>("deviceId")!!
                    val service = call.argument<String>("service")!!
                    val characteristic = call.argument<String>("characteristic")!!
                    val bleInputProperty = call.argument<String>("bleInputProperty")!!
                    val gatt = knownGatts.find { it.device.address == deviceId }
                    if (gatt == null) {
                        Handler(Looper.getMainLooper()).post {
                            result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)
                        }
                        return
                    }
                    val c = gatt.getCharacteristic(service, characteristic)
                    if (c == null) {
                        Handler(Looper.getMainLooper()).post {
                            result.error(
                                "IllegalArgument",
                                "Unknown characteristic: $characteristic",
                                null
                            )
                        }
                        return
                    }
                    val setted = gatt.setNotifiable(c, bleInputProperty)
                    if (setted) {
                        notificationCondition.await()
                        Handler(Looper.getMainLooper()).post {
                            if (connected) {
                                result.success(null)
                            } else {
                                result.error("Device disconnected", null, null)
                            }
                        }
                    } else {
                        Handler(Looper.getMainLooper()).post {
                            result.error(
                                "Characteristic unavailable",
                                null,
                                null
                            );
                        }
                    }
                }
            }

            "readValue" -> {
                lock.withLock<Unit> {
                    val deviceId = call.argument<String>("deviceId")!!
                    val service = call.argument<String>("service")!!
                    val characteristic = call.argument<String>("characteristic")!!
                    val gatt = knownGatts.find { it.device.address == deviceId }
                    if (gatt == null) {
                        Handler(Looper.getMainLooper()).post {
                            result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)
                        }
                        return
                    }
                    val c = gatt.getCharacteristic(service, characteristic)
                    if (c == null) {
                        Handler(Looper.getMainLooper()).post {
                            result.error(
                                "IllegalArgument",
                                "Unknown characteristic: $characteristic",
                                null
                            )
                        }
                        return
                    }
                    if (gatt.readCharacteristic(c)) {
                        readCondition.await()
                        Handler(Looper.getMainLooper()).post {
                            if (connected) {
                                result.success(null)
                            } else {
                                result.error("Device disconnected", null, null)
                            }
                        }
                    } else {
                        Handler(Looper.getMainLooper())
                            .post {
                                result.error(
                                    "Characteristic unavailable ${c.uuid}",
                                    null,
                                    null
                                )
                            }
                    }
                }
            }

            "writeValue" -> {
                lock.withLock<Unit> {
                    val deviceId = call.argument<String>("deviceId")!!
                    val service = call.argument<String>("service")!!
                    val characteristic = call.argument<String>("characteristic")!!
                    val value = call.argument<ByteArray>("value")!!
                    val gatt = knownGatts.find { it.device.address == deviceId }
                    if (gatt == null) {
                        Handler(Looper.getMainLooper()).post {
                            result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)
                        }
                        return
                    }
                    val writeResult = gatt.getCharacteristic(service, characteristic)?.let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            // Android 13+ (API 33+)
                            gatt.writeCharacteristic(it, value, it.writeType)
                        } else {
                            // Older Android versions
                            it.value = value
                            gatt.writeCharacteristic(it)
                        }
                    }
                    val writeResultCondition =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            writeResult == BluetoothGatt.GATT_SUCCESS
                        } else {
                            writeResult == true
                        }
                    if (writeResultCondition) {
                        writeCondition.await()
                        Handler(Looper.getMainLooper()).post {
                            if (connected) {
                                result.success(null)
                            } else {
                                result.error("Device disconnected", null, null)
                            }
                        }
                    } else {
                        Handler(Looper.getMainLooper()).post {
                            result.error(
                                "Characteristic unavailable",
                                null,
                                null
                            )
                        }
                    }
                }
            }

            "requestMtu" -> {
                lock.withLock<Unit> {
                    val deviceId = call.argument<String>("deviceId")!!
                    val expectedMtu = call.argument<Int>("expectedMtu")!!
                    val gatt = knownGatts.find { it.device.address == deviceId }
                    if (gatt == null) {
                        Handler(Looper.getMainLooper()).post {
                            result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)
                        }
                        return
                    }
                    gatt.requestMtu(expectedMtu)
                    Handler(Looper.getMainLooper()).post {
                        if (connected) {
                            result.success(null)
                        } else {
                            result.error("Device disconnected", null, null)
                        }
                    }
                }
            }

            else -> {
                result.notImplemented()
            }
        }
    }

    private fun cleanConnection(gatt: BluetoothGatt) {
        knownGatts.removeAll { it.device.address == gatt.device.address }
        gatt.disconnect()
        gatt.close()
        connected = false
        lock.withLock {
            readCondition.signal()
            writeCondition.signal()
            notificationCondition.signal()
        }
    }

    enum class AvailabilityState(val value: Int) {
        unknown(0),
        resetting(1),
        unsupported(2),
        unauthorized(3),
        poweredOff(4),
        poweredOn(5),
    }

    fun BluetoothManager.getAvailabilityState(): AvailabilityState {
        val state = adapter?.state ?: return AvailabilityState.unsupported
        return when (state) {
            BluetoothAdapter.STATE_OFF -> AvailabilityState.poweredOff
            BluetoothAdapter.STATE_ON -> AvailabilityState.poweredOn
            BluetoothAdapter.STATE_TURNING_ON -> AvailabilityState.resetting
            BluetoothAdapter.STATE_TURNING_OFF -> AvailabilityState.resetting
            else -> AvailabilityState.unknown
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                Handler(Looper.getMainLooper()).post {
                    availabilityChangeSink?.success(
                        bluetoothManager.getAvailabilityState().value
                    )
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            Log.v(TAG, "onScanFailed: $errorCode")
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.v(TAG, "onScanResult: $callbackType + $result")
            Handler(Looper.getMainLooper()).post {
                scanResultSink?.success(
                    mapOf<String, Any>(
                        "name" to (result.device.name ?: ""),
                        "deviceId" to result.device.address,
                        "manufacturerDataHead" to (result.manufacturerDataHead ?: byteArrayOf()),
                        "rssi" to result.rssi
                    )
                )
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            Log.v(TAG, "onBatchScanResults: $results")
        }
    }

    private var availabilityChangeSink: EventChannel.EventSink? = null
    private var scanResultSink: EventChannel.EventSink? = null

    override fun onListen(args: Any?, eventSink: EventChannel.EventSink?) {
        val map = args as? Map<String, Any> ?: return
        when (map["name"]) {
            "availabilityChange" -> {
                availabilityChangeSink = eventSink
                Handler(Looper.getMainLooper()).post {
                    availabilityChangeSink?.success(bluetoothManager.getAvailabilityState().value)
                }
            }

            "scanResult" -> scanResultSink = eventSink
        }
    }

    override fun onCancel(args: Any?) {
        val map = args as? Map<String, Any> ?: return
        when (map["name"]) {
            "availabilityChange" -> availabilityChangeSink = null
            "scanResult" -> scanResultSink = null
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.v(
                TAG,
                "onConnectionStateChange: device(${gatt.device.address}) status($status), newState($newState)"
            )
            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                sendMessage(
                    messageConnector, mapOf(
                        "deviceId" to gatt.device.address,
                        "ConnectionState" to "connected"
                    )
                )
            } else {
                cleanConnection(gatt)
                sendMessage(
                    messageConnector, mapOf(
                        "deviceId" to gatt.device.address,
                        "ConnectionState" to "disconnected"
                    )
                )
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.v(TAG, "onServicesDiscovered ${gatt.device.address} $status")
            if (status != BluetoothGatt.GATT_SUCCESS) return

            gatt.services?.forEach { service ->
                Log.v(TAG, "Service " + service.uuid)
                service.characteristics.forEach { characteristic ->
                    Log.v(TAG, "    Characteristic ${characteristic.uuid}")
                    characteristic.descriptors.forEach {
                        Log.v(TAG, "        Descriptor ${it.uuid}")
                    }
                }

                sendMessage(
                    messageConnector, mapOf(
                        "deviceId" to gatt.device.address,
                        "ServiceState" to "discovered",
                        "service" to service.uuid.toString(),
                        "characteristics" to service.characteristics.map { it.uuid.toString() }
                    ))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            sendMessage(
                messageConnector, mapOf(
                    "mtuConfig" to mtu
                )
            )
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Log.v(
                    TAG,
                    "onCharacteristicRead ${characteristic.uuid}, ${characteristic.value.contentToString()}"
                )
                sendMessage(
                    messageConnector, mapOf(
                        "deviceId" to gatt.device.address,
                        "characteristicValue" to mapOf(
                            "characteristic" to characteristic.uuid.toString(),
                            "value" to characteristic.value
                        )
                    )
                )
                lock.withLock {
                    readCondition.signal()
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            Log.v(
                TAG,
                "onCharacteristicRead ${characteristic.uuid}, ${value.contentToString()}"
            )
            sendMessage(
                messageConnector, mapOf(
                    "deviceId" to gatt.device.address,
                    "characteristicValue" to mapOf(
                        "characteristic" to characteristic.uuid.toString(),
                        "value" to value
                    )
                )
            )
            lock.withLock {
                readCondition.signal()
            }
            super.onCharacteristicRead(gatt, characteristic, value, status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.v(
                TAG,
                "onCharacteristicWrite ${characteristic.uuid}} $status"
            )
            lock.withLock {
                writeCondition.signal()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Log.v(
                    TAG,
                    "onCharacteristicChanged ${characteristic.uuid}, ${characteristic.value.contentToString()}"
                )
                sendMessage(
                    messageConnector, mapOf(
                        "deviceId" to gatt.device.address,
                        "characteristicValue" to mapOf(
                            "characteristic" to characteristic.uuid.toString(),
                            "value" to characteristic.value
                        )
                    )
                )
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.v(
                TAG,
                "onCharacteristicChanged ${characteristic.uuid}, ${value.contentToString()}"
            )
            sendMessage(
                messageConnector, mapOf(
                    "deviceId" to gatt.device.address,
                    "characteristicValue" to mapOf(
                        "characteristic" to characteristic.uuid.toString(),
                        "value" to value
                    )
                )
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (descriptor?.uuid == DESC__CLIENT_CHAR_CONFIGURATION) {
                lock.withLock {
                    notificationCondition.signal()
                }
            }
            super.onDescriptorWrite(gatt, descriptor, status)
        }
    }
}

val ScanResult.manufacturerDataHead: ByteArray?
    get() {
        val sparseArray = scanRecord?.manufacturerSpecificData ?: return null
        if (sparseArray.isEmpty()) return null

        return sparseArray.keyAt(0).toShort().toByteArray() + sparseArray.valueAt(0)
    }

fun Short.toByteArray(byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN): ByteArray =
    ByteBuffer.allocate(2 /*Short.SIZE_BYTES*/).order(byteOrder).putShort(this).array()

fun BluetoothGatt.getCharacteristic(
    service: String,
    characteristic: String
): BluetoothGattCharacteristic? =
    getService(UUID.fromString(service)).getCharacteristic(UUID.fromString(characteristic))

private val DESC__CLIENT_CHAR_CONFIGURATION =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

fun BluetoothGatt.setNotifiable(
    gattCharacteristic: BluetoothGattCharacteristic,
    bleInputProperty: String
): Boolean {
    if (gattCharacteristic.descriptors.none { it.uuid == DESC__CLIENT_CHAR_CONFIGURATION }) {
        gattCharacteristic.addDescriptor(
            BluetoothGattDescriptor(
                DESC__CLIENT_CHAR_CONFIGURATION,
                PERMISSION_WRITE or PERMISSION_READ
            )
        )
    }
    val descriptor =
        gattCharacteristic.getDescriptor(DESC__CLIENT_CHAR_CONFIGURATION)

    val (value, enable) = when (bleInputProperty) {
        "notification" -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE to true
        "indication" -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE to true
        else -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE to false
    }

    try {
        // Handle different API levels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+)
            writeDescriptor(descriptor, value)
        } else {
            // Older Android versions
            descriptor.value = value
            writeDescriptor(descriptor)
        }
        return setCharacteristicNotification(descriptor.characteristic, enable)
    } catch (e: SecurityException) {
        Log.w("BLE", "Missing BLUETOOTH_CONNECT permission", e)
        return false
    } catch (e: Exception) {
        Log.e("BLE", "Failed to set notification: ${e.message}", e)
        return false
    }
}
