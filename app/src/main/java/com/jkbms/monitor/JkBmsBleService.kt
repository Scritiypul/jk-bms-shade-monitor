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
import android.content.pm.ServiceInfo
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
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLSocketFactory

@SuppressLint("MissingPermission")
class JkBmsBleService : Service() {

    companion object {
        const val ACTION_START = "com.jkbms.monitor.ACTION_START"
        const val ACTION_PAUSE_FOR_JK_APP = "com.jkbms.monitor.ACTION_PAUSE_FOR_JK_APP"
        const val ACTION_RESUME = "com.jkbms.monitor.ACTION_RESUME"
        const val ACTION_STOP = "com.jkbms.monitor.ACTION_STOP"
        const val ACTION_RECONNECT = "com.jkbms.monitor.ACTION_RECONNECT"
        const val ACTION_UPDATE_SHADE = "com.jkbms.monitor.ACTION_UPDATE_SHADE"

        private const val CHANNEL_ID_MONITOR = "jk_bms_live_shade"
        private const val CHANNEL_ID_ALERTS = "jk_bms_alerts"
        private const val NOTIF_ID_MONITOR = 1001
        private const val NOTIF_ID_ALERT = 1002

        const val MQTT_BROKER_HOST = "broker.emqx.io"
        const val MQTT_BROKER_PORT_TCP = 1883
        const val MQTT_BROKER_PORT_TLS = 8883

        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHAR_NOTIFY_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val CHAR_WRITE_ALT_UUID: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val _stateFlow = MutableStateFlow(JkBmsState())
        val stateFlow: StateFlow<JkBmsState> = _stateFlow

        @Volatile
        private var activeInstance: JkBmsBleService? = null

        fun getLocalWifiIp(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
                while (interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: ""
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                return ip
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return ""
        }

        /**
         * Отправка команды управления в BMS (0x1D Заряд, 0x1E Разряд, 0x1F Балансир):
         * 1. Если включен прямой Bluetooth BLE к BMS — шлет команду напрямую по BLE.
         * 2. Если телефон дома в Wi-Fi — шлет HTTP GET на http://192.168.123.105/control и проверяет JSON-ответ {"ok":true}.
         *    (Если ESP32 отвечает редиректом на /, предупреждает, что в ESP32 еще не залита прошивка v2.0!).
         * 3. Если вне дома (мобильный интернет 4G/5G или чужой Wi-Fi) — шлет через MQTT с авто-переключением
         *    на защищенный порт TLS 8883 (который не блокируется мобильными операторами).
         */
        suspend fun sendGatewayControlCommand(
            context: Context,
            cmd: String,
            value: Int,
            pin: String
        ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
            val prefs = AppPrefs(context)
            val host = prefs.esp32Host.ifBlank { "192.168.123.105" }
            val topicId = prefs.cloudTopicId.ifBlank { "c84780550485" }

            // 0. Если пользователь нажал кнопку освобождения BLE для официального JK App —
            // на всякий случай отключаем и прямой BLE на самом телефоне, если он был включен
            val svc = activeInstance
            if (cmd == "ble" && value == 0) {
                svc?.disconnectGatt()
            }

            // Если активно прямое BLE-соединение с телефона на BMS
            if (prefs.useDirectBle && svc != null && svc.isGattConnected) {
                val regByte: Byte? = when (cmd) {
                    "chg" -> 0x1D.toByte()
                    "dsg" -> 0x1E.toByte()
                    "bal" -> 0x1F.toByte()
                    else -> null
                }
                if (regByte != null) {
                    svc.sendBleControlRegister(regByte, value)
                    return@withContext Pair(true, "✅ Команда отправлена напрямую по Bluetooth BLE")
                }
            }

            var localHttpOk = false
            var localHttpError = ""
            var espOldFirmwareDetected = false

            // 1. Попытка через локальный HTTP (быстро, если телефон в домашнем Wi-Fi)
            try {
                val encPin = URLEncoder.encode(pin, "UTF-8")
                val url = URL("http://$host/control?pin=$encPin&cmd=$cmd&val=$value")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 1800
                    readTimeout = 1800
                    requestMethod = "GET"
                }
                val code = conn.responseCode
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    if (body.contains("\"ok\":true")) {
                        localHttpOk = true
                    } else {
                        espOldFirmwareDetected = true
                    }
                } else if (code == 403) {
                    localHttpError = "❌ Неверный PIN-пароль управления!"
                } else if (code == 302 || code == 404) {
                    // В прошивке v1.0 неизвестный путь /control возвращает 302 Redirect на /
                    espOldFirmwareDetected = true
                }
                conn.disconnect()
            } catch (_: Exception) {
            }

            if (localHttpError.isNotEmpty()) {
                return@withContext Pair(false, localHttpError)
            }

            if (localHttpOk) {
                // Оптимистично обновим UI сразу
                svc?.applyOptimisticControl(cmd, value)
                return@withContext Pair(true, "✅ Выполнено по домашнему Wi-Fi ($host)")
            }

            if (espOldFirmwareDetected) {
                return@withContext Pair(
                    false,
                    "⚠️ На вашей ESP32 ($host) еще залита старая прошивка v1.0 (без управления и облака)! Прошейте обновленный скетч ESP32_JK_BMS_Gateway.ino через Arduino IDE."
                )
            }

            // 2. Отправка через Облако (MQTT по TLS 8883 + TCP 1883 для мобильного интернета 4G/5G)
            val cloudOk = publishMqttCommand("jkbms/$topicId/cmd", "$pin:$cmd:$value")
            if (cloudOk) {
                svc?.applyOptimisticControl(cmd, value)
                Pair(true, "🌍 Команда доставлена через Интернет (Облако)")
            } else {
                Pair(false, "❌ Нет связи со шлюзом (проверьте мобильный интернет или Wi-Fi)")
            }
        }

        private fun encodeMqttLength(out: ByteArrayOutputStream, length: Int) {
            var rem = length
            do {
                var digit = rem % 128
                rem /= 128
                if (rem > 0) digit = digit or 0x80
                out.write(digit)
            } while (rem > 0)
        }

        private fun readFullyStatic(inp: InputStream, buf: ByteArray, len: Int): Boolean {
            var total = 0
            while (total < len) {
                val r = inp.read(buf, total, len - total)
                if (r <= 0) return false
                total += r
            }
            return true
        }

        /**
         * Открывает соединение с брокером MQTT:
         * Пробует сначала TLS (порт 8883 — никогда не блокируется мобильными операторами 4G/5G),
         * а затем обычный TCP (порт 1883).
         */
        private fun openBrokerSocket(timeoutMs: Int): Socket? {
            // 1. Попытка через TLS 8883 (проходит через любых мобильных операторов)
            try {
                val rawSock = Socket()
                rawSock.connect(InetSocketAddress(MQTT_BROKER_HOST, MQTT_BROKER_PORT_TLS), timeoutMs)
                rawSock.soTimeout = timeoutMs
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sslSock = sslFactory.createSocket(rawSock, MQTT_BROKER_HOST, MQTT_BROKER_PORT_TLS, true)
                sslSock.soTimeout = timeoutMs
                return sslSock
            } catch (_: Exception) {
            }

            // 2. Фолбэк на обычный TCP 1883
            return try {
                val sock = Socket()
                sock.connect(InetSocketAddress(MQTT_BROKER_HOST, MQTT_BROKER_PORT_TCP), timeoutMs)
                sock.soTimeout = timeoutMs
                sock
            } catch (_: Exception) {
                null
            }
        }

        private fun publishMqttCommand(topic: String, message: String): Boolean {
            var sock: Socket? = null
            return try {
                sock = openBrokerSocket(4500) ?: return false
                val out = sock.getOutputStream()
                val inp = sock.getInputStream()

                val clientId = "jk-cmd-" + (System.currentTimeMillis() % 1000000)
                val idBytes = clientId.toByteArray(Charsets.UTF_8)
                val connectBody = ByteArrayOutputStream()
                connectBody.write(
                    byteArrayOf(
                        0x00, 0x04,
                        'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(),
                        0x04, 0x02, 0x00, 20
                    )
                )
                connectBody.write((idBytes.size shr 8) and 0xFF)
                connectBody.write(idBytes.size and 0xFF)
                connectBody.write(idBytes)

                val connectPkt = ByteArrayOutputStream()
                connectPkt.write(0x10)
                encodeMqttLength(connectPkt, connectBody.size())
                connectPkt.write(connectBody.toByteArray())
                out.write(connectPkt.toByteArray())
                out.flush()

                val connack = ByteArray(4)
                if (!readFullyStatic(inp, connack, 4) || connack[0] != 0x20.toByte() || connack[3] != 0x00.toByte()) {
                    return false
                }

                // Публикуем с QoS 1 (0x32), чтобы дождаться PUBACK от брокера перед закрытием сокета!
                val topBytes = topic.toByteArray(Charsets.UTF_8)
                val msgBytes = message.toByteArray(Charsets.UTF_8)
                val pubBody = ByteArrayOutputStream()
                pubBody.write((topBytes.size shr 8) and 0xFF)
                pubBody.write(topBytes.size and 0xFF)
                pubBody.write(topBytes)
                pubBody.write(0x00) // Packet ID MSB
                pubBody.write(0x07) // Packet ID LSB = 7
                pubBody.write(msgBytes)

                val pubPkt = ByteArrayOutputStream()
                pubPkt.write(0x32) // PUBLISH QoS 1
                encodeMqttLength(pubPkt, pubBody.size())
                pubPkt.write(pubBody.toByteArray())
                out.write(pubPkt.toByteArray())
                out.flush()

                // Читаем 4 байта PUBACK (0x40 0x02 0x00 0x07)
                val puback = ByteArray(4)
                val ackOk = readFullyStatic(inp, puback, 4) && (puback[0].toInt() and 0xF0) == 0x40

                // Корректный DISCONNECT
                out.write(byteArrayOf(0xE0.toByte(), 0x00))
                out.flush()
                ackOk
            } catch (_: Exception) {
                false
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var localPollJob: Job? = null
    private var cloudMqttJob: Job? = null
    private var blePollJob: Job? = null

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var isGattConnected = false
    @Volatile
    private var lastConnectAttemptMillis = 0L
    @Volatile
    private var lastLocalWifiSuccessMillis = 0L
    @Volatile
    private var lastControlActionMillis = 0L
    private var lastNotificationUpdateMillis = 0L

    private val rxBuffer = ByteArrayOutputStream()
    private var lastAlertTimeMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannels()
        syncShadeVisibility(_stateFlow.value)
        startHybridGatewayLoops()
        startBlePollingLoop()
    }

    private fun syncShadeVisibility(state: JkBmsState) {
        val prefs = AppPrefs(this)
        if (prefs.shadeNotificationEnabled) {
            promoteToForegroundSafely(state)
        } else {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) {
            }
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF_ID_MONITOR)
            } catch (_: Throwable) {
            }
        }
    }

    private fun promoteToForegroundSafely(state: JkBmsState) {
        try {
            val notif = buildShadeNotification(state)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID_MONITOR,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID_MONITOR, notif)
            }
        } catch (_: Throwable) {
            try {
                startForeground(NOTIF_ID_MONITOR, buildShadeNotification(state))
            } catch (_: Throwable) {
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_SHADE -> {
                syncShadeVisibility(_stateFlow.value)
            }
            ACTION_PAUSE_FOR_JK_APP -> {
                pauseConnectionForOfficialApp()
            }
            ACTION_RESUME, ACTION_START -> {
                syncShadeVisibility(_stateFlow.value)
                val prefs = AppPrefs(this)
                if (prefs.useDirectBle && prefs.targetMacAddress.isNotBlank()) {
                    resumeAndConnect(forceReconnect = false)
                }
            }
            ACTION_RECONNECT -> {
                syncShadeVisibility(_stateFlow.value)
                val prefs = AppPrefs(this)
                if (prefs.useDirectBle && prefs.targetMacAddress.isNotBlank()) {
                    resumeAndConnect(forceReconnect = true)
                }
            }
            ACTION_STOP -> {
                disconnectGatt()
                try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    fun applyOptimisticControl(cmd: String, value: Int) {
        lastControlActionMillis = System.currentTimeMillis()
        val cur = _stateFlow.value
        val enabled = value != 0
        val updated = when (cmd) {
            "chg" -> cur.copy(chargeMosEnabled = enabled)
            "dsg" -> cur.copy(dischargeMosEnabled = enabled)
            "bal" -> cur.copy(balanceEnabled = enabled)
            "ble" -> {
                val isPaused = (value == 0)
                cur.copy(
                    connected = if (isPaused) false else cur.connected,
                    paused = isPaused,
                    blePausedOnEsp = isPaused,
                    statusText = if (isPaused) {
                        "⏸ BLE платы отключен (свободно для офиц. JK App)"
                    } else {
                        "🔄 Возобновление связи ESP32 с BMS..."
                    }
                )
            }
            else -> cur
        }
        updateState(updated)
    }

    private fun updateStateFromGateway(parsed: JkBmsState) {
        val now = System.currentTimeMillis()
        val cur = _stateFlow.value
        // В течение 4.5 сек после ручного переключения тумблера не даем старым пакетам вернуть тумблер назад
        val merged = if (now - lastControlActionMillis < 4500L) {
            parsed.copy(
                chargeMosEnabled = cur.chargeMosEnabled,
                dischargeMosEnabled = cur.dischargeMosEnabled,
                balanceEnabled = cur.balanceEnabled,
                paused = cur.paused,
                blePausedOnEsp = cur.blePausedOnEsp,
                connected = if (cur.blePausedOnEsp) false else parsed.connected,
                statusText = if (cur.blePausedOnEsp) {
                    "⏸ BLE платы отключен (свободно для офиц. JK App)"
                } else {
                    parsed.statusText
                }
            )
        } else {
            parsed
        }
        updateState(merged)
    }

    fun sendBleControlRegister(reg: Byte, value: Int) {
        serviceScope.launch {
            sendCommand(JkBmsParser.buildControlCommand(reg, value))
            delay(350)
            sendCommand(JkBmsParser.buildCellInfoCommand())
            val cmdName = when (reg) {
                0x1D.toByte() -> "chg"
                0x1E.toByte() -> "dsg"
                0x1F.toByte() -> "bal"
                else -> ""
            }
            if (cmdName.isNotEmpty()) {
                applyOptimisticControl(cmdName, value)
            }
        }
    }

    /**
     * Гибридный движок мониторинга:
     * 1. Быстрый локальный опрос ESP32 по домашнему Wi-Fi (http://192.168.123.105/status)
     * 2. Постоянная облачная подписка MQTT (TLS 8883 + TCP 1883) для работы из любой точки мира (4G/5G/чужой Wi-Fi)
     */
    private fun startHybridGatewayLoops() {
        localPollJob?.cancel()
        localPollJob = serviceScope.launch {
            while (isActive) {
                val prefs = AppPrefs(this@JkBmsBleService)
                if (!prefs.useDirectBle) {
                    val host = prefs.esp32Host.ifBlank { "192.168.123.105" }
                    try {
                        val url = URL("http://$host/status")
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 1400
                            readTimeout = 1400
                            requestMethod = "GET"
                            useCaches = false
                        }
                        if (conn.responseCode == 200) {
                            val json = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            val parsed = JkBmsParser.parseGatewayJson(json, "🏠 Дома Wi-Fi ($host)")
                            if (parsed != null) {
                                lastLocalWifiSuccessMillis = System.currentTimeMillis()
                                updateStateFromGateway(parsed)
                                checkOptionalAlerts(parsed)
                            }
                        }
                        conn.disconnect()
                    } catch (_: Exception) {
                    }
                }
                delay(2000)
            }
        }

        cloudMqttJob?.cancel()
        cloudMqttJob = serviceScope.launch {
            while (isActive) {
                val prefs = AppPrefs(this@JkBmsBleService)
                if (prefs.useDirectBle) {
                    delay(3000)
                    continue
                }

                val topicId = prefs.cloudTopicId.ifBlank { "c84780550485" }
                val stateTopic = "jkbms/$topicId/status"
                var sock: Socket? = null

                try {
                    sock = openBrokerSocket(5000)
                    if (sock == null) {
                        delay(4000)
                        continue
                    }
                    sock.soTimeout = 25000
                    val out = sock.getOutputStream()
                    val inp = sock.getInputStream()

                    val clientId = "jk-sub-" + (System.currentTimeMillis() % 1000000)
                    val idBytes = clientId.toByteArray(Charsets.UTF_8)
                    val connectBody = ByteArrayOutputStream()
                    connectBody.write(
                        byteArrayOf(
                            0x00, 0x04,
                            'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(),
                            0x04, 0x02, 0x00, 30
                        )
                    )
                    connectBody.write((idBytes.size shr 8) and 0xFF)
                    connectBody.write(idBytes.size and 0xFF)
                    connectBody.write(idBytes)

                    val connectPkt = ByteArrayOutputStream()
                    connectPkt.write(0x10)
                    encodeMqttLength(connectPkt, connectBody.size())
                    connectPkt.write(connectBody.toByteArray())
                    out.write(connectPkt.toByteArray())
                    out.flush()

                    val connack = ByteArray(4)
                    if (readFully(inp, connack, 4) && connack[0] == 0x20.toByte() && connack[3] == 0x00.toByte()) {
                        val topBytes = stateTopic.toByteArray(Charsets.UTF_8)
                        val subBody = ByteArrayOutputStream()
                        subBody.write(0x00)
                        subBody.write(0x01)
                        subBody.write((topBytes.size shr 8) and 0xFF)
                        subBody.write(topBytes.size and 0xFF)
                        subBody.write(topBytes)
                        subBody.write(0x00)

                        val subPkt = ByteArrayOutputStream()
                        subPkt.write(0x82)
                        encodeMqttLength(subPkt, subBody.size())
                        subPkt.write(subBody.toByteArray())
                        out.write(subPkt.toByteArray())
                        out.flush()

                        var lastPingMs = System.currentTimeMillis()
                        while (isActive && !prefs.useDirectBle) {
                            val now = System.currentTimeMillis()
                            if (now - lastPingMs > 18000L) {
                                out.write(byteArrayOf(0xC0.toByte(), 0x00)) // PINGREQ
                                out.flush()
                                lastPingMs = now
                            }

                            val hdr = inp.read()
                            if (hdr == -1) break
                            val remLen = readMqttRemainingLength(inp)
                            if (remLen < 0 || remLen > 16384) break

                            val payload = ByteArray(remLen)
                            if (!readFully(inp, payload, remLen)) break

                            // Если пришел PUBLISH пакет (0x30..0x3F)
                            if ((hdr and 0xF0) == 0x30 && remLen > 4) {
                                val tLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                                var payStart = 2 + tLen
                                if ((hdr and 0x06) != 0) payStart += 2
                                if (payStart < remLen) {
                                    val jsonStr = String(payload, payStart, remLen - payStart, Charsets.UTF_8)
                                    // Если локальный Wi-Fi сейчас недоступен (> 3.5 сек), берем данные из Облака!
                                    if (System.currentTimeMillis() - lastLocalWifiSuccessMillis > 3500L) {
                                        val parsed = JkBmsParser.parseGatewayJson(jsonStr, "🌍 Интернет (Облако)")
                                        if (parsed != null) {
                                            updateStateFromGateway(parsed)
                                            checkOptionalAlerts(parsed)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { sock?.close() } catch (_: Exception) {}
                }
                delay(4000)
            }
        }
    }

    private fun readMqttRemainingLength(inp: InputStream): Int {
        var multiplier = 1
        var value = 0
        var encodedByte: Int
        do {
            encodedByte = inp.read()
            if (encodedByte == -1) return -1
            value += (encodedByte and 127) * multiplier
            multiplier *= 128
            if (multiplier > 128 * 128 * 128) return -1
        } while ((encodedByte and 128) != 0)
        return value
    }

    private fun readFully(inp: InputStream, buf: ByteArray, len: Int): Boolean =
        readFullyStatic(inp, buf, len)

    private fun pauseConnectionForOfficialApp() {
        disconnectGatt()
        updateState(
            _stateFlow.value.copy(
                connected = false,
                paused = true,
                statusText = "На паузе"
            )
        )
    }

    private fun resumeAndConnect(forceReconnect: Boolean) {
        val prefs = AppPrefs(this)
        val mac = prefs.targetMacAddress
        val name = prefs.targetDeviceName
        if (mac.isBlank()) return

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
                statusText = "Подключение по BLE к $name..."
            )
        )
        connectToDevice(mac)
    }

    private fun startBlePollingLoop() {
        blePollJob?.cancel()
        blePollJob = serviceScope.launch {
            while (isActive) {
                val st = _stateFlow.value
                val prefs = AppPrefs(this@JkBmsBleService)
                val now = System.currentTimeMillis()
                if (prefs.useDirectBle && !st.paused && prefs.targetMacAddress.isNotBlank()) {
                    if (bluetoothGatt == null || (!isGattConnected && now - lastConnectAttemptMillis > 10_000L)) {
                        connectToDevice(prefs.targetMacAddress)
                    } else if (isGattConnected &&
                        st.lastUpdatedMillis > 0 &&
                        now - st.lastUpdatedMillis > 25_000L &&
                        now - lastConnectAttemptMillis > 25_000L
                    ) {
                        connectToDevice(prefs.targetMacAddress)
                    }
                } else if (!prefs.useDirectBle && bluetoothGatt != null) {
                    disconnectGatt()
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
            if (!adapter.isEnabled) return

            disconnectGatt()
            lastConnectAttemptMillis = System.currentTimeMillis()
            val device: BluetoothDevice = adapter.getRemoteDevice(macAddress)
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(this, false, gattCallback)
            }
        } catch (_: Exception) {
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
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isGattConnected = false
                try { gatt.close() } catch (_: Exception) {}
                if (bluetoothGatt === gatt) bluetoothGatt = null
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
                serviceScope.launch {
                    delay(250)
                    sendCommand(JkBmsParser.buildDeviceInfoCommand())
                    delay(350)
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
        val prefs = AppPrefs(this)
        if (!prefs.shadeNotificationEnabled) return

        val now = System.currentTimeMillis()
        val statusChanged = prev.connected != newState.connected ||
            prev.paused != newState.paused ||
            prev.statusText != newState.statusText
        if (statusChanged || now - lastNotificationUpdateMillis >= 1500L) {
            lastNotificationUpdateMillis = now
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID_MONITOR, buildShadeNotification(newState))
            } catch (_: Throwable) {
            }
        }
    }

    private fun checkOptionalAlerts(state: JkBmsState) {
        val prefs = AppPrefs(this)
        if (!prefs.alertsEnabled || !state.connected) return

        val now = System.currentTimeMillis()
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
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val alertNotif = NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("⚠️ Внимание: JK BMS")
                    .setContentText(reason)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
                nm.notify(NOTIF_ID_ALERT, alertNotif)
            } catch (_: Throwable) {
            }
        }
    }

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
            title = "⏸ JK BMS: Мониторинг на паузе"
            shortLine = "Нажмите, чтобы открыть дашборд"
            expandedLines.append(shortLine)
        } else if (!state.connected && state.lastUpdatedMillis == 0L) {
            title = "🔄 JK BMS: ${state.statusText}"
            shortLine = "Ожидание данных от ESP32 (Wi-Fi / Облако)..."
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
                chargeW > 5 -> "⚡ Заряд +$chargeW Вт"
                dischargeW > 5 -> "🔻 Расход -$dischargeW Вт"
                else -> "💤 Покой (0 Вт)"
            }

            title = "🔋 ${state.socPercent}%  |  $modeIcon  |  🌡 ${t1}°C"
            shortLine = "${state.connectionSource}  •  $voltStr В ($ampStr А)  •  ${state.getEstimatedTimeText()}"

            expandedLines.append("📡 Канал связи: ${state.connectionSource}\n")
            expandedLines.append("⚡ Зарядка: $chargeW Вт   |   🔻 Расход: $dischargeW Вт\n")
            expandedLines.append("🔋 Емкость: ${String.format(Locale.US, "%.1f", state.remainingCapacityAh)} / ${String.format(Locale.US, "%.0f", state.nominalCapacityAh)} А·ч (~${state.remainingEnergyWh.toInt()} Вт·ч) • ${state.getEstimatedTimeText()}\n")
            expandedLines.append("🌡 Температура: T1: ${t1}°C  |  T2: ${t2}°C  |  MOS: ${tMos}°C\n")
            expandedLines.append("📊 Сеть: $voltStr В ($ampStr А)  •  Разбег ячеек Δ: ${state.cellDeltaMv} мВ")
        }

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
                description = "Постоянная сводка заряда, мощности и температуры JK BMS"
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
        if (activeInstance === this) activeInstance = null
        localPollJob?.cancel()
        cloudMqttJob?.cancel()
        blePollJob?.cancel()
        serviceScope.cancel()
        disconnectGatt()
        super.onDestroy()
    }
}
