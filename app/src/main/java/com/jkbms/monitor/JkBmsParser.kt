package com.jkbms.monitor

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

data class JkBmsState(
    val connected: Boolean = false,
    val paused: Boolean = false,
    val statusText: String = "Ожидание подключения...",
    val connectionSource: String = "Поиск шлюза...",
    val deviceName: String = "JK-B2A8S20P",
    val deviceAddress: String = "C8:47:80:55:04:85",
    val socPercent: Int = 0,               // % заряда
    val packVoltage: Double = 0.0,         // В (Общее напряжение)
    val currentAmps: Double = 0.0,         // А (+ заряд, - разряд)
    val powerWatts: Double = 0.0,          // Вт (со знаком: + заряд, - разряд)
    val chargeWatts: Double = 0.0,         // Скорость зарядки (Вт)
    val dischargeWatts: Double = 0.0,      // Скорость расхода (Вт)
    val remainingCapacityAh: Double = 0.0, // Остаток ёмкости А·ч
    val nominalCapacityAh: Double = 230.0, // Номинальная ёмкость А·ч
    val remainingEnergyWh: Double = 0.0,   // Остаток энергии Вт·ч
    val tempMos: Double = 0.0,             // Температура платы (MOS) °C
    val tempSensor1: Double = 0.0,         // Датчик температуры 1 (T1) °C
    val tempSensor2: Double = 0.0,         // Датчик температуры 2 (T2) °C
    val cellVoltages: List<Double> = emptyList(),
    val cellDeltaMv: Int = 0,              // Разбег ячеек в мВ
    val cellAvgV: Double = 0.0,            // Среднее напряжение ячейки В
    val minCellIndex: Int = 0,             // Индекс минимальной ячейки (1..8)
    val maxCellIndex: Int = 0,             // Индекс максимальной ячейки (1..8)
    val chargeMosEnabled: Boolean = true,
    val dischargeMosEnabled: Boolean = true,
    val balanceEnabled: Boolean = false,
    val cycleCount: Int = 0,
    val packetsCount: Long = 0L,
    val gatewayIp: String = "",
    val wifiRssi: Int = 0,
    val lastUpdatedMillis: Long = 0L
) {
    fun getEstimatedTimeText(): String {
        if (abs(currentAmps) < 0.2) return "Покой (нет нагрузки)"
        return if (currentAmps > 0) {
            val needAh = (nominalCapacityAh - remainingCapacityAh).coerceAtLeast(0.0)
            if (needAh <= 0.01) return "Почти заряжен"
            val hours = needAh / currentAmps
            formatHours(hours, "до 100%")
        } else {
            val remAh = remainingCapacityAh.coerceAtLeast(0.0)
            val hours = remAh / abs(currentAmps)
            formatHours(hours, "осталось")
        }
    }

    private fun formatHours(hours: Double, suffix: String): String {
        if (hours > 99) return ">99 ч $suffix"
        val totalMinutes = (hours * 60).toInt()
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) "${h}ч ${m}м $suffix" else "${m}м $suffix"
    }
}

object JkBmsParser {
    fun buildDeviceInfoCommand(): ByteArray = buildCommand(0x97.toByte(), 0, 0)
    fun buildCellInfoCommand(): ByteArray = buildCommand(0x96.toByte(), 0, 0)

    fun buildControlCommand(reg: Byte, value: Int): ByteArray =
        buildCommand(reg, value, 4)

    private fun buildCommand(cmdCode: Byte, value: Int, len: Int): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        frame[2] = 0x90.toByte()
        frame[3] = 0xEB.toByte()
        frame[4] = cmdCode
        frame[5] = len.toByte()
        frame[6] = (value and 0xFF).toByte()
        frame[7] = ((value shr 8) and 0xFF).toByte()
        frame[8] = ((value shr 16) and 0xFF).toByte()
        frame[9] = ((value shr 24) and 0xFF).toByte()
        var crc = 0
        for (i in 0 until 19) {
            crc = (crc + (frame[i].toInt() and 0xFF)) and 0xFF
        }
        frame[19] = crc.toByte()
        return frame
    }

    /**
     * Разбор JSON-ответа от шлюза ESP32 (/status или из облачного MQTT-канала)
     */
    fun parseGatewayJson(jsonStr: String, sourceLabel: String): JkBmsState? {
        return try {
            val obj = JSONObject(jsonStr)
            val isConn = obj.optBoolean("connected", false) || obj.optBoolean("hasData", false)
            val soc = obj.optInt("soc", obj.optInt("socPercent", 0)).coerceIn(0, 100)
            val totalV = obj.optDouble("totalVoltage", obj.optDouble("packVoltage", 0.0))
            val curA = obj.optDouble("current", obj.optDouble("currentAmps", 0.0))
            val signedPwr = totalV * curA
            val chgW = if (signedPwr > 0.0) signedPwr else 0.0
            val dsgW = if (signedPwr < 0.0) abs(signedPwr) else 0.0

            val remAh = obj.optDouble("remainCap", obj.optDouble("remainingCapacityAh", 0.0))
            val nomAh = obj.optDouble("nominalCap", obj.optDouble("nominalCapacityAh", 230.0))
            val remWh = obj.optDouble(
                "remainingEnergyWh",
                remAh * (if (totalV > 1.0) totalV else 25.6)
            )

            val tMos = obj.optDouble("tempMos", 0.0)
            val t1 = obj.optDouble("temp1", obj.optDouble("tempSensor1", 0.0))
            val t2 = obj.optDouble("temp2", obj.optDouble("tempSensor2", 0.0))

            val chgMos = obj.optBoolean("chg", obj.optBoolean("chargeMosEnabled", true))
            val dsgMos = obj.optBoolean("dsg", obj.optBoolean("dischargeMosEnabled", true))
            val bal = obj.optBoolean("bal", obj.optBoolean("balanceEnabled", false))
            val cycles = obj.optInt("cycleCount", 0)
            val packets = obj.optLong("packets", 0L)
            val gwIp = obj.optString("ip", obj.optString("phoneIp", ""))
            val rssi = obj.optInt("rssi", 0)

            val cellsArr = obj.optJSONArray("cells") ?: obj.optJSONArray("cellVoltages")
            val cells = mutableListOf<Double>()
            if (cellsArr != null) {
                for (i in 0 until cellsArr.length()) {
                    val v = cellsArr.optDouble(i, 0.0)
                    if (v > 0.1) cells.add(v)
                }
            }

            var minV = 99.0
            var maxV = 0.0
            var sumV = 0.0
            var minIdx = 0
            var maxIdx = 0
            for (i in cells.indices) {
                val v = cells[i]
                if (v > 1.0) {
                    if (v < minV) {
                        minV = v
                        minIdx = i + 1
                    }
                    if (v > maxV) {
                        maxV = v
                        maxIdx = i + 1
                    }
                    sumV += v
                }
            }
            val avgV = if (cells.isNotEmpty()) sumV / cells.size else obj.optDouble("cellAvg", 0.0)
            val deltaMv = if (cells.size >= 2) {
                ((maxV - minV) * 1000.0).roundToInt()
            } else {
                obj.optInt("cellDeltaMv", 0)
            }

            JkBmsState(
                connected = isConn,
                paused = false,
                statusText = if (isConn) "BMS Онлайн ($sourceLabel)" else "ESP32 ищет BMS по BLE...",
                connectionSource = sourceLabel,
                deviceName = obj.optString("deviceName", "JK-B2A8S20P"),
                deviceAddress = obj.optString("bmsMac", "C8:47:80:55:04:85"),
                socPercent = soc,
                packVoltage = totalV,
                currentAmps = curA,
                powerWatts = signedPwr,
                chargeWatts = chgW,
                dischargeWatts = dsgW,
                remainingCapacityAh = remAh,
                nominalCapacityAh = if (nomAh > 1.0) nomAh else 230.0,
                remainingEnergyWh = remWh,
                tempMos = tMos,
                tempSensor1 = t1,
                tempSensor2 = t2,
                cellVoltages = cells,
                cellDeltaMv = deltaMv,
                cellAvgV = avgV,
                minCellIndex = minIdx,
                maxCellIndex = maxIdx,
                chargeMosEnabled = chgMos,
                dischargeMosEnabled = dsgMos,
                balanceEnabled = bal,
                cycleCount = cycles,
                packetsCount = packets,
                gatewayIp = gwIp,
                wifiRssi = rssi,
                lastUpdatedMillis = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Разбор 300-байтного кадра телеметрии (тип 0x02) при прямом BLE-подключении
     */
    fun parseTelemetryFrame(frame: ByteArray, currentState: JkBmsState): JkBmsState? {
        if (frame.size < 250) return null
        if (frame[0] != 0x55.toByte() ||
            frame[1] != 0xAA.toByte() ||
            frame[2] != 0xEB.toByte() ||
            frame[3] != 0x90.toByte()
        ) {
            return null
        }

        val frameType = frame[4].toInt() and 0xFF
        if (frameType != 0x02) return null

        val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)

        val cells = mutableListOf<Double>()
        for (i in 0 until 24) {
            val offset = 6 + i * 2
            if (offset + 2 <= frame.size) {
                val mv = buf.getShort(offset).toInt() and 0xFFFF
                if (mv in 500..5500) {
                    cells.add(mv / 1000.0)
                }
            }
        }

        var minV = 99.0
        var maxV = 0.0
        var minIdx = 0
        var maxIdx = 0
        for (i in cells.indices) {
            val v = cells[i]
            if (v < minV) { minV = v; minIdx = i + 1 }
            if (v > maxV) { maxV = v; maxIdx = i + 1 }
        }

        val deltaMv = if (cells.size >= 2) {
            ((maxV - minV) * 1000.0).roundToInt()
        } else {
            val rawDelta = buf.getShort(62).toInt() and 0xFFFF
            if (rawDelta in 0..2000) rawDelta else 0
        }
        val avgV = if (cells.isNotEmpty()) cells.sum() / cells.size else 0.0

        val shift = detectOffsetShift(buf, cells.sum())

        val rawVoltage = buf.getInt(118 + shift).toLong() and 0xFFFFFFFFL
        val packVoltage = rawVoltage / 1000.0

        val rawCurrent = buf.getInt(126 + shift)
        val currentAmps = rawCurrent / 1000.0

        val tempT1 = readTemperature(buf, 130 + shift)
        val tempT2 = readTemperature(buf, 132 + shift)
        val tempMos = readTemperature(buf, if (shift == 0) 134 else 112 + shift)

        val soc = (frame[141 + shift].toInt() and 0xFF).coerceIn(0, 100)

        val remCapMah = buf.getInt(142 + shift).toLong() and 0xFFFFFFFFL
        val nomCapMah = buf.getInt(146 + shift).toLong() and 0xFFFFFFFFL
        val remCapAh = remCapMah / 1000.0
        val nomCapAh = nomCapMah / 1000.0

        val cycleCount = buf.getInt(150 + shift).coerceAtLeast(0)

        val chargeMos = (frame.getOrNull(166 + shift)?.toInt() ?: 1) != 0
        val dischargeMos = (frame.getOrNull(167 + shift)?.toInt() ?: 1) != 0
        val balanceActive = (frame.getOrNull(140 + shift)?.toInt() ?: 0) != 0

        val powerW = packVoltage * currentAmps
        val chargeW = if (powerW > 0.0) powerW else 0.0
        val dischargeW = if (powerW < 0.0) abs(powerW) else 0.0

        val nominalPackV = if (cells.isNotEmpty()) cells.size * 3.2 else packVoltage
        val remEnergyWh = remCapAh * (if (packVoltage > 1.0) packVoltage else nominalPackV)

        return currentState.copy(
            connected = true,
            statusText = "Подключено (Bluetooth BLE)",
            connectionSource = "🦷 Bluetooth BLE",
            socPercent = soc,
            packVoltage = packVoltage,
            currentAmps = currentAmps,
            powerWatts = powerW,
            chargeWatts = chargeW,
            dischargeWatts = dischargeW,
            remainingCapacityAh = remCapAh,
            nominalCapacityAh = if (nomCapAh > 1.0) nomCapAh else 230.0,
            remainingEnergyWh = remEnergyWh,
            tempMos = tempMos,
            tempSensor1 = tempT1,
            tempSensor2 = tempT2,
            cellVoltages = cells,
            cellDeltaMv = deltaMv,
            cellAvgV = avgV,
            minCellIndex = minIdx,
            maxCellIndex = maxIdx,
            chargeMosEnabled = chargeMos,
            dischargeMosEnabled = dischargeMos,
            balanceEnabled = balanceActive,
            cycleCount = cycleCount,
            packetsCount = currentState.packetsCount + 1,
            lastUpdatedMillis = System.currentTimeMillis()
        )
    }

    private fun detectOffsetShift(buf: ByteBuffer, cellsVoltageSum: Double): Int {
        val v0 = (buf.getInt(118).toLong() and 0xFFFFFFFFL) / 1000.0
        val soc0 = buf.get(141).toInt() and 0xFF

        val v32 = (buf.getInt(150).toLong() and 0xFFFFFFFFL) / 1000.0
        val soc32 = buf.get(173).toInt() and 0xFF

        if (cellsVoltageSum > 3.0) {
            val diff0 = abs(v0 - cellsVoltageSum)
            val diff32 = abs(v32 - cellsVoltageSum)
            if (diff0 < 2.5 && soc0 in 0..100) return 0
            if (diff32 < 2.5 && soc32 in 0..100) return 32
        }
        if (v0 in 5.0..100.0 && soc0 in 0..100) return 0
        if (v32 in 5.0..100.0 && soc32 in 0..100) return 32
        return 0
    }

    private fun readTemperature(buf: ByteBuffer, offset: Int): Double {
        if (offset + 2 > buf.limit()) return 0.0
        val raw = buf.getShort(offset).toInt()
        val temp = raw / 10.0
        return if (temp in -50.0..150.0) temp else 0.0
    }
}
