package com.jkbms.monitor

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

data class JkBmsState(
    val connected: Boolean = false,
    val paused: Boolean = false,
    val blePausedOnEsp: Boolean = false,
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
    val balanceEnabled: Boolean = true,
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
    private const val PADDING_PATTERN_U32 = 0x54412E51L // "Q.AT" = 1413548625 (1413548.625)

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
     * Включает жесткую фильтрацию на случай, если на ESP32 еще старая прошивка v2.0 или в MQTT остался старый пакет.
     */
    fun parseGatewayJson(jsonStr: String, sourceLabel: String): JkBmsState? {
        return try {
            val obj = JSONObject(jsonStr)
            val espBlePaused = obj.optBoolean("blePaused", false)
            val isConn = !espBlePaused && (obj.optBoolean("connected", false) || obj.optBoolean("hasData", false))

            val cellsArr = obj.optJSONArray("cells") ?: obj.optJSONArray("cellVoltages")
            val cells = mutableListOf<Double>()
            if (cellsArr != null) {
                for (i in 0 until cellsArr.length()) {
                    val v = cellsArr.optDouble(i, 0.0)
                    if (v in 1.0..5.0) cells.add(v)
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
                obj.optInt("cellDeltaMv", 0).coerceIn(0, 2000)
            }

            val rawTotalV = obj.optDouble("totalVoltage", obj.optDouble("packVoltage", 0.0))
            val rawCurA = obj.optDouble("current", obj.optDouble("currentAmps", 0.0))

            // Если в пакете старой прошивки ESP32 пришел мусорный хвост 1413548.63 ("Q.AT")
            val isCorruptedFrame = rawTotalV > 60.0 || abs(rawCurA) > 400.0 ||
                (sumV > 10.0 && abs(rawTotalV - sumV) > 4.0)

            val totalV = if (!isCorruptedFrame && rawTotalV in 8.0..45.0) {
                rawTotalV
            } else if (sumV > 8.0) {
                sumV
            } else {
                0.0
            }

            val curA = if (!isCorruptedFrame && abs(rawCurA) <= 350.0) rawCurA else 0.0
            val signedPwr = totalV * curA
            val chgW = if (signedPwr > 0.0) signedPwr else 0.0
            val dsgW = if (signedPwr < 0.0) abs(signedPwr) else 0.0

            val rawSoc = obj.optInt("soc", obj.optInt("socPercent", 0)).coerceIn(0, 100)
            val rawNomAh = obj.optDouble("nominalCap", obj.optDouble("nominalCapacityAh", 230.0))
            val nomAh = if (!isCorruptedFrame && rawNomAh in 10.0..1000.0) rawNomAh else 230.0

            val rawRemAh = obj.optDouble("remainCap", obj.optDouble("remainingCapacityAh", 0.0))
            val remAh = if (!isCorruptedFrame && rawRemAh in 0.0..(nomAh * 1.1)) {
                rawRemAh
            } else {
                nomAh * (rawSoc / 100.0)
            }

            val remWh = remAh * (if (totalV > 8.0) totalV else 25.6)

            val rawTMos = obj.optDouble("tempMos", 0.0)
            val rawT1 = obj.optDouble("temp1", obj.optDouble("tempSensor1", 0.0))
            val rawT2 = obj.optDouble("temp2", obj.optDouble("tempSensor2", 0.0))

            val tMos = if (!isCorruptedFrame && rawTMos in -40.0..130.0) rawTMos else 25.0
            val t1 = if (!isCorruptedFrame && rawT1 in -40.0..130.0) rawT1 else 24.0
            val t2 = if (!isCorruptedFrame && rawT2 in -40.0..130.0) rawT2 else 24.0

            val chgMos = obj.optBoolean("chg", obj.optBoolean("chargeMosEnabled", true))
            val dsgMos = obj.optBoolean("dsg", obj.optBoolean("dischargeMosEnabled", true))
            val bal = obj.optBoolean("bal", obj.optBoolean("balanceEnabled", true))
            val rawCycles = obj.optInt("cycleCount", 0)
            val cycles = if (!isCorruptedFrame && rawCycles in 0..50000) rawCycles else 0
            val packets = obj.optLong("packets", 0L)
            val gwIp = obj.optString("ip", obj.optString("phoneIp", ""))
            val rssi = obj.optInt("rssi", 0)

            val statusLabel = when {
                espBlePaused -> "⏸ BLE платы отключен (свободно для офиц. JK App)"
                isConn -> "BMS Онлайн ($sourceLabel)"
                else -> "ESP32 ищет BMS по BLE..."
            }

            JkBmsState(
                connected = isConn,
                paused = espBlePaused,
                blePausedOnEsp = espBlePaused,
                statusText = statusLabel,
                connectionSource = sourceLabel,
                deviceName = obj.optString("deviceName", "JK-B2A8S20P"),
                deviceAddress = obj.optString("bmsMac", "C8:47:80:55:04:85"),
                socPercent = rawSoc,
                packVoltage = totalV,
                currentAmps = curA,
                powerWatts = signedPwr,
                chargeWatts = chgW,
                dischargeWatts = dsgW,
                remainingCapacityAh = remAh,
                nominalCapacityAh = nomAh,
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
     * Разбор 300-байтного кадра телеметрии (тип 0x01 или 0x02) при прямом BLE-подключении
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

        // Проверка CRC-8 если кадр полный (300 байт)
        if (frame.size >= 300) {
            var crc = 0
            for (i in 0 until 299) {
                crc = (crc + (frame[i].toInt() and 0xFF)) and 0xFF
            }
            val expected = frame[299].toInt() and 0xFF
            if (crc != expected) return null
        }

        val frameType = frame[4].toInt() and 0xFF
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)

        // Кадр настроек 0x01: точное состояние переключателей Заряд / Разряд / Балансировка
        if (frameType == 0x01) {
            val rawChg = buf.getInt(118).toLong() and 0xFFFFFFFFL
            val rawDsg = buf.getInt(122).toLong() and 0xFFFFFFFFL
            val rawBal = buf.getInt(126).toLong() and 0xFFFFFFFFL
            if (rawChg <= 1L && rawDsg <= 1L && rawBal <= 1L) {
                return currentState.copy(
                    chargeMosEnabled = rawChg != 0L,
                    dischargeMosEnabled = rawDsg != 0L,
                    balanceEnabled = rawBal != 0L
                )
            }
            return null
        }

        if (frameType != 0x02) return null

        val cells = mutableListOf<Double>()
        for (i in 0 until 8) {
            val offset = 6 + i * 2
            if (offset + 2 <= frame.size) {
                val mv = buf.getShort(offset).toInt() and 0xFFFF
                if (mv in 1500..4500) {
                    cells.add(mv / 1000.0)
                }
            }
        }

        if (cells.size < 4) return null

        var minV = 99.0
        var maxV = 0.0
        var minIdx = 0
        var maxIdx = 0
        for (i in cells.indices) {
            val v = cells[i]
            if (v < minV) { minV = v; minIdx = i + 1 }
            if (v > maxV) { maxV = v; maxIdx = i + 1 }
        }

        val cellsSum = cells.sum()
        val deltaMv = ((maxV - minV) * 1000.0).roundToInt()
        val avgV = cellsSum / cells.size

        val shift = detectOffsetShift(buf, cellsSum)

        val rawVoltage = buf.getInt(118 + shift).toLong() and 0xFFFFFFFFL
        val rawCurrentU32 = buf.getInt(126 + shift).toLong() and 0xFFFFFFFFL
        if (rawVoltage == PADDING_PATTERN_U32 || rawCurrentU32 == PADDING_PATTERN_U32) {
            return null
        }

        val packVoltage = rawVoltage / 1000.0
        if (packVoltage !in 10.0..42.0 || abs(packVoltage - cellsSum) > 3.5) {
            return null
        }

        val currentAmps = buf.getInt(126 + shift) / 1000.0
        if (abs(currentAmps) > 350.0) {
            return null
        }

        val tempT1 = readTemperature(buf, 130 + shift)
        val tempT2 = readTemperature(buf, 132 + shift)
        val tempMos = readTemperature(buf, if (shift == 0) 134 else 112 + shift)

        val rawSoc = frame[141 + shift].toInt() and 0xFF
        if (rawSoc > 100) return null
        val soc = rawSoc.coerceIn(0, 100)

        val remCapMah = buf.getInt(142 + shift).toLong() and 0xFFFFFFFFL
        val nomCapMah = buf.getInt(146 + shift).toLong() and 0xFFFFFFFFL
        val nomCapAh = if (nomCapMah != PADDING_PATTERN_U32 && nomCapMah in 5000L..1000000L) {
            nomCapMah / 1000.0
        } else {
            230.0
        }
        val remCapAh = if (remCapMah != PADDING_PATTERN_U32 && remCapMah <= 1000000L) {
            (remCapMah / 1000.0).coerceAtMost(nomCapAh * 1.1)
        } else {
            nomCapAh * (soc / 100.0)
        }

        val rawCycles = buf.getInt(150 + shift).toLong() and 0xFFFFFFFFL
        val cycleCount = if (rawCycles != PADDING_PATTERN_U32 && rawCycles in 0L..50000L) {
            rawCycles.toInt()
        } else {
            0
        }

        val rawChgByte = frame.getOrNull(166 + shift)?.toInt()?.and(0xFF) ?: 255
        val rawDsgByte = frame.getOrNull(167 + shift)?.toInt()?.and(0xFF) ?: 255
        val chargeMos = if (rawChgByte <= 1) (rawChgByte != 0) else currentState.chargeMosEnabled
        val dischargeMos = if (rawDsgByte <= 1) (rawDsgByte != 0) else currentState.dischargeMosEnabled

        val powerW = packVoltage * currentAmps
        val chargeW = if (powerW > 0.0) powerW else 0.0
        val dischargeW = if (powerW < 0.0) abs(powerW) else 0.0
        val remEnergyWh = remCapAh * packVoltage

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
            nominalCapacityAh = nomCapAh,
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
            cycleCount = cycleCount,
            packetsCount = currentState.packetsCount + 1,
            lastUpdatedMillis = System.currentTimeMillis()
        )
    }

    private fun detectOffsetShift(buf: ByteBuffer, cellsVoltageSum: Double): Int {
        val rawV0 = buf.getInt(118).toLong() and 0xFFFFFFFFL
        val rawV32 = buf.getInt(150).toLong() and 0xFFFFFFFFL
        if (rawV32 == PADDING_PATTERN_U32) return 0
        if (rawV0 == PADDING_PATTERN_U32) return 32

        val v0 = rawV0 / 1000.0
        val soc0 = buf.get(141).toInt() and 0xFF

        val v32 = rawV32 / 1000.0
        val soc32 = buf.get(173).toInt() and 0xFF

        if (cellsVoltageSum > 8.0) {
            val diff0 = abs(v0 - cellsVoltageSum)
            val diff32 = abs(v32 - cellsVoltageSum)
            if (diff0 < 2.5 && soc0 in 0..100) return 0
            if (diff32 < 2.5 && soc32 in 0..100) return 32
        }
        if (v0 in 10.0..45.0 && soc0 in 0..100) return 0
        if (v32 in 10.0..45.0 && soc32 in 0..100) return 32
        return 0
    }

    private fun readTemperature(buf: ByteBuffer, offset: Int): Double {
        if (offset + 2 > buf.limit()) return 0.0
        val raw = buf.getShort(offset).toInt()
        val temp = raw / 10.0
        return if (temp in -45.0..130.0) temp else 0.0
    }
}
