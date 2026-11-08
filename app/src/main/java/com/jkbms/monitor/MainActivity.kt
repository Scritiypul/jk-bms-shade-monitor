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
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private var uiJob: Job? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)

    // Верхняя панель
    private lateinit var tvConnSourceBadge: TextView
    private lateinit var tvSubtitleInfo: TextView

    // Главная карточка SoC
    private lateinit var tvSocBig: TextView
    private lateinit var tvModePill: TextView
    private lateinit var progressSoc: ProgressBar
    private lateinit var tvCapacityLine: TextView
    private lateinit var tvEtaLine: TextView

    // 4 карточки показателей
    private lateinit var tvVoltVal: TextView
    private lateinit var tvCurrVal: TextView
    private lateinit var tvChargeVal: TextView
    private lateinit var tvDischargeVal: TextView

    // 3 индикатора ключей MOS и балансира
    private lateinit var tvMosChg: TextView
    private lateinit var tvMosDsg: TextView
    private lateinit var tvMosBal: TextView

    // Карта 8 ячеек
    private lateinit var tvCellsSummary: TextView
    private val cellBoxes = ArrayList<LinearLayout>(8)
    private val cellVoltTexts = ArrayList<TextView>(8)
    private val cellBars = ArrayList<ProgressBar>(8)

    // Температуры и подвал
    private lateinit var tvTempMos: TextView
    private lateinit var tvTemp1: TextView
    private lateinit var tvTemp2: TextView
    private lateinit var tvCycles: TextView
    private lateinit var tvFooterMeta: TextView

    private val discoveredDevices = LinkedHashMap<String, String>()
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)

        window.statusBarColor = Color.parseColor("#080D1A")
        window.navigationBarColor = Color.parseColor("#080D1A")

        setContentView(buildDashboardView())
        requestNeededPermissions()
        startMonitorService(JkBmsBleService.ACTION_START)
    }

    override fun onStart() {
        super.onStart()
        uiJob = mainScope.launch {
            JkBmsBleService.stateFlow.collect { state ->
                try {
                    renderState(state)
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun onStop() {
        uiJob?.cancel()
        super.onStop()
    }

    private fun buildDashboardView(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#080D1A"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(14)
            setPadding(pad, dp(18), pad, dp(28))
        }
        scroll.addView(root)

        // ==================== 1. ВЕРХНЯЯ ШАПКА ====================
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "LiFePO4 24V • 230Ah"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        })
        tvSubtitleInfo = TextView(this).apply {
            text = "Шлюз: ${prefs.esp32Host} + Облако"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, 0)
        }
        titleCol.addView(tvSubtitleInfo)
        headerRow.addView(titleCol)

        val btnSettings = TextView(this).apply {
            text = "⚙️ Управление"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            background = cardBg("#131E33", "#263B5E", 12)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setOnClickListener { showSettingsAndControlDialog() }
        }
        headerRow.addView(btnSettings)
        root.addView(headerRow)

        // Строка статуса подключения
        tvConnSourceBadge = TextView(this).apply {
            text = "🔄 Подключение к шлюзу ESP32..."
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = cardBg("#111A2E", "#1E293B", 10)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(10)
            layoutParams = lp
        }
        root.addView(tvConnSourceBadge)

        // ==================== 2. ГЛАВНАЯ КАРТОЧКА БАТАРЕИ ====================
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#14213B", "#263C63", 20)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(12)
            layoutParams = lp
        }

        val heroTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val socCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        socCol.addView(TextView(this).apply {
            text = "ЗАРЯД АККУМУЛЯТОРА"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
        })
        tvSocBig = TextView(this).apply {
            text = "-- %"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            typeface = Typeface.DEFAULT_BOLD
        }
        socCol.addView(tvSocBig)
        heroTop.addView(socCol)

        tvModePill = TextView(this).apply {
            text = "💤 Ожидание"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            background = cardBg("#0D1729", "#243B61", 12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        heroTop.addView(tvModePill)
        heroCard.addView(heroTop)

        tvCapacityLine = TextView(this).apply {
            text = "Емкость: -- / 230 А·ч  (~ -- Вт·ч)"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(0, dp(4), 0, dp(8))
        }
        heroCard.addView(tvCapacityLine)

        progressSoc = buildStyledProgressBar("#090E1A", "#10B981", dp(14)).apply {
            max = 100
            progress = 0
        }
        heroCard.addView(progressSoc)

        tvEtaLine = TextView(this).apply {
            text = "Прогноз времени: расчет..."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(9), 0, 0)
        }
        heroCard.addView(tvEtaLine)
        root.addView(heroCard)

        // ==================== 3. СЕТКА 2x2: НАПРЯЖЕНИЕ, ТОК, ЗАРЯД, РАСХОД ====================
        val rowMetrics1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            layoutParams = lp
        }
        val voltCard = createMetricCard("⚡ НАПРЯЖЕНИЕ", "-- В", "#F8FAFC") { tvVoltVal = it }
        val currCard = createMetricCard("🔌 ТОК БАТАРЕИ", "-- А", "#38BDF8") { tvCurrVal = it }
        (voltCard.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(5)
        (currCard.layoutParams as LinearLayout.LayoutParams).marginStart = dp(5)
        rowMetrics1.addView(voltCard)
        rowMetrics1.addView(currCard)
        root.addView(rowMetrics1)

        val rowMetrics2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            layoutParams = lp
        }
        val chgCard = createMetricCard("⚡ ЗАРЯДКА (ВТ)", "0 Вт", "#10B981") { tvChargeVal = it }
        val dsgCard = createMetricCard("🔻 РАСХОД (ВТ)", "0 Вт", "#FB923C") { tvDischargeVal = it }
        (chgCard.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(5)
        (dsgCard.layoutParams as LinearLayout.LayoutParams).marginStart = dp(5)
        rowMetrics2.addView(chgCard)
        rowMetrics2.addView(dsgCard)
        root.addView(rowMetrics2)

        // ==================== 4. СТАТУС КЛЮЧЕЙ MOSFET И БАЛАНСИРА ====================
        val mosRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            layoutParams = lp
        }
        tvMosChg = createMosPill("⚡ Заряд: --").apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(4)
            setOnClickListener { showSettingsAndControlDialog() }
        }
        tvMosDsg = createMosPill("🔋 Разряд: --").apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(dp(4), 0, dp(4), 0)
            setOnClickListener { showSettingsAndControlDialog() }
        }
        tvMosBal = createMosPill("⚖️ Баланс: --").apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(4)
            setOnClickListener { showSettingsAndControlDialog() }
        }
        mosRow.addView(tvMosChg)
        mosRow.addView(tvMosDsg)
        mosRow.addView(tvMosBal)
        root.addView(mosRow)

        // ==================== 5. ВИЗУАЛЬНАЯ КАРТА ЯЧЕЕК 8S ====================
        val cellsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#121C2E", "#1F2E47", 18)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(12)
            layoutParams = lp
        }

        val cellsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cellsHeader.addView(TextView(this).apply {
            text = "ЯЧЕЙКИ 1–8 (LiFePO4)"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        tvCellsSummary = TextView(this).apply {
            text = "Δ: -- мВ • Сред: -.--- В"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }
        cellsHeader.addView(tvCellsSummary)
        cellsSection.addView(cellsHeader)

        cellBoxes.clear()
        cellVoltTexts.clear()
        cellBars.clear()

        for (row in 0 until 4) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            for (col in 0 until 2) {
                val cellIdx = row * 2 + col + 1
                val cellCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = cardBg("#0B1220", "#1E293B", 11)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    if (col == 0) lp.marginEnd = dp(4) else lp.marginStart = dp(4)
                    layoutParams = lp
                }

                val topLine = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                topLine.addView(TextView(this).apply {
                    text = "#$cellIdx"
                    setTextColor(Color.parseColor("#94A3B8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val tvV = TextView(this).apply {
                    text = "-.--- В"
                    setTextColor(Color.parseColor("#F8FAFC"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.MONOSPACE
                }
                topLine.addView(tvV)
                cellCard.addView(topLine)

                val bar = buildStyledProgressBar("#1E293B", "#38BDF8", dp(5)).apply {
                    max = 100
                    progress = 50
                    val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5))
                    blp.topMargin = dp(6)
                    layoutParams = blp
                }
                cellCard.addView(bar)

                cellBoxes.add(cellCard)
                cellVoltTexts.add(tvV)
                cellBars.add(bar)
                rowLayout.addView(cellCard)
            }
            cellsSection.addView(rowLayout)
        }
        root.addView(cellsSection)

        // ==================== 6. ТЕМПЕРАТУРЫ И ЦИКЛЫ ====================
        val tempSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#121C2E", "#1F2E47", 18)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(12)
            layoutParams = lp
        }

        val tempHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tempHead.addView(TextView(this).apply {
            text = "🌡 ТЕМПЕРАТУРА И РЕСУРС"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        tvCycles = TextView(this).apply {
            text = "Циклов: --"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }
        tempHead.addView(tvCycles)
        tempSection.addView(tempHead)

        val tempRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            layoutParams = lp
        }
        tempRow.addView(createTempBox("КЛЮЧИ MOS") { tvTempMos = it }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(4)
        })
        tempRow.addView(createTempBox("ДАТЧИК T1") { tvTemp1 = it }.apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(dp(4), 0, dp(4), 0)
        })
        tempRow.addView(createTempBox("ДАТЧИК T2") { tvTemp2 = it }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(4)
        })
        tempSection.addView(tempRow)
        root.addView(tempSection)

        // Кнопка быстрого вызова настроек и управления внизу
        val btnControlBig = Button(this).apply {
            text = "⚙️ Управление BMS (Заряд / Разряд / Настройки)"
            setBackgroundColor(Color.parseColor("#1D4ED8"))
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(14)
            layoutParams = lp
            setOnClickListener { showSettingsAndControlDialog() }
        }
        root.addView(btnControlBig)

        tvFooterMeta = TextView(this).apply {
            text = "Локальный Wi-Fi + Облачный доступ из любой точки мира"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(tvFooterMeta)

        return scroll
    }

    private fun createMetricCard(
        title: String,
        initialValue: String,
        valueColorHex: String,
        assignView: (TextView) -> Unit
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#121C2E", "#1F2E47", 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
        })
        val tvVal = TextView(this).apply {
            text = initialValue
            setTextColor(Color.parseColor(valueColorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
        }
        assignView(tvVal)
        card.addView(tvVal)
        return card
    }

    private fun createMosPill(label: String): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = cardBg("#121C2E", "#1F2E47", 12)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
    }

    private fun createTempBox(label: String, assignView: (TextView) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = cardBg("#0B1220", "#1E293B", 12)
            setPadding(dp(8), dp(9), dp(8), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
        })
        val tv = TextView(this).apply {
            text = "-- °C"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, 0)
        }
        assignView(tv)
        box.addView(tv)
        return box
    }

    private fun renderState(state: JkBmsState) {
        val modeDesc = if (prefs.useDirectBle) {
            "Режим: Прямой Bluetooth (${prefs.targetMacAddress.ifBlank { "не выбран" }})"
        } else {
            "ESP32: ${prefs.esp32Host}  •  Облако: ${prefs.cloudTopicId}"
        }
        tvSubtitleInfo.text = modeDesc

        val timeStr = if (state.lastUpdatedMillis > 0) {
            " • " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(state.lastUpdatedMillis))
        } else ""

        tvConnSourceBadge.text = "${state.statusText}$timeStr"
        tvConnSourceBadge.setTextColor(
            when {
                state.paused -> Color.parseColor("#FACC15")
                state.connected -> Color.parseColor("#4ADE80")
                else -> Color.parseColor("#38BDF8")
            }
        )

        if (state.lastUpdatedMillis > 0) {
            tvSocBig.text = "${state.socPercent} %"
            tvSocBig.setTextColor(
                when {
                    state.socPercent <= 20 -> Color.parseColor("#EF4444")
                    state.socPercent <= 40 -> Color.parseColor("#F59E0B")
                    else -> Color.parseColor("#10B981")
                }
            )
            progressSoc.progress = state.socPercent

            tvCapacityLine.text = String.format(
                Locale.US,
                "Запас: %.1f из %.0f А·ч  (~%.0f Вт·ч)",
                state.remainingCapacityAh,
                state.nominalCapacityAh,
                state.remainingEnergyWh
            )
            tvEtaLine.text = "Прогноз времени: ${state.getEstimatedTimeText()}"

            when {
                state.currentAmps > 0.25 -> {
                    tvModePill.text = "⚡ Зарядка"
                    tvModePill.setTextColor(Color.parseColor("#10B981"))
                }
                state.currentAmps < -0.25 -> {
                    tvModePill.text = "🔋 Разряд"
                    tvModePill.setTextColor(Color.parseColor("#38BDF8"))
                }
                else -> {
                    tvModePill.text = "💤 Покой"
                    tvModePill.setTextColor(Color.parseColor("#94A3B8"))
                }
            }

            tvVoltVal.text = String.format(Locale.US, "%.2f В", state.packVoltage)
            tvCurrVal.text = String.format(Locale.US, "%+.1f А", state.currentAmps)
            tvChargeVal.text = "${state.chargeWatts.toInt()} Вт"
            tvDischargeVal.text = "${state.dischargeWatts.toInt()} Вт"

            styleMosPill(tvMosChg, "⚡ Заряд", state.chargeMosEnabled)
            styleMosPill(tvMosDsg, "🔋 Разряд", state.dischargeMosEnabled)
            styleMosPill(tvMosBal, "⚖️ Баланс", state.balanceEnabled)

            tvCellsSummary.text = String.format(
                Locale.US,
                "Δ %d мВ • Сред: %.3f В",
                state.cellDeltaMv,
                state.cellAvgV
            )

            for (i in 0 until 8) {
                val cv = state.cellVoltages.getOrNull(i) ?: 0.0
                val idx = i + 1
                cellVoltTexts[i].text = if (cv > 0.1) String.format(Locale.US, "%.3f В", cv) else "-.--- В"
                val pct = (((cv - 2.65) / (3.65 - 2.65)) * 100.0).toInt().coerceIn(5, 100)
                cellBars[i].progress = pct

                val borderHex = when (idx) {
                    state.minCellIndex -> "#38BDF8"
                    state.maxCellIndex -> "#F59E0B"
                    else -> "#1E293B"
                }
                cellBoxes[i].background = cardBg("#0B1220", borderHex, 11)
            }

            tvTempMos.text = String.format(Locale.US, "%.1f °C", state.tempMos)
            tvTemp1.text = String.format(Locale.US, "%.1f °C", state.tempSensor1)
            tvTemp2.text = String.format(Locale.US, "%.1f °C", state.tempSensor2)
            tvCycles.text = "Циклов: ${state.cycleCount}"

            val gwInfo = if (state.gatewayIp.isNotEmpty()) "IP: ${state.gatewayIp} • " else ""
            tvFooterMeta.text = "${gwInfo}Источник: ${state.connectionSource} • Пакетов: ${state.packetsCount}"
        }
    }

    private fun styleMosPill(tv: TextView, name: String, enabled: Boolean) {
        if (enabled) {
            tv.text = "$name: ВКЛ"
            tv.setTextColor(Color.parseColor("#10B981"))
            tv.background = cardBg("#0F2425", "#10B981", 12)
        } else {
            tv.text = "$name: ВЫКЛ"
            tv.setTextColor(Color.parseColor("#94A3B8"))
            tv.background = cardBg("#121C2E", "#334155", 12)
        }
    }

    /**
     * Диалог управления ключами BMS (Заряд / Разряд / Балансировка) и настроек подключения
     */
    private fun showSettingsAndControlDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(18)
            setPadding(p, dp(14), p, dp(14))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        scroll.addView(container)

        val tvCmdStatus = TextView(this).apply {
            text = "Команды работают и дома по Wi-Fi, и удаленно через Интернет!"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(10))
        }
        container.addView(tvCmdStatus)

        // Поле PIN-пароля управления
        container.addView(TextView(this).apply {
            text = "🔐 Пароль / PIN управления BMS:"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
        })
        val etPin = EditText(this).apply {
            setText(prefs.controlPin)
            hint = "По умолчанию: 1234"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(etPin)

        // Строки управления Зарядом, Разрядом, Балансиром
        fun addControlRow(label: String, cmdCode: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this@MainActivity).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val btnOn = Button(this@MainActivity).apply {
                text = "ВКЛ"
                setBackgroundColor(Color.parseColor("#10B981"))
                setTextColor(Color.parseColor("#051B14"))
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener {
                    val pin = etPin.text.toString().trim().ifBlank { "1234" }
                    prefs.controlPin = pin
                    tvCmdStatus.text = "⏳ Отправка команды $label -> ВКЛ..."
                    mainScope.launch {
                        val (ok, msg) = JkBmsBleService.sendGatewayControlCommand(this@MainActivity, cmdCode, 1, pin)
                        tvCmdStatus.text = msg
                        tvCmdStatus.setTextColor(if (ok) Color.parseColor("#4ADE80") else Color.parseColor("#EF4444"))
                    }
                }
            }
            val btnOff = Button(this@MainActivity).apply {
                text = "ВЫКЛ"
                setBackgroundColor(Color.parseColor("#EF4444"))
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginStart = dp(6)
                layoutParams = lp
                setOnClickListener {
                    val pin = etPin.text.toString().trim().ifBlank { "1234" }
                    prefs.controlPin = pin
                    tvCmdStatus.text = "⏳ Отправка команды $label -> ВЫКЛ..."
                    mainScope.launch {
                        val (ok, msg) = JkBmsBleService.sendGatewayControlCommand(this@MainActivity, cmdCode, 0, pin)
                        tvCmdStatus.text = msg
                        tvCmdStatus.setTextColor(if (ok) Color.parseColor("#4ADE80") else Color.parseColor("#EF4444"))
                    }
                }
            }
            row.addView(btnOn)
            row.addView(btnOff)
            container.addView(row)
        }

        addControlRow("⚡ Ключ Заряда (Charge):", "chg")
        addControlRow("🔋 Ключ Разряда (Discharge):", "dsg")
        addControlRow("⚖️ Балансировка (Balance):", "bal")

        // Настройки адреса шлюза ESP32 и Облачного ID
        container.addView(TextView(this).apply {
            text = "\n🌐 Подключение к ESP32 (Локально + Интернет):"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(4))
        })

        container.addView(TextView(this).apply {
            text = "Локальный IP адрес ESP32 в домашнем Wi-Fi:"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        val etHost = EditText(this).apply {
            setText(prefs.esp32Host)
            hint = "192.168.123.105"
            setTextColor(Color.WHITE)
        }
        container.addView(etHost)

        val btnScanWifi = Button(this).apply {
            text = "🔍 Автопоиск ESP32 в домашней сети Wi-Fi"
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                tvCmdStatus.text = "🔍 Сканирование домашней Wi-Fi подсети..."
                scanLocalSubnetForEsp32 { foundIp ->
                    if (foundIp != null) {
                        etHost.setText(foundIp)
                        prefs.esp32Host = foundIp
                        tvCmdStatus.text = "✅ Шлюз ESP32 найден: $foundIp"
                        tvCmdStatus.setTextColor(Color.parseColor("#4ADE80"))
                    } else {
                        tvCmdStatus.text = "❌ ESP32 не найдена в локальной сети (будет использовано Облако)"
                    }
                }
            }
        }
        container.addView(btnScanWifi)

        container.addView(TextView(this).apply {
            text = "Облачный ID вашей BMS (для доступа из любой точки мира):"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, 0)
        })
        val etCloudId = EditText(this).apply {
            setText(prefs.cloudTopicId)
            hint = "c84780550485"
            setTextColor(Color.WHITE)
        }
        container.addView(etCloudId)

        // Оповещения
        val swAlerts = Switch(this).apply {
            text = "🔔 Звуковые уведомления (низкий заряд / перегрев)"
            setTextColor(Color.WHITE)
            isChecked = prefs.alertsEnabled
            setPadding(0, dp(10), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> prefs.alertsEnabled = checked }
        }
        container.addView(swAlerts)

        val tvLowSoc = TextView(this).apply {
            text = "Порог тревоги низкого заряда: ${prefs.lowSocAlertThreshold}%"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        container.addView(tvLowSoc)

        val seekSoc = SeekBar(this).apply {
            max = 50
            progress = prefs.lowSocAlertThreshold
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p.coerceAtLeast(5)
                    prefs.lowSocAlertThreshold = v
                    tvLowSoc.text = "Порог тревоги низкого заряда: $v%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        container.addView(seekSoc)

        // Опциональный режим прямого Bluetooth (без ESP32)
        val swDirectBle = Switch(this).apply {
            text = "🦷 Прямой Bluetooth к BMS (вместо ESP32)"
            setTextColor(Color.parseColor("#94A3B8"))
            isChecked = prefs.useDirectBle
            setPadding(0, dp(10), 0, dp(4))
            setOnCheckedChangeListener { _, checked ->
                prefs.useDirectBle = checked
                if (checked && prefs.targetMacAddress.isBlank()) {
                    if (hasBlePermissions()) showBleScannerDialog() else requestNeededPermissions()
                }
            }
        }
        container.addView(swDirectBle)

        AlertDialog.Builder(this)
            .setTitle("⚙️ Управление BMS и Настройки")
            .setView(scroll)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.controlPin = etPin.text.toString().trim().ifBlank { "1234" }
                prefs.esp32Host = etHost.text.toString().trim().ifBlank { "192.168.123.105" }
                prefs.cloudTopicId = etCloudId.text.toString().trim().ifBlank { "c84780550485" }
                renderState(JkBmsBleService.stateFlow.value)
                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun scanLocalSubnetForEsp32(onResult: (String?) -> Unit) {
        mainScope.launch {
            val foundIp = withContext(Dispatchers.IO) {
                val localIp = JkBmsBleService.getLocalWifiIp()
                val prefix = if (localIp.contains(".")) {
                    localIp.substringBeforeLast(".")
                } else {
                    prefs.esp32Host.substringBeforeLast(".", "192.168.123")
                }

                val priorityOctets = listOf(105, 100, 101, 102, 103, 104, 106, 107, 108, 109, 110) +
                    (1..254).filter { it !in 100..110 }

                var discovered: String? = null
                for (chunk in priorityOctets.chunked(24)) {
                    if (discovered != null) break
                    val jobs = chunk.map { last ->
                        async {
                            val candidate = "$prefix.$last"
                            try {
                                val url = URL("http://$candidate/status")
                                val conn = (url.openConnection() as HttpURLConnection).apply {
                                    connectTimeout = 650
                                    readTimeout = 650
                                    requestMethod = "GET"
                                }
                                if (conn.responseCode == 200) {
                                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                                    if (body.contains("\"soc\"")) {
                                        discovered = candidate
                                    }
                                }
                                conn.disconnect()
                            } catch (_: Exception) {
                            }
                        }
                    }
                    jobs.awaitAll()
                }
                discovered
            }
            onResult(foundIp)
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
        try {
            adapter.bondedDevices?.forEach { dev ->
                val name = dev.name ?: "Без имени"
                discoveredDevices[dev.address] = "$name [Сопряжено]"
            }
        } catch (_: Exception) {}

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
        } catch (_: Exception) {}

        Toast.makeText(this, "Поиск Bluetooth JK BMS (4 сек)...", Toast.LENGTH_SHORT).show()

        handler.postDelayed({
            if (scanning) {
                try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
                scanning = false
            }
            if (discoveredDevices.isEmpty()) return@postDelayed

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
                    startMonitorService(JkBmsBleService.ACTION_RECONNECT)
                }
                .setNegativeButton("Отмена", null)
                .show()
        }, 4000)
    }

    private fun startMonitorService(action: String) {
        val intent = Intent(this, JkBmsBleService::class.java).apply {
            this.action = action
        }
        try {
            startService(intent)
        } catch (_: Throwable) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                }
            } catch (_: Throwable) {
            }
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

    private fun buildStyledProgressBar(trackHex: String, fillHex: String, heightPx: Int): ProgressBar {
        return ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            val bgShape = GradientDrawable().apply {
                setColor(Color.parseColor(trackHex))
                cornerRadius = heightPx.toFloat()
            }
            val fgShape = GradientDrawable().apply {
                setColor(Color.parseColor(fillHex))
                cornerRadius = heightPx.toFloat()
            }
            val clipFg = ClipDrawable(fgShape, Gravity.START, ClipDrawable.HORIZONTAL)
            val layers = LayerDrawable(arrayOf(bgShape, clipFg)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            progressDrawable = layers
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun cardBg(bgHex: String, borderHex: String, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), Color.parseColor(borderHex))
        }
}
