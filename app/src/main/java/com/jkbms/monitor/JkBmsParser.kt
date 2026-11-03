package com.jkbms.monitor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class JkBmsState(
    val connected: Boolean = false,
    val paused: Boolean = false,
    val statusText: String = "Ожидание подключения...",
    val deviceName: String = "",
    val deviceAddress: String = "",
    val socPercent: Int = 0,               // % заряда
    val packVoltage: Double = 0.0,         // В (Общее напряжение)
    val currentAmps: Double = 0.0,         // А (+ заряд, - разряд)
    val powerWatts: Double = 0.0,          // Вт (мгновенная мощность = V * A)
    val chargeWatts: Double = 0.0,         // Скорость зарядки (Вт / Вт·ч за час)
    val dischargeWatts: Double = 0.0,      // Скорость расхода (Вт / Вт·ч за час)
    val remainingCapacityAh: Double = 0.0, // Остаток ёмкости А·ч
    val nominalCapacityAh: Double = 0.0,   // Номинальная ёмкость А·ч
    val remainingEnergyWh: Double = 0.0,   // Остаток энергии Вт·ч
    val tempMos: Double = 0.0,             // Температура платы (MOS) °C
    val tempSensor1: Double = 0.0,         // Датчик температуры 1 (T1) °C
    val tempSensor2: Double = 0.0,         // Датчик температуры 2 (T2) °C
    val cellVoltages: List<Double> = emptyList(),
    val cellDeltaMv: Int = 0,              // Разбег ячеек в мВ
    val chargeMosEnabled: Boolean = false,
    val dischargeMosEnabled: Boolean = false,
    val balanceEnabled: Boolean = false,
    val cycleCount: Int = 0,
    val lastUpdatedMillis: Long = 0L
) {
    /**
     * Расчет примерного оставшегося времени (заряда или разряда)
     */
    fun getEstimatedTimeText(): String {
        if (abs(currentAmps) < 0.15) return "Покой (0 Вт)"
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
    val HEADER = byteArrayOf(0x55, 0xAA.toByte(), 0xEB.toByte(), 0x90.toByte())

    /**
     * Команда активации сведений об устройстве (0x97)
     */
    fun buildDeviceInfoCommand(): ByteArray = buildCommand(0x97.toByte())

    /**
     * Команда подписки на телеметрию ячеек и статуса (0x96)
     */
    fun buildCellInfoCommand(): ByteArray = buildCommand(0x96.toByte())

    private fun buildCommand(cmdCode: Byte): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        frame[2] = 0x90.toByte()
        frame[3] = 0xEB.toByte()
        frame[4] = cmdCode
        frame[5] = 0x00 // Длина
        var crc = 0
        for (i in 0 until 19) {
            crc = (crc + (frame[i].toInt() and 0xFF)) and 0xFF
        }
        frame[19] = crc.toByte()
        return frame
    }

    /**
     * Разбор 300-байтного кадра телеметрии (тип 0x02) от JK BMS (включая B2A8S20P).
     * Автоматически определяет смещение протокола (32-bit vs 64-bit uptime).
     */
    fun parseTelemetryFrame(frame: ByteArray, currentState: JkBmsState): JkBmsState? {
        if (frame.size < 250) return null
        // Проверяем заголовок 55 AA EB 90
        if (frame[0] != 0x55.toByte() ||
            frame[1] != 0xAA.toByte() ||
            frame[2] != 0xEB.toByte() ||
            frame[3] != 0x90.toByte()
        ) {
            return null
        }

        val frameType = frame[4].toInt() and 0xFF
        if (frameType != 0x02) {
            // Нас интересуют только кадры статуса 0x02 (Cell Info)
            return null
        }

        val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Читаем напряжения ячеек (до 24 ячеек, начиная с байта 6, по 2 байта uint16 в мВ)
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

        val deltaMv = if (cells.size >= 2) {
            val minV = cells.minOrNull() ?: 0.0
            val maxV = cells.maxOrNull() ?: 0.0
            ((maxV - minV) * 1000.0).toInt()
        } else {
            val rawDelta = buf.getShort(62).toInt() and 0xFFFF
            if (rawDelta in 0..2000) rawDelta else 0
        }

        // 2. Определяем смещение протокола:
        // В стандартных B2A8S20P (HW v10/v11) смещение 0 (напряжение на 118, ток на 126, SOC на 141)
        // В новых ревизиях со сдвигом 32 бит смещение +32 (напряжение на 150, ток на 158, SOC на 173)
        val shift = detectOffsetShift(buf, cells.sum())

        val rawVoltage = buf.getInt(118 + shift).toLong() and 0xFFFFFFFFL
        val packVoltage = rawVoltage / 1000.0

        val rawCurrent = buf.getInt(126 + shift) // Знаковое 32-битное число в мА
        val currentAmps = rawCurrent / 1000.0

        // Температуры (int16 в 0.1 °C)
        val tempT1 = readTemperature(buf, 130 + shift)
        val tempT2 = readTemperature(buf, 132 + shift)
        val tempMos = readTemperature(buf, if (shift == 0) 134 else 112 + shift)

        // Заряд в % (SoC)
        val soc = (frame[141 + shift].toInt() and 0xFF).coerceIn(0, 100)

        // Остаток и номинал ёмкости (uint32 в мА·ч)
        val remCapMah = buf.getInt(142 + shift).toLong() and 0xFFFFFFFFL
        val nomCapMah = buf.getInt(146 + shift).toLong() and 0xFFFFFFFFL
        val remCapAh = remCapMah / 1000.0
        val nomCapAh = nomCapMah / 1000.0

        val cycleCount = buf.getInt(150 + shift).coerceAtLeast(0)

        // Статусы ключей MOS (заряд/разряд/балансир)
        val chargeMos = (frame.getOrNull(166 + shift)?.toInt() ?: 1) != 0
        val dischargeMos = (frame.getOrNull(167 + shift)?.toInt() ?: 1) != 0
        val balanceActive = (frame.getOrNull(140 + shift)?.toInt() ?: 0) != 0

        // Мощность (Вт = Вт·ч за 1 час)
        val powerW = packVoltage * currentAmps
        val chargeW = if (powerW > 0.0) powerW else 0.0
        val dischargeW = if (powerW < 0.0) abs(powerW) else 0.0

        // Оценка запаса энергии в Вт·ч
        val nominalCellV = if (cells.size in 3..4) 3.2 else if (cells.size in 7..8) 3.2 else 3.2
        val nominalPackV = if (cells.isNotEmpty()) cells.size * nominalCellV else packVoltage
        val remEnergyWh = remCapAh * (if (packVoltage > 1.0) packVoltage else nominalPackV)

        return currentState.copy(
            connected = true,
            statusText = "Подключено",
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
            chargeMosEnabled = chargeMos,
            dischargeMosEnabled = dischargeMos,
            balanceEnabled = balanceActive,
            cycleCount = cycleCount,
            lastUpdatedMillis = System.currentTimeMillis()
        )
    }

    private fun detectOffsetShift(buf: ByteBuffer, cellsVoltageSum: Double): Int {
        // Проверяем смещение 0 (байт 118) и смещение +32 (байт 150)
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
