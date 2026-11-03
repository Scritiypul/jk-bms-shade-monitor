package com.jkbms.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private var uiJob: Job? = null

    // UI элементы
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvDeviceSubtitle: TextView
    private lateinit var tvSocBig: TextView
    private lateinit var progressSoc: ProgressBar
    private lateinit var tvEstimatedTime: TextView

    private lateinit var tvChargeRateVal: TextView
    private lateinit var tvDischargeRateVal: TextView
    private lateinit var tvTempSensorVal: TextView
    private lateinit var tvTempMosVal: TextView

    private lateinit var tvDetailsPack: TextView
    private lateinit var tvDetailsCells: TextView
    private lateinit var btnPauseResume: Button

    private val discoveredDevices = LinkedHashMap<String, String>() // MAC -> Name
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)

        setContentView(buildDashboardView())
        requestNeededPermissions()

        if (prefs.targetMacAddress.isNotBlank()) {
            startMonitorService(JkBmsBleService.ACTION_START)
        }
    }

    override fun onStart() {
        super.onStart()
        uiJob = CoroutineScope(Dispatchers.Main).launch {
            JkBmsBleService.stateFlow.collect { state ->
                renderState(state)
            }
        }
    }

    override fun onStop() {
        uiJob?.cancel()
        super.onStop()
    }

    private fun buildDashboardView(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(18)
            setPadding(pad, dp(24), pad, pad)
        }
        scroll.addView(root)

        // Заголовок
        val tvTitle = TextView(this).apply {
            text = "JK BMS Шторка-Монитор"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(tvTitle)

        tvDeviceSubtitle = TextView(this).apply {
            val savedName = prefs.targetDeviceName.ifBlank { "Устройство не выбрано" }
            val savedMac = prefs.targetMacAddress
            text = if (savedMac.isNotBlank()) "$savedName ($savedMac)" else "Нажмите «Найти JK BMS», чтобы привязать плату"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(tvDeviceSubtitle)

        tvStatusBadge = TextView(this).apply {
            text = "Ожидание..."
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBg("#1E293B", 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(tvStatusBadge)

        // Главная карточка: Процент заряда (SoC)
        val socCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#1E293B", 16)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(14)
            layoutParams = lp
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val tvSocLabel = TextView(this).apply {
            text = "ТЕКУЩИЙ ЗАРЯД (SoC)"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }
        socCard.addView(tvSocLabel)

        tvSocBig = TextView(this).apply {
            text = "-- %"
            setTextColor(Color.parseColor("#4ADE80"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
        }
        socCard.addView(tvSocBig)

        progressSoc = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10))
            lp.topMargin = dp(4)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }
        socCard.addView(progressSoc)

        tvEstimatedTime = TextView(this).apply {
            text = "Запас энергии: -- Вт·ч"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        socCard.addView(tvEstimatedTime)
        root.addView(socCard)

        // Строка из 2 карточек: Скорость Зарядки (Вт·ч) и Скорость Расхода (Вт·ч)
        val rowRates = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(12)
            layoutParams = lp
        }

        val chargeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#1E293B", 14)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = dp(6)
            layoutParams = lp
        }
        chargeCard.addView(TextView(this).apply {
            text = "⚡ СКОРОСТЬ ЗАРЯДКИ"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
        })
        tvChargeRateVal = TextView(this).apply {
            text = "0 Вт·ч"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(2))
        }
        chargeCard.addView(tvChargeRateVal)
        chargeCard.addView(TextView(this).apply {
            text = "приход в час (Вт)"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        val dischargeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#1E293B", 14)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(6)
            layoutParams = lp
        }
        dischargeCard.addView(TextView(this).apply {
            text = "🔻 СКОРОСТЬ РАСХОДА"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
        })
        tvDischargeRateVal = TextView(this).apply {
            text = "0 Вт·ч"
            setTextColor(Color.parseColor("#FB923C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(2))
        }
        dischargeCard.addView(tvDischargeRateVal)
        dischargeCard.addView(TextView(this).apply {
            text = "потребление в час (Вт)"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        rowRates.addView(chargeCard)
        rowRates.addView(dischargeCard)
        root.addView(rowRates)

        // Карточка Температуры
        val tempCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#1E293B", 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(12)
            layoutParams = lp
        }
        tempCard.addView(TextView(this).apply {
            text = "🌡 ТЕМПЕРАТУРА (ДАТЧИКИ И ПЛАТА)"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
        })
        tvTempSensorVal = TextView(this).apply {
            text = "Датчик 1: -- °C   |   Датчик 2: -- °C"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(2))
        }
        tempCard.addView(tvTempSensorVal)
        tvTempMosVal = TextView(this).apply {
            text = "Силовые ключи (MOS): -- °C"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        tempCard.addView(tvTempMosVal)
        root.addView(tempCard)

        // Карточка Деталей (Напряжение, Ток, Ячейки B2A8S20P)
        val detailsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#1E293B", 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(12)
            layoutParams = lp
        }
        detailsCard.addView(TextView(this).apply {
            text = "📊 ПАРАМЕТРЫ БАТАРЕИ И ЯЧЕЕК (B2A8S20P)"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
        })
        tvDetailsPack = TextView(this).apply {
            text = "Напряжение: -- В  •  Ток: -- А  •  Разбег Δ: -- мВ"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(4))
        }
        detailsCard.addView(tvDetailsPack)

        tvDetailsCells = TextView(this).apply {
            text = "Ячейки: ожидание данных..."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
        }
        detailsCard.addView(tvDetailsCells)
        root.addView(detailsCard)

        // Кнопки управления
        val btnScan = Button(this).apply {
            text = "🔍 Найти и привязать JK BMS (Bluetooth)"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(16)
            layoutParams = lp
            setOnClickListener {
                if (hasBlePermissions()) {
                    showBleScannerDialog()
                } else {
                    requestNeededPermissions()
                }
            }
        }
        root.addView(btnScan)

        btnPauseResume = Button(this).apply {
            text = "⏸ Пауза (освободить Bluetooth для JK App)"
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
            setOnClickListener {
                val st = JkBmsBleService.stateFlow.value
                if (st.paused) {
                    startMonitorService(JkBmsBleService.ACTION_RESUME)
                } else {
                    startMonitorService(JkBmsBleService.ACTION_PAUSE_FOR_JK_APP)
                }
            }
        }
        root.addView(btnPauseResume)

        // Настройка оповещений (по желанию пользователя)
        val alertsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#1E293B", 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(14)
            layoutParams = lp
        }

        val switchAlerts = Switch(this).apply {
            text = "🔔 Звуковые оповещения (низкий заряд / перегрев)"
            setTextColor(Color.WHITE)
            isChecked = prefs.alertsEnabled
            setOnCheckedChangeListener { _, isChecked ->
                prefs.alertsEnabled = isChecked
            }
        }
        alertsCard.addView(switchAlerts)

        val tvLowSocLabel = TextView(this).apply {
            text = "Порог низкого заряда: ${prefs.lowSocAlertThreshold}%"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, dp(4))
        }
        alertsCard.addView(tvLowSocLabel)

        val seekLowSoc = SeekBar(this).apply {
            max = 50
            progress = prefs.lowSocAlertThreshold
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val p = progress.coerceAtLeast(5)
                    prefs.lowSocAlertThreshold = p
                    tvLowSocLabel.text = "Порог низкого заряда: $p%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        alertsCard.addView(seekLowSoc)
        root.addView(alertsCard)

        return scroll
    }

    private fun renderState(state: JkBmsState) {
        val savedName = prefs.targetDeviceName.ifBlank { state.deviceName }
        val savedMac = prefs.targetMacAddress.ifBlank { state.deviceAddress }
        if (savedMac.isNotBlank()) {
            tvDeviceSubtitle.text = "Устройство: $savedName ($savedMac)"
        }

        val timeStr = if (state.lastUpdatedMillis > 0) {
            " • обновл. " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(state.lastUpdatedMillis))
        } else ""

        tvStatusBadge.text = "${state.statusText}$timeStr"
        tvStatusBadge.setTextColor(
            when {
                state.paused -> Color.parseColor("#FACC15")
                state.connected -> Color.parseColor("#4ADE80")
                else -> Color.parseColor("#38BDF8")
            }
        )

        btnPauseResume.text = if (state.paused) {
            "▶ Возобновить мониторинг в шторке"
        } else {
            "⏸ Пауза (освободить Bluetooth для JK App)"
        }

        if (state.lastUpdatedMillis > 0) {
            tvSocBig.text = "${state.socPercent} %"
            progressSoc.progress = state.socPercent
            tvEstimatedTime.text = String.format(
                Locale.US,
                "Запас: %.1f / %.0f А·ч (~%.0f Вт·ч)  •  %s",
                state.remainingCapacityAh,
                state.nominalCapacityAh,
                state.remainingEnergyWh,
                state.getEstimatedTimeText()
            )

            tvChargeRateVal.text = "${state.chargeWatts.toInt()} Вт·ч"
            tvDischargeRateVal.text = "${state.dischargeWatts.toInt()} Вт·ч"

            tvTempSensorVal.text = String.format(
                Locale.US,
                "Датчик 1: %.1f °C   |   Датчик 2: %.1f °C",
                state.tempSensor1,
                state.tempSensor2
            )
            tvTempMosVal.text = String.format(
                Locale.US,
                "Силовые ключи (MOS): %.1f °C",
                state.tempMos
            )

            tvDetailsPack.text = String.format(
                Locale.US,
                "Напряжение: %.2f В  •  Ток: %+.1f А  •  Разбег Δ: %d мВ",
                state.packVoltage,
                state.currentAmps,
                state.cellDeltaMv
            )

            if (state.cellVoltages.isNotEmpty()) {
                val formattedCells = state.cellVoltages.mapIndexed { idx, v ->
                    String.format(Locale.US, "C%d:%.3fV", idx + 1, v)
                }.chunked(4).joinToString("\n") { it.joinToString("  ") }
                tvDetailsCells.text = formattedCells
            }
        }
    }

    private fun showBleScannerDialog() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Включите Bluetooth на телефоне", Toast.LENGTH_LONG).show()
            return
        }

        discoveredDevices.clear()
        // Сначала добавляем уже спаренные устройства Bluetooth
        try {
            adapter.bondedDevices?.forEach { dev ->
                val name = dev.name ?: "Без имени"
                discoveredDevices[dev.address] = "$name [Сопряжено]"
            }
        } catch (_: Exception) {
        }

        val scanner = adapter.bluetoothLeScanner
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev: BluetoothDevice = result.device ?: return
                val name = dev.name ?: result.scanRecord?.deviceName ?: "BLE-устройство"
                discoveredDevices[dev.address] = name
            }
        }

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner?.startScan(null, settings, scanCallback)
            scanning = true
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка запуска сканера: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        Toast.makeText(this, "Поиск Bluetooth устройств JK BMS (4 сек)...", Toast.LENGTH_SHORT).show()

        handler.postDelayed({
            if (scanning) {
                try {
                    scanner?.stopScan(scanCallback)
                } catch (_: Exception) {
                }
                scanning = false
            }

            if (discoveredDevices.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Устройства не найдены")
                    .setMessage("Убедитесь, что Bluetooth и Геолокация включены, а родное приложение JK BMS закрыто (оно занимает Bluetooth-канал платы).")
                    .setPositiveButton("ОК", null)
                    .show()
                return@postDelayed
            }

            // Сортируем так, чтобы устройства с JK / B2A8S были сверху
            val sortedEntries = discoveredDevices.entries.sortedByDescending { entry ->
                val up = entry.value.uppercase(Locale.ROOT)
                up.contains("JK") || up.contains("BMS") || up.contains("B2A")
            }

            val items = sortedEntries.map { "${it.value}\n(${it.key})" }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Выберите вашу JK BMS")
                .setItems(items) { _, which ->
                    val chosen = sortedEntries[which]
                    prefs.targetMacAddress = chosen.key
                    prefs.targetDeviceName = chosen.value
                    tvDeviceSubtitle.text = "Устройство: ${chosen.value} (${chosen.key})"
                    startMonitorService(JkBmsBleService.ACTION_RECONNECT)
                    Toast.makeText(this, "Подключение к ${chosen.value}...", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }, 4000)
    }

    private fun startMonitorService(action: String) {
        val intent = Intent(this, JkBmsBleService::class.java).apply {
            this.action = action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun hasBlePermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun roundedBg(hexColor: String, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(hexColor))
            cornerRadius = dp(radiusDp).toFloat()
        }
}
