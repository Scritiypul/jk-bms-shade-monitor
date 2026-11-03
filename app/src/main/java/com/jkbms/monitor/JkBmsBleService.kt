package com.jkbms.monitor

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

@SuppressLint("MissingPermission")
class JkBmsBleService : Service() {

    companion object {
        const val ACTION_START = "com.jkbms.monitor.ACTION_START"
        const val ACTION_PAUSE_FOR_JK_APP = "com.jkbms.monitor.ACTION_PAUSE_FOR_JK_APP"
        const val ACTION_RESUME = "com.jkbms.monitor.ACTION_RESUME"
        const val ACTION_STOP = "com.jkbms.monitor.ACTION_STOP"
        const val ACTION_RECONNECT = "com.jkbms.monitor.ACTION_RECONNECT"

        private const val CHANNEL_ID_MONITOR = "jk_bms_live_shade"
        private const val CHANNEL_ID_ALERTS = "jk_bms_alerts"
        private const val NOTIF_ID_MONITOR = 1001
        private const val NOTIF_ID_ALERT = 1002

        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHAR_NOTIFY_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val CHAR_WRITE_ALT_UUID: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val _stateFlow = MutableStateFlow(JkBmsState())
        val stateFlow: StateFlow<JkBmsState> = _stateFlow
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var isGattConnected = false
    @Volatile
    private var lastConnectAttemptMillis = 0L
    private var lastNotificationUpdateMillis = 0L

    private val rxBuffer = ByteArrayOutputStream()
    private var lastAlertTimeMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIF_ID_MONITOR, buildShadeNotification(_stateFlow.value))
        startPollingLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_FOR_JK_APP -> {
                pauseConnectionForOfficialApp()
            }
            ACTION_RESUME, ACTION_START -> {
                resumeAndConnect(forceReconnect = false)
            }
            ACTION_RECONNECT -> {
                resumeAndConnect(forceReconnect = true)
            }
            ACTION_STOP -> {
                disconnectGatt()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun pauseConnectionForOfficialApp() {
        disconnectGatt()
        updateState(
            _stateFlow.value.copy(
                connected = false,
                paused = true,
                statusText = "На паузе (Bluetooth свободен для JK BMS)"
            )
        )
    }

    private fun resumeAndConnect(forceReconnect: Boolean) {
        val prefs = AppPrefs(this)
        val mac = prefs.targetMacAddress
        val name = prefs.targetDeviceName
        if (mac.isBlank()) {
            updateState(
                _stateFlow.value.copy(
                    connected = false,
                    paused = false,
                    statusText = "Выберите вашу JK BMS в приложении"
                )
            )
            return
        }

        val current = _stateFlow.value
        val alreadyConnectedToSame = isGattConnected &&
            bluetoothGatt != null &&
            !current.paused &&
            current.deviceAddress == mac

        if (alreadyConnectedToSame && !forceReconnect) {
            return
        }

        updateState(
            current.copy(
                paused = false,
                deviceName = name,
                deviceAddress = mac,
                statusText = if (current.connected && !forceReconnect) "Подключено" else "Подключение к $name..."
            )
        )
        connectToDevice(mac)
    }

    private fun startPollingLoop() {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            while (isActive) {
                val st = _stateFlow.value
                val prefs = AppPrefs(this@JkBmsBleService)
                val now = System.currentTimeMillis()
                if (!st.paused && prefs.targetMacAddress.isNotBlank()) {
                    if (bluetoothGatt == null || (!isGattConnected && now - lastConnectAttemptMillis > 10_000L)) {
                        connectToDevice(prefs.targetMacAddress)
                    } else if (isGattConnected &&
                        st.lastUpdatedMillis > 0 &&
                        now - st.lastUpdatedMillis > 25_000L &&
                        now - lastConnectAttemptMillis > 25_000L
                    ) {
                        // Переподключаемся только если поток уведомлений от BMS полностью завис (>25 сек)
                        connectToDevice(prefs.targetMacAddress)
                    }
                }
                delay(5000)
            }
        }
    }

    @Synchronized
    private fun connectToDevice(macAddress: String) {
        try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter: BluetoothAdapter = btManager.adapter ?: return
            if (!adapter.isEnabled) {
                updateState(_stateFlow.value.copy(connected = false, statusText = "Включите Bluetooth"))
                return
            }

            disconnectGatt()
            lastConnectAttemptMillis = System.currentTimeMillis()
            val device: BluetoothDevice = adapter.getRemoteDevice(macAddress)
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(this, false, gattCallback)
            }
        } catch (e: Exception) {
            updateState(_stateFlow.value.copy(connected = false, statusText = "Ошибка BLE: ${e.message}"))
        }
    }

    @Synchronized
    private fun disconnectGatt() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {
        } finally {
            isGattConnected = false
            bluetoothGatt = null
            writeCharacteristic = null
            rxBuffer.reset()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isGattConnected = true
                lastConnectAttemptMillis = System.currentTimeMillis()
                updateState(_stateFlow.value.copy(statusText = "Обнаружение сервисов JK BMS..."))
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isGattConnected = false
                try {
                    gatt.close()
                } catch (_: Exception) {
                }
                if (bluetoothGatt === gatt) {
                    bluetoothGatt = null
                }
                val isPaused = _stateFlow.value.paused
                updateState(
                    _stateFlow.value.copy(
                        connected = false,
                        statusText = if (isPaused) {
                            "На паузе (Bluetooth свободен для JK BMS)"
                        } else {
                            "Ожидание связи с JK BMS..."
                        }
                    )
                )
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(SERVICE_UUID) ?: return
            val notifyChar = service.getCharacteristic(CHAR_NOTIFY_UUID) ?: return
            val writeChar = service.getCharacteristic(CHAR_WRITE_ALT_UUID) ?: notifyChar
            writeCharacteristic = writeChar

            gatt.setCharacteristicNotification(notifyChar, true)
            val descriptor = notifyChar.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            } else {
                // Если дескриптора нет, сразу инициализируем поток
                serviceScope.launch {
                    delay(300)
                    sendCommand(JkBmsParser.buildDeviceInfoCommand())
                    delay(400)
                    sendCommand(JkBmsParser.buildCellInfoCommand())
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            serviceScope.launch {
                delay(250)
                sendCommand(JkBmsParser.buildDeviceInfoCommand())
                delay(350)
                sendCommand(JkBmsParser.buildCellInfoCommand())
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            handleIncomingBytes(data)
        }
    }

    private fun sendCommand(command: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val char = writeCharacteristic ?: return
        try {
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            char.value = command
            gatt.writeCharacteristic(char)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun handleIncomingBytes(bytes: ByteArray) {
        // Если пришел заголовок нового пакета 55 AA EB 90
        if (bytes.size >= 4 &&
            bytes[0] == 0x55.toByte() &&
            bytes[1] == 0xAA.toByte() &&
            bytes[2] == 0xEB.toByte() &&
            bytes[3] == 0x90.toByte()
        ) {
            processBufferedFrame()
            rxBuffer.reset()
        }

        rxBuffer.write(bytes)

        if (rxBuffer.size() >= 300) {
            processBufferedFrame()
            rxBuffer.reset()
        }
    }

    private fun processBufferedFrame() {
        val raw = rxBuffer.toByteArray()
        if (raw.size < 250) return

        // Находим старт заголовка 55 AA EB 90 внутри буфера
        var startIdx = -1
        for (i in 0..raw.size - 4) {
            if (raw[i] == 0x55.toByte() &&
                raw[i + 1] == 0xAA.toByte() &&
                raw[i + 2] == 0xEB.toByte() &&
                raw[i + 3] == 0x90.toByte()
            ) {
                startIdx = i
                break
            }
        }
        if (startIdx == -1) return

        val slice = raw.copyOfRange(startIdx, raw.size)
        val parsed = JkBmsParser.parseTelemetryFrame(slice, _stateFlow.value)
        if (parsed != null) {
            updateState(parsed)
            checkOptionalAlerts(parsed)
        }
    }

    private fun updateState(newState: JkBmsState) {
        val prev = _stateFlow.value
        _stateFlow.value = newState
        val now = System.currentTimeMillis()
        val statusChanged = prev.connected != newState.connected ||
            prev.paused != newState.paused ||
            prev.statusText != newState.statusText
        if (statusChanged || now - lastNotificationUpdateMillis >= 1500L) {
            lastNotificationUpdateMillis = now
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID_MONITOR, buildShadeNotification(newState))
        }
    }

    private fun checkOptionalAlerts(state: JkBmsState) {
        val prefs = AppPrefs(this)
        if (!prefs.alertsEnabled || !state.connected) return

        val now = System.currentTimeMillis()
        // Не чаще раза в 10 минут
        if (now - lastAlertTimeMillis < 10 * 60 * 1000L) return

        val maxTemp = maxOf(state.tempSensor1, state.tempSensor2, state.tempMos)
        val reason = when {
            state.socPercent in 1..prefs.lowSocAlertThreshold ->
                "Низкий заряд аккумулятора: ${state.socPercent}% (${String.format(Locale.US, "%.2f", state.packVoltage)} В)"
            maxTemp >= prefs.highTempAlertThreshold ->
                "Высокая температура JK BMS: ${String.format(Locale.US, "%.1f", maxTemp)} °C"
            else -> null
        }

        if (reason != null) {
            lastAlertTimeMillis = now
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val alertNotif = NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("⚠️ Внимание: JK BMS")
                .setContentText(reason)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID_ALERT, alertNotif)
        }
    }

    /**
     * Формирует информативное уведомление в шторке Android
     */
    private fun buildShadeNotification(state: JkBmsState): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title: String
        val shortLine: String
        val expandedLines = StringBuilder()

        if (state.paused) {
            title = "⏸ JK BMS: На паузе (канал свободен)"
            shortLine = "Вы можете открыть родное приложение JK BMS"
            expandedLines.append("Нажмите «Возобновить», когда закончите работу в официальном приложении JK BMS.")
        } else if (!state.connected) {
            title = "🔄 JK BMS: ${state.statusText}"
            shortLine = if (state.socPercent > 0) {
                "Посл. данные: 🔋 ${state.socPercent}% | Заряд: ${state.chargeWatts.toInt()} Вт·ч | Расход: ${state.dischargeWatts.toInt()} Вт·ч"
            } else {
                "Ожидание Bluetooth-соединения с B2A8S20P..."
            }
            expandedLines.append(shortLine)
        } else {
            val chargeW = state.chargeWatts.toInt()
            val dischargeW = state.dischargeWatts.toInt()
            val t1 = String.format(Locale.US, "%.1f", state.tempSensor1)
            val t2 = String.format(Locale.US, "%.1f", state.tempSensor2)
            val tMos = String.format(Locale.US, "%.1f", state.tempMos)
            val voltStr = String.format(Locale.US, "%.2f", state.packVoltage)
            val ampStr = String.format(Locale.US, "%+.1f", state.currentAmps)

            val modeIcon = when {
                chargeW > 5 -> "⚡ Заряд +$chargeW Вт·ч"
                dischargeW > 5 -> "🔻 Расход -$dischargeW Вт·ч"
                else -> "💤 Покой (0 Вт·ч)"
            }

            // Компактный заголовок (виден всегда в свернутой шторке и на экране блокировки)
            title = "🔋 ${state.socPercent}%  |  $modeIcon  |  🌡 ${t1}°C"

            // Вторая строка свернутого уведомления — четко по запросу: Зарядка, Расход и Температура
            shortLine = "⚡ Зарядка: $chargeW Вт·ч/ч  •  🔻 Расход: $dischargeW Вт·ч/ч  •  🌡 $t1°C"

            // Развернутый вид (когда тянешь уведомление в шторке вниз)
            expandedLines.append("⚡ Скорость зарядки:  $chargeW Вт·ч (в час)\n")
            expandedLines.append("🔻 Скорость расхода:  $dischargeW Вт·ч (в час)\n")
            expandedLines.append("🌡 Температура:  Датчик 1: ${t1}°C  |  Датчик 2: ${t2}°C  |  MOS: ${tMos}°C\n")
            expandedLines.append("🔋 Запас: ${String.format(Locale.US, "%.1f", state.remainingCapacityAh)} / ${String.format(Locale.US, "%.0f", state.nominalCapacityAh)} А·ч (~${state.remainingEnergyWh.toInt()} Вт·ч) • ${state.getEstimatedTimeText()}\n")
            expandedLines.append("📊 Сеть: $voltStr В (${ampStr} А)  •  Разбег ячеек Δ: ${state.cellDeltaMv} мВ")
        }

        val toggleIntent = Intent(this, JkBmsBleService::class.java).apply {
            action = if (state.paused) ACTION_RESUME else ACTION_PAUSE_FOR_JK_APP
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            1,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val refreshIntent = Intent(this, JkBmsBleService::class.java).apply {
            action = ACTION_RECONNECT
        }
        val refreshPendingIntent = PendingIntent.getService(
            this,
            2,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleLabel = if (state.paused) "▶ Возобновить" else "⏸ Пауза (для JK App)"

        return NotificationCompat.Builder(this, CHANNEL_ID_MONITOR)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(title)
            .setContentText(shortLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedLines.toString()))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent)
            .addAction(0, toggleLabel, togglePendingIntent)
            .addAction(0, "🔄 Обновить", refreshPendingIntent)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val monitorChannel = NotificationChannel(
                CHANNEL_ID_MONITOR,
                "Мониторинг JK BMS в шторке",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постоянная сводка заряда, мощности (Вт·ч) и температуры JK BMS"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "Оповещения JK BMS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Предупреждения о низком заряде или перегреве"
            }

            nm.createNotificationChannel(monitorChannel)
            nm.createNotificationChannel(alertChannel)
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        serviceScope.cancel()
        disconnectGatt()
        super.onDestroy()
    }
}
