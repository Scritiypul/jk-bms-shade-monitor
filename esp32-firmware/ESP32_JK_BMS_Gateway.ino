/*
 * ============================================================================
 * ESP32 JK-BMS Wi-Fi + Cloud + BLE Шлюз v2.2 (Стабильный парсер + Управление)
 * ============================================================================
 * Плата:       ESP32 Dev Module (ESP32-WROOM-32, CP2102)
 * Разделы:     Huge APP (3MB No OTA / 1MB SPIFFS)
 * Библиотеки:  NimBLE-Arduino (by h2zero) — других внешних библиотек НЕ требуется!
 *
 * Что исправлено и улучшено в версии 2.2:
 * 1. ИСПРАВЛЕНЫ "СУМАСШЕДШИЕ" ЗНАЧЕНИЯ (1413548.63 В / А):
 *    - Жесткая проверка контрольной суммы CRC-8 каждого 300-байтного кадра JK BMS.
 *    - Скользящий поиск заголовка 55 AA EB 90 без потери фрагментов BLE-пакетов.
 *    - Приоритет смещения JK02_24S (shift = 0) для JK-B2A8S20P и фильтрация
 *      мусорного заполнения 0x51 0x2E 0x41 0x54 (1413548.625).
 *    - Сверка общего напряжения батареи с суммой 8 ячеек.
 * 2. СИНХРОНИЗАЦИЯ ТУМБЛЕРОВ (Заряд / Разряд / Балансировка):
 *    - Чтение как кадра ячеек (0x02), так и кадра настроек (0x01) для точного
 *      отображения состояния ключей Charge, Discharge и Balancer.
 *    - Задержка 3.5 сек после переключения тумблера, чтобы старые пакеты в полете
 *      не возвращали тумблер назад.
 * 3. СТАБИЛЬНОЕ BLE-СОЕДИНЕНИЕ БЕЗ ОШИБКИ 520 ("Сервис FFE0 не найден"):
 *    - Убрано замедление параметров BLE во время поиска сервисов GATT.
 * 4. МГНОВЕННОЕ ОТКЛЮЧЕНИЕ ПЛАТЫ ОТ BMS ПО КНОПКЕ:
 *    - Кнопка "Отключить плату от BMS" мгновенно разрывает BLE-связь и держит
 *      паузу, не пытаясь переподключиться, пока вы не нажмете "Подключить обратно".
 * ============================================================================
 */

#include <WiFi.h>
#include <WebServer.h>
#include <ESPmDNS.h>
#include <esp_wifi.h>
#include <esp_coexist.h>
#include <NimBLEDevice.h>

// ==================== НАСТРОЙКИ ПОЛЬЗОВАТЕЛЯ ====================
const char* WIFI_SSID   = "Sweet Home";
const char* WIFI_PASS   = "ПАРОЛЬ";              // <-- Ваш пароль от домашнего Wi-Fi
const char* BMS_MAC     = "c8:47:80:55:04:85";   // MAC вашей JK-B2A8S20P
const char* MDNS_NAME   = "jkbms";               // Доступ по http://jkbms.local
const char* CONTROL_PIN = "1234";                // Пароль/PIN для управления ключами BMS

// Облачный канал для доступа из любой точки мира (уникальный по MAC вашей BMS)
const char* MQTT_HOST        = "broker.emqx.io";
const uint16_t MQTT_PORT     = 1883;
const char* MQTT_TOPIC_STATE = "jkbms/c84780550485/status";
const char* MQTT_TOPIC_CMD   = "jkbms/c84780550485/cmd";
// ================================================================

WebServer server(80);
WiFiClient mqttNet;

static NimBLEUUID serviceUUID("ffe0");
static NimBLEUUID charNotifyUUID("ffe1");
static NimBLEUUID charWriteUUID("ffe2");

volatile bool bmsConnected = false;
volatile bool blePaused    = false;
volatile unsigned long blePausedAtMs      = 0;
volatile unsigned long lastParsedMs       = 0;
volatile unsigned long lastHttpReqMs      = 0;
volatile unsigned long lastControlCmdMs   = 0;
volatile bool needImmediateCloudPublish   = false;

// Очередь команд управления BMS (выполняется в задаче BLETask)
volatile uint8_t pendingCmdReg = 0;
volatile uint32_t pendingCmdVal = 0;
volatile bool hasPendingCmd = false;

// Сохраненное состояние переключателей из кадра настроек 0x01
volatile bool hasSettingsFrame = false;
volatile bool settingsBalState = false;

NimBLERemoteCharacteristic* pNotifyChar = nullptr;
NimBLERemoteCharacteristic* pWriteChar  = nullptr;
NimBLEClient* pClient = nullptr;

portMUX_TYPE bmsMux = portMUX_INITIALIZER_UNLOCKED;

struct BMSData {
  float cells[8] = {0};
  int cellCount = 8;
  float cellMin = 0;
  float cellMax = 0;
  float cellAvg = 0;
  float voltDiff = 0;
  int minCellIdx = 0;
  int maxCellIdx = 0;
  float totalVoltage = 0;
  float current = 0;
  float power = 0;
  float balanceCurrent = 0;
  int soc = 0;
  float remainCap = 0;
  float nominalCap = 230.0f;
  int cycleCount = 0;
  float tempMos = 0;
  float temp1 = 0;
  float temp2 = 0;
  bool chg = true;
  bool dsg = true;
  bool bal = true;
  uint32_t packets = 0;
  unsigned long lastUpdateMs = 0;
} bms;

uint8_t frameBuf[320];
int framePos = 0;
uint8_t prevB0 = 0, prevB1 = 0, prevB2 = 0;

static uint32_t readU32LE(const uint8_t* d, int offset) {
  return (uint32_t)d[offset] |
         ((uint32_t)d[offset + 1] << 8) |
         ((uint32_t)d[offset + 2] << 16) |
         ((uint32_t)d[offset + 3] << 24);
}

static float readTemp16(const uint8_t* d, int offset) {
  int16_t raw = (int16_t)(d[offset] | (d[offset + 1] << 8));
  float t = raw * 0.1f;
  return (t >= -45.0f && t <= 130.0f) ? t : 0.0f;
}

// Определение смещения полей (для JK-B2A8S20P используется протокол JK02_24S: shift = 0)
static int detectOffsetShift(const uint8_t* d, float cellsSum) {
  uint32_t rawV0  = readU32LE(d, 118);
  uint32_t rawV32 = readU32LE(d, 150);

  // Защита от паттерна заполнения 0x51 0x2E 0x41 0x54 (0x54412E51 = 1413548625)
  if (rawV32 == 0x54412E51UL) return 0;
  if (rawV0  == 0x54412E51UL) return 32;

  float v0  = rawV0 * 0.001f;
  float v32 = rawV32 * 0.001f;
  uint8_t soc0  = d[141];
  uint8_t soc32 = d[173];

  if (cellsSum > 8.0f) {
    float diff0  = fabs(v0 - cellsSum);
    float diff32 = fabs(v32 - cellsSum);
    if (diff0  < 2.5f && soc0  <= 100) return 0;
    if (diff32 < 2.5f && soc32 <= 100) return 32;
  }
  if (v0  >= 10.0f && v0  <= 45.0f && soc0  <= 100) return 0;
  if (v32 >= 10.0f && v32 <= 45.0f && soc32 <= 100) return 32;
  return 0;
}

// Разбор кадра настроек 0x01 (для 100% точного состояния тумблеров Заряд / Разряд / Балансир)
void parseJKSettingsFrame(const uint8_t* d) {
  uint32_t rawChg = readU32LE(d, 118);
  uint32_t rawDsg = readU32LE(d, 122);
  uint32_t rawBal = readU32LE(d, 126);

  if (rawChg <= 1 && rawDsg <= 1 && rawBal <= 1) {
    hasSettingsFrame = true;
    settingsBalState = (rawBal != 0);
    if (millis() - lastControlCmdMs > 3500UL) {
      portENTER_CRITICAL(&bmsMux);
      bms.chg = (rawChg != 0);
      bms.dsg = (rawDsg != 0);
      bms.bal = (rawBal != 0);
      portEXIT_CRITICAL(&bmsMux);
    }
  }
}

// Разбор основного кадра телеметрии ячеек 0x02 (300 байт, проверенный по CRC-8)
void parseJKFrame(const uint8_t* d) {
  float tempCells[8] = {0};
  float minC = 99.0f, maxC = 0.0f, sumC = 0.0f;
  int validCells = 0;
  int minIdx = 0, maxIdx = 0;

  for (int i = 0; i < 8; i++) {
    uint16_t mv = (uint16_t)d[6 + i * 2] | ((uint16_t)d[7 + i * 2] << 8);
    float cv = mv * 0.001f;
    if (cv >= 1.5f && cv <= 4.5f) {
      tempCells[i] = cv;
      if (cv < minC) { minC = cv; minIdx = i + 1; }
      if (cv > maxC) { maxC = cv; maxIdx = i + 1; }
      sumC += cv;
      validCells++;
    }
  }

  // Для 8S батареи должно быть минимум 4 валидные ячейки
  if (validCells < 4 || sumC < 10.0f || sumC > 38.0f) {
    return;
  }

  int shift = detectOffsetShift(d, sumC);

  uint32_t totMv = readU32LE(d, 118 + shift);
  uint32_t rawCur = readU32LE(d, 126 + shift);
  int32_t curMa = (int32_t)rawCur;

  // Защита от мусорных байтов заполнения 0x51 0x2E 0x41 0x54 (1413548.625)
  if (totMv == 0x54412E51UL || rawCur == 0x54412E51UL) {
    return;
  }

  float totV = totMv * 0.001f;
  // Если общее напряжение оторвано от суммы ячеек более чем на 3.5 В — берем сумму ячеек или отбрасываем искаженный кадр
  if (totV < 10.0f || totV > 40.0f || fabs(totV - sumC) > 3.5f) {
    return;
  }

  float curA = curMa * 0.001f;
  if (fabs(curA) > 350.0f) {
    return;
  }

  float t1   = readTemp16(d, 130 + shift);
  float t2   = readTemp16(d, 132 + shift);
  float tMos = readTemp16(d, (shift == 0) ? 134 : (112 + shift));

  int16_t balMa = (int16_t)(d[138 + shift] | (d[139 + shift] << 8));
  float balCurA = balMa * 0.001f;
  if (fabs(balCurA) > 5.0f) balCurA = 0.0f;

  uint8_t rawBalByte = d[140 + shift];
  bool balActive = (rawBalByte == 1 || rawBalByte == 2);

  int socVal = d[141 + shift];
  if (socVal > 100) return;

  uint32_t remMah = readU32LE(d, 142 + shift);
  uint32_t nomMah = readU32LE(d, 146 + shift);
  uint32_t cycles = readU32LE(d, 150 + shift);

  float nomAh = (nomMah != 0x54412E51UL && nomMah >= 5000 && nomMah <= 1000000) ? (nomMah * 0.001f) : 230.0f;
  float remAh = (remMah != 0x54412E51UL && remMah <= 1000000) ? (remMah * 0.001f) : (nomAh * socVal * 0.01f);
  if (remAh > nomAh * 1.1f) remAh = nomAh * socVal * 0.01f;
  if (cycles == 0x54412E51UL || cycles > 50000) cycles = 0;

  // В JK02_24S байты 166 и 167 всегда равны 0 или 1 (состояние ключей Заряда и Разряда)
  uint8_t rawChgByte = d[166 + shift];
  uint8_t rawDsgByte = d[167 + shift];

  unsigned long now = millis();
  bool allowSwitchOverwrite = (now - lastControlCmdMs > 3500UL);

  portENTER_CRITICAL(&bmsMux);
  for (int i = 0; i < 8; i++) {
    if (tempCells[i] > 0.5f) bms.cells[i] = tempCells[i];
  }
  bms.cellMin = minC;
  bms.cellMax = maxC;
  bms.cellAvg = sumC / validCells;
  bms.voltDiff = maxC - minC;
  bms.minCellIdx = minIdx;
  bms.maxCellIdx = maxIdx;
  bms.totalVoltage = totV;
  bms.current = curA;
  bms.power = totV * curA;
  bms.temp1 = t1;
  bms.temp2 = t2;
  bms.tempMos = tMos;
  bms.balanceCurrent = balCurA;
  bms.soc = socVal;
  bms.remainCap = remAh;
  bms.nominalCap = nomAh;
  bms.cycleCount = (int)cycles;

  if (allowSwitchOverwrite) {
    if (rawChgByte <= 1) bms.chg = (rawChgByte != 0);
    if (rawDsgByte <= 1) bms.dsg = (rawDsgByte != 0);
    if (hasSettingsFrame) {
      bms.bal = settingsBalState;
    } else if (rawBalByte <= 2) {
      bms.bal = balActive;
    }
  }

  bms.packets++;
  bms.lastUpdateMs = now;
  portEXIT_CRITICAL(&bmsMux);

  lastParsedMs = now;
}

void notifyCallback(NimBLERemoteCharacteristic* pChar, uint8_t* pData, size_t length, bool isNotify) {
  for (size_t i = 0; i < length; i++) {
    uint8_t b = pData[i];

    // Скользящее обнаружение заголовка 55 AA EB 90 в любом месте потока байтов
    if (prevB0 == 0x55 && prevB1 == 0xAA && prevB2 == 0xEB && b == 0x90) {
      frameBuf[0] = 0x55;
      frameBuf[1] = 0xAA;
      frameBuf[2] = 0xEB;
      frameBuf[3] = 0x90;
      framePos = 4;
      prevB0 = prevB1; prevB1 = prevB2; prevB2 = b;
      continue;
    }
    prevB0 = prevB1;
    prevB1 = prevB2;
    prevB2 = b;

    if (framePos >= 4 && framePos < 300) {
      frameBuf[framePos++] = b;
      if (framePos == 300) {
        // Проверяем контрольную сумму CRC-8 (сумма байтов 0..298 должна совпадать с байтом 299)
        uint8_t crc = 0;
        for (int j = 0; j < 299; j++) {
          crc += frameBuf[j];
        }
        if (crc == frameBuf[299]) {
          uint8_t fType = frameBuf[4];
          if (fType == 0x01) {
            parseJKSettingsFrame(frameBuf);
          } else if (fType == 0x02) {
            parseJKFrame(frameBuf);
          }
        }
        framePos = 0;
      }
    }
  }
}

class ClientCallbacks : public NimBLEClientCallbacks {
  void onConnect(NimBLEClient* pCli) {
    bmsConnected = true;
    Serial.println("JK BMS BLE Connected!");
  }
  void onDisconnect(NimBLEClient* pCli) {
    bmsConnected = false;
    Serial.println("[BLE] JK BMS отключилась.");
  }
  void onDisconnect(NimBLEClient* pCli, int reason) {
    bmsConnected = false;
    Serial.printf("[BLE] JK BMS отключилась (reason: %d)\n", reason);
  }
};

static ClientCallbacks clientCB;

void sendJKCommand(uint8_t reg, uint32_t val, uint8_t len) {
  NimBLERemoteCharacteristic* targetChar = pWriteChar ? pWriteChar : pNotifyChar;
  if (!bmsConnected || !targetChar) return;

  uint8_t cmd[20] = {0};
  cmd[0] = 0xAA;
  cmd[1] = 0x55;
  cmd[2] = 0x90;
  cmd[3] = 0xEB;
  cmd[4] = reg;
  cmd[5] = len;
  cmd[6] = val & 0xFF;
  cmd[7] = (val >> 8) & 0xFF;
  cmd[8] = (val >> 16) & 0xFF;
  cmd[9] = (val >> 24) & 0xFF;

  uint8_t crc = 0;
  for (int i = 0; i < 19; i++) crc += cmd[i];
  cmd[19] = crc;

  targetChar->writeValue(cmd, 20, false);
}

// Постановка команды управления BMS в очередь (протокол JK02 BLE: 0x1D = Charge, 0x1E = Discharge, 0x1F = Balance)
bool queueBMSControl(const String& cmdName, int val) {
  if (cmdName == "chg") {
    pendingCmdReg = 0x1D; // Ключ Заряда (Charge MOS по BLE)
    pendingCmdVal = (val != 0) ? 1 : 0;
    lastControlCmdMs = millis();
    hasPendingCmd = true;
    portENTER_CRITICAL(&bmsMux);
    bms.chg = (val != 0);
    portEXIT_CRITICAL(&bmsMux);
    needImmediateCloudPublish = true;
    return true;
  } else if (cmdName == "dsg") {
    pendingCmdReg = 0x1E; // Ключ Разряда (Discharge MOS по BLE)
    pendingCmdVal = (val != 0) ? 1 : 0;
    lastControlCmdMs = millis();
    hasPendingCmd = true;
    portENTER_CRITICAL(&bmsMux);
    bms.dsg = (val != 0);
    portEXIT_CRITICAL(&bmsMux);
    needImmediateCloudPublish = true;
    return true;
  } else if (cmdName == "bal") {
    pendingCmdReg = 0x1F; // Балансировка (Balancer по BLE)
    pendingCmdVal = (val != 0) ? 1 : 0;
    lastControlCmdMs = millis();
    hasPendingCmd = true;
    settingsBalState = (val != 0);
    hasSettingsFrame = true;
    portENTER_CRITICAL(&bmsMux);
    bms.bal = (val != 0);
    portEXIT_CRITICAL(&bmsMux);
    needImmediateCloudPublish = true;
    return true;
  } else if (cmdName == "refresh") {
    pendingCmdReg = 0x96;
    pendingCmdVal = 0;
    hasPendingCmd = true;
    return true;
  } else if (cmdName == "ble") {
    // val = 0 -> принудительно разорвать BLE-связь ESP32 с BMS (для входа через официальное приложение JK)
    // val = 1 -> вернуть BLE-связь ESP32 с BMS
    if (val == 0) {
      blePaused = true;
      blePausedAtMs = millis();
      bmsConnected = false;
      hasPendingCmd = false;
      Serial.println("[BLE CMD] Принудительное отключение BLE от BMS (освобождение для офиц. приложения)...");
    } else {
      blePaused = false;
      Serial.println("[BLE CMD] Возобновление BLE-соединения ESP32 с BMS...");
    }
    needImmediateCloudPublish = true;
    return true;
  }
  return false;
}

bool connectToBMS() {
  if (blePaused || WiFi.status() != WL_CONNECTED) {
    return false;
  }

  esp_coex_preference_set(ESP_COEX_PREFER_WIFI);

  if (!pClient) {
    pClient = NimBLEDevice::createClient();
    pClient->setClientCallbacks(&clientCB, false);
    // Быстрые параметры во время установки связи и обнаружения сервисов FFE0 (без таймаута 520!)
    pClient->setConnectionParams(12, 24, 0, 400);
    pClient->setConnectTimeout(6);
  }

  if (pClient->isConnected()) {
    if (blePaused) {
      pClient->disconnect();
      bmsConnected = false;
      return false;
    }
    bmsConnected = true;
    return true;
  }

  Serial.printf("[BLE] Подключение к JK BMS (%s)...\n", BMS_MAC);

  NimBLEAddress addrPub(BMS_MAC, BLE_ADDR_PUBLIC);
  bool ok = pClient->connect(addrPub);
  if (!ok && !blePaused) {
    vTaskDelay(pdMS_TO_TICKS(250));
    NimBLEAddress addrRand(BMS_MAC, BLE_ADDR_RANDOM);
    ok = pClient->connect(addrRand);
  }

  if (!ok || blePaused) {
    if (pClient->isConnected()) pClient->disconnect();
    bmsConnected = false;
    if (!blePaused) {
      Serial.println("[BLE] Не удалось подключиться к BMS.");
    }
    return false;
  }

  NimBLERemoteService* pSvc = pClient->getService(serviceUUID);
  if (!pSvc) {
    Serial.println("[BLE] Сервис FFE0 не найден!");
    pClient->disconnect();
    bmsConnected = false;
    return false;
  }

  pNotifyChar = pSvc->getCharacteristic(charNotifyUUID);
  if (!pNotifyChar) {
    Serial.println("[BLE] Характеристика FFE1 не найдена!");
    pClient->disconnect();
    bmsConnected = false;
    return false;
  }

  pWriteChar = pSvc->getCharacteristic(charWriteUUID);
  if (!pWriteChar) {
    pWriteChar = pNotifyChar;
  }

  framePos = 0;
  if (pNotifyChar->canNotify()) {
    pNotifyChar->subscribe(true, notifyCallback);
  }

  bmsConnected = true;

  vTaskDelay(pdMS_TO_TICKS(250));
  sendJKCommand(0x97, 0, 0); // Запрос кадра настроек 0x01
  vTaskDelay(pdMS_TO_TICKS(350));
  sendJKCommand(0x96, 0, 0); // Запрос кадра ячеек 0x02
  lastParsedMs = millis();

  // После успешного открытия потока ставим щадящий интервал без slave latency (чтобы не было разрывов 520)
  pClient->updateConnParams(24, 48, 0, 400);

  Serial.println("[BLE] Канал открыт! Wi-Fi и BLE работают стабильно.");
  needImmediateCloudPublish = true;
  return true;
}

String buildStatusJson() {
  BMSData snap;
  portENTER_CRITICAL(&bmsMux);
  snap = bms;
  portEXIT_CRITICAL(&bmsMux);

  bool isFresh = bmsConnected && !blePaused && (snap.packets > 0) && ((millis() - snap.lastUpdateMs) < 25000);
  float absPower = fabs(snap.power);
  float chgWatts = (snap.power > 0.0f) ? snap.power : 0.0f;
  float dsgWatts = (snap.power < 0.0f) ? fabs(snap.power) : 0.0f;
  int deltaMv = (int)roundf(snap.voltDiff * 1000.0f);
  float remWh = snap.remainCap * (snap.totalVoltage > 1.0f ? snap.totalVoltage : 25.6f);
  long ageSec = (snap.packets > 0) ? (long)((millis() - snap.lastUpdateMs) / 1000UL) : -1;

  String json;
  json.reserve(980);
  json += "{";
  json += "\"connected\":" + String((bmsConnected && !blePaused) ? "true" : "false") + ",";
  json += "\"blePaused\":" + String(blePaused ? "true" : "false") + ",";
  json += "\"hasData\":" + String((snap.packets > 0 && !blePaused) ? "true" : "false") + ",";
  json += "\"fresh\":" + String(isFresh ? "true" : "false") + ",";
  String stTxt = blePaused ? "⏸ BLE платы отключен (свободно для офиц. JK App)" : (bmsConnected ? "Подключено (ESP32)" : "Поиск BMS по BLE...");
  json += "\"statusText\":\"" + stTxt + "\",";
  json += "\"deviceName\":\"JK-B2A8S20P\",";
  json += "\"deviceAddress\":\"" + String(BMS_MAC) + "\",";
  json += "\"bmsMac\":\"" + String(BMS_MAC) + "\",";
  json += "\"ip\":\"" + WiFi.localIP().toString() + "\",";
  json += "\"phoneIp\":\"" + WiFi.localIP().toString() + "\",";
  json += "\"rssi\":" + String(WiFi.RSSI()) + ",";
  json += "\"uptimeSec\":" + String(millis() / 1000UL) + ",";
  json += "\"ageSec\":" + String(ageSec) + ",";

  json += "\"soc\":" + String(snap.soc) + ",";
  json += "\"totalVoltage\":" + String(snap.totalVoltage, 2) + ",";
  json += "\"current\":" + String(snap.current, 2) + ",";
  json += "\"power\":" + String(absPower, 1) + ",";
  json += "\"signedPower\":" + String(snap.power, 1) + ",";
  json += "\"cellAvg\":" + String(snap.cellAvg, 3) + ",";
  json += "\"cellMin\":" + String(snap.cellMin, 3) + ",";
  json += "\"cellMax\":" + String(snap.cellMax, 3) + ",";
  json += "\"minCellIdx\":" + String(snap.minCellIdx) + ",";
  json += "\"maxCellIdx\":" + String(snap.maxCellIdx) + ",";
  json += "\"voltDiff\":" + String(snap.voltDiff, 3) + ",";
  json += "\"balanceCurrent\":" + String(snap.balanceCurrent, 2) + ",";
  json += "\"remainCap\":" + String(snap.remainCap, 1) + ",";
  json += "\"nominalCap\":" + String(snap.nominalCap, 1) + ",";
  json += "\"tempMos\":" + String(snap.tempMos, 1) + ",";
  json += "\"temp1\":" + String(snap.temp1, 1) + ",";
  json += "\"temp2\":" + String(snap.temp2, 1) + ",";
  json += "\"chg\":" + String(snap.chg ? "true" : "false") + ",";
  json += "\"dsg\":" + String(snap.dsg ? "true" : "false") + ",";
  json += "\"bal\":" + String(snap.bal ? "true" : "false") + ",";
  json += "\"packets\":" + String(snap.packets) + ",";

  json += "\"socPercent\":" + String(snap.soc) + ",";
  json += "\"packVoltage\":" + String(snap.totalVoltage, 2) + ",";
  json += "\"currentAmps\":" + String(snap.current, 2) + ",";
  json += "\"powerWatts\":" + String(snap.power, 1) + ",";
  json += "\"chargeWatts\":" + String(chgWatts, 1) + ",";
  json += "\"dischargeWatts\":" + String(dsgWatts, 1) + ",";
  json += "\"remainingCapacityAh\":" + String(snap.remainCap, 1) + ",";
  json += "\"nominalCapacityAh\":" + String(snap.nominalCap, 1) + ",";
  json += "\"remainingEnergyWh\":" + String(remWh, 0) + ",";
  json += "\"tempSensor1\":" + String(snap.temp1, 1) + ",";
  json += "\"tempSensor2\":" + String(snap.temp2, 1) + ",";
  json += "\"cellDeltaMv\":" + String(deltaMv) + ",";
  json += "\"cycleCount\":" + String(snap.cycleCount) + ",";
  json += "\"chargeMosEnabled\":" + String(snap.chg ? "true" : "false") + ",";
  json += "\"dischargeMosEnabled\":" + String(snap.dsg ? "true" : "false") + ",";
  json += "\"balanceEnabled\":" + String(snap.bal ? "true" : "false") + ",";
  json += "\"lastUpdatedMillis\":" + String(snap.lastUpdateMs) + ",";

  json += "\"cells\":[";
  for (int i = 0; i < 8; i++) {
    if (i > 0) json += ",";
    json += String(snap.cells[i], 3);
  }
  json += "],\"cellVoltages\":[";
  for (int i = 0; i < 8; i++) {
    if (i > 0) json += ",";
    json += String(snap.cells[i], 3);
  }
  json += "]}";
  return json;
}

// ==================== ВСТРОЕННЫЙ ЛЕГКОВЕСНЫЙ MQTT-КЛИЕНТ ====================
static void mqttWriteRemainingLength(uint8_t* buf, int& pos, int len) {
  do {
    uint8_t encoded = len % 128;
    len /= 128;
    if (len > 0) encoded |= 128;
    buf[pos++] = encoded;
  } while (len > 0);
}

static bool mqttConnectAndSubscribe() {
  if (WiFi.status() != WL_CONNECTED) return false;
  if (mqttNet.connected()) return true;

  mqttNet.stop();
  mqttNet.setTimeout(2);
  if (!mqttNet.connect(MQTT_HOST, MQTT_PORT)) {
    return false;
  }

  String clientId = "esp32-jkbms-" + String((uint32_t)ESP.getEfuseMac(), HEX);
  int idLen = clientId.length();
  int remLen = 10 + 2 + idLen;

  uint8_t pkt[128];
  int p = 0;
  pkt[p++] = 0x10; // CONNECT
  mqttWriteRemainingLength(pkt, p, remLen);
  pkt[p++] = 0x00; pkt[p++] = 0x04;
  pkt[p++] = 'M';  pkt[p++] = 'Q'; pkt[p++] = 'T'; pkt[p++] = 'T';
  pkt[p++] = 0x04; // Protocol Level 4 (v3.1.1)
  pkt[p++] = 0x02; // Clean Session
  pkt[p++] = 0x00; pkt[p++] = 30; // KeepAlive 30s
  pkt[p++] = (idLen >> 8) & 0xFF;
  pkt[p++] = idLen & 0xFF;
  memcpy(&pkt[p], clientId.c_str(), idLen);
  p += idLen;

  mqttNet.write(pkt, p);

  unsigned long t0 = millis();
  while (mqttNet.available() < 4 && (millis() - t0 < 1500)) {
    delay(10);
  }
  if (mqttNet.available() < 4) {
    mqttNet.stop();
    return false;
  }
  uint8_t connack[4];
  mqttNet.read(connack, 4);
  if (connack[0] != 0x20 || connack[3] != 0x00) {
    mqttNet.stop();
    return false;
  }

  // Подписываемся на топик команд управления: jkbms/c84780550485/cmd
  int topLen = strlen(MQTT_TOPIC_CMD);
  int subRem = 2 + 2 + topLen + 1;
  p = 0;
  pkt[p++] = 0x82; // SUBSCRIBE
  mqttWriteRemainingLength(pkt, p, subRem);
  pkt[p++] = 0x00; pkt[p++] = 0x01; // Packet ID 1
  pkt[p++] = (topLen >> 8) & 0xFF;
  pkt[p++] = topLen & 0xFF;
  memcpy(&pkt[p], MQTT_TOPIC_CMD, topLen);
  p += topLen;
  pkt[p++] = 0x00; // QoS 0
  mqttNet.write(pkt, p);

  Serial.println("[CLOUD] Облачный доступ активен (broker.emqx.io)!");
  return true;
}

static void mqttPublishState(const String& json) {
  if (!mqttNet.connected()) return;
  int topLen = strlen(MQTT_TOPIC_STATE);
  int payLen = json.length();
  int remLen = 2 + topLen + payLen;

  uint8_t hdr[64];
  int p = 0;
  hdr[p++] = 0x31; // PUBLISH (QoS 0, RETAIN = 1)
  mqttWriteRemainingLength(hdr, p, remLen);
  hdr[p++] = (topLen >> 8) & 0xFF;
  hdr[p++] = topLen & 0xFF;
  memcpy(&hdr[p], MQTT_TOPIC_STATE, topLen);
  p += topLen;

  mqttNet.write(hdr, p);
  mqttNet.write((const uint8_t*)json.c_str(), payLen);
}

static void mqttPollIncoming() {
  if (!mqttNet.connected()) return;
  while (mqttNet.available() > 0) {
    uint8_t header = mqttNet.read();
    int mult = 1;
    int remLen = 0;
    unsigned long t0 = millis();
    uint8_t enc = 0;
    do {
      while (!mqttNet.available() && (millis() - t0 < 200)) delay(1);
      if (!mqttNet.available()) return;
      enc = mqttNet.read();
      remLen += (enc & 127) * mult;
      mult *= 128;
    } while ((enc & 128) != 0);

    uint8_t buf[256];
    int toRead = (remLen < 255) ? remLen : 255;
    int got = 0;
    while (got < toRead && (millis() - t0 < 300)) {
      if (mqttNet.available()) buf[got++] = mqttNet.read();
    }
    while (got < remLen && mqttNet.available()) {
      mqttNet.read();
      got++;
    }

    // Если это PUBLISH пакет с командой управления (0x30..0x3F)
    if ((header & 0xF0) == 0x30 && toRead > 4) {
      int topLen = (buf[0] << 8) | buf[1];
      int payStart = 2 + topLen;
      if ((header & 0x06) != 0) payStart += 2; // если QoS > 0
      if (payStart < toRead) {
        buf[toRead] = 0;
        String msg = String((char*)&buf[payStart]);
        msg.trim();
        // Формат команды: "PIN:cmd:val", например "1234:chg:1"
        int p1 = msg.indexOf(':');
        int p2 = msg.indexOf(':', p1 + 1);
        if (p1 > 0 && p2 > p1) {
          String pin = msg.substring(0, p1);
          String cmd = msg.substring(p1 + 1, p2);
          int val = msg.substring(p2 + 1).toInt();
          if (pin == String(CONTROL_PIN)) {
            Serial.printf("[CLOUD CMD] Выполнение удаленной команды: %s = %d\n", cmd.c_str(), val);
            queueBMSControl(cmd, val);
          } else {
            Serial.println("[CLOUD CMD] Неверный PIN-код управления!");
          }
        }
      }
    }
  }
}

// ==================== ВСТРОЕННЫЙ ВЕБ-ДАШБОРД С УПРАВЛЕНИЕМ ====================
const char INDEX_HTML[] PROGMEM = R"rawliteral(
<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<meta name="theme-color" content="#0b1120">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="JK BMS Monitor">
<title>JK BMS Monitor — 24V 230Ah</title>
<style>
:root{
  --bg:#0b1120;--card:#151f32;--border:#24324b;--text:#f1f5f9;--muted:#94a3b8;
  --green:#10b981;--blue:#38bdf8;--amber:#f59e0b;--red:#ef4444;
}
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
body{
  font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
  background:radial-gradient(circle at top,#172544 0%,var(--bg) 65%);
  color:var(--text);min-height:100vh;padding:14px;max-width:540px;margin:0 auto;
}
.header{display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;gap:8px}
.title-wrap h1{font-size:1.12rem;font-weight:800}
.title-wrap p{font-size:.74rem;color:var(--muted);margin-top:2px}
.top-right{display:flex;align-items:center;gap:8px}
.badge{
  display:inline-flex;align-items:center;gap:6px;padding:6px 11px;border-radius:999px;
  font-size:.74rem;font-weight:700;background:rgba(255,255,255,.06);border:1px solid var(--border);
}
.gear-btn{
  background:var(--card);color:var(--text);border:1px solid var(--border);
  border-radius:11px;padding:6px 10px;font-size:.82rem;font-weight:700;cursor:pointer;
}
.dot{width:8px;height:8px;border-radius:50%;background:var(--amber);box-shadow:0 0 8px var(--amber)}
.dot.ok{background:var(--green);box-shadow:0 0 10px var(--green)}
.dot.err{background:var(--red);box-shadow:0 0 10px var(--red)}
.hero{
  background:linear-gradient(145deg,#192742,#111a2e);border:1px solid var(--border);
  border-radius:20px;padding:18px;margin-bottom:12px;box-shadow:0 10px 25px rgba(0,0,0,.35);
}
.hero-top{display:flex;justify-content:space-between;align-items:center;gap:14px}
.soc-big{font-size:3.1rem;font-weight:800;line-height:1;letter-spacing:-1px}
.soc-sub{font-size:.82rem;color:var(--muted);margin-top:6px}
.state-pill{
  padding:6px 12px;border-radius:12px;font-size:.8rem;font-weight:700;
  background:rgba(56,189,248,.14);color:var(--blue);border:1px solid rgba(56,189,248,.3);
}
.bar-wrap{height:14px;background:#090e1a;border-radius:999px;overflow:hidden;margin:15px 0 10px;border:1px solid #1e293b}
.bar-fill{height:100%;width:0%;background:linear-gradient(90deg,#10b981,#38bdf8);transition:width .4s ease}
.eta-row{display:flex;justify-content:space-between;font-size:.8rem;color:var(--muted)}
.eta-row strong{color:var(--text)}
.grid-3{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:12px}
.stat{
  background:var(--card);border:1px solid var(--border);border-radius:16px;padding:12px 10px;text-align:center;
}
.stat-lbl{font-size:.7rem;color:var(--muted);text-transform:uppercase;letter-spacing:.5px}
.stat-val{font-size:1.3rem;font-weight:800;margin-top:4px}
.stat-unit{font-size:.75rem;color:var(--muted);font-weight:500}
.switches{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-bottom:12px}
.sw{
  background:var(--card);border:1px solid var(--border);border-radius:12px;padding:9px 8px;
  display:flex;align-items:center;justify-content:center;gap:6px;font-size:.76rem;font-weight:700;cursor:pointer;
}
.sw i{width:8px;height:8px;border-radius:50%;background:#475569}
.sw.on i{background:var(--green);box-shadow:0 0 8px var(--green)}
.section{
  background:var(--card);border:1px solid var(--border);border-radius:18px;padding:14px;margin-bottom:12px;
}
.sec-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;font-size:.82rem;font-weight:700}
.sec-head span{color:var(--muted);font-weight:500;font-size:.76rem}
.cells{display:grid;grid-template-columns:repeat(2,1fr);gap:8px}
.cell{
  background:#0d1526;border:1px solid #1e293b;border-radius:11px;padding:8px 10px;
  display:flex;flex-direction:column;gap:4px;
}
.cell.min{border-color:rgba(56,189,248,.6)}
.cell.max{border-color:rgba(245,158,11,.6)}
.cell-top{display:flex;justify-content:space-between;font-size:.76rem}
.cell-num{color:var(--muted);font-weight:600}
.cell-v{font-weight:700;font-variant-numeric:tabular-nums}
.cell-bar{height:5px;background:#1e293b;border-radius:4px;overflow:hidden}
.cell-bar div{height:100%;background:var(--blue);width:50%;transition:width .3s}
.temps{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
.t-box{background:#0d1526;border:1px solid #1e293b;border-radius:11px;padding:8px;text-align:center}
.t-box small{display:block;font-size:.68rem;color:var(--muted)}
.t-box b{font-size:.98rem;display:block;margin-top:3px}
.ctrl-drawer{display:none;background:#131d31;border:1px solid var(--blue);border-radius:18px;padding:14px;margin-bottom:12px}
.ctrl-drawer.open{display:block}
.ctrl-row{display:flex;justify-content:space-between;align-items:center;padding:8px 0;border-bottom:1px solid #1e293b;font-size:.84rem}
.ctrl-row:last-child{border-bottom:none}
.btn-sm{background:var(--blue);color:#081120;border:none;border-radius:8px;padding:6px 12px;font-weight:700;cursor:pointer}
.pin-in{background:#0b1120;color:#fff;border:1px solid #334155;border-radius:8px;padding:6px 10px;width:110px}
.footer{text-align:center;font-size:.72rem;color:var(--muted);padding:6px 0 14px}
</style>
</head>
<body>
  <div class="header">
    <div class="title-wrap">
      <h1>JK BMS Monitor</h1>
      <p id="subInfo">24V 230Ah • JK-B2A8S20P</p>
    </div>
    <div class="top-right">
      <div class="badge"><span class="dot" id="connDot"></span><span id="connTxt">Связь...</span></div>
      <button class="gear-btn" onclick="document.getElementById('ctrlBox').classList.toggle('open')">⚙️</button>
    </div>
  </div>

  <div class="ctrl-drawer" id="ctrlBox">
    <div style="font-size:.86rem;font-weight:800;margin-bottom:8px;color:var(--blue)">⚙️ Управление ключами JK BMS</div>
    <div class="ctrl-row">
      <span>Пароль управления (PIN):</span>
      <input type="password" class="pin-in" id="pinIn" value="1234" placeholder="PIN (1234)">
    </div>
    <div class="ctrl-row">
      <span>⚡ Заряд (Charge MOS):</span>
      <div>
        <button class="btn-sm" onclick="sendCmd('chg',1)">ВКЛ</button>
        <button class="btn-sm" style="background:#ef4444;color:#fff" onclick="sendCmd('chg',0)">ВЫКЛ</button>
      </div>
    </div>
    <div class="ctrl-row">
      <span>🔋 Разряд (Discharge MOS):</span>
      <div>
        <button class="btn-sm" onclick="sendCmd('dsg',1)">ВКЛ</button>
        <button class="btn-sm" style="background:#ef4444;color:#fff" onclick="sendCmd('dsg',0)">ВЫКЛ</button>
      </div>
    </div>
    <div class="ctrl-row">
      <span>⚖️ Балансировка ячеек:</span>
      <div>
        <button class="btn-sm" onclick="sendCmd('bal',1)">ВКЛ</button>
        <button class="btn-sm" style="background:#ef4444;color:#fff" onclick="sendCmd('bal',0)">ВЫКЛ</button>
      </div>
    </div>
    <div class="ctrl-row">
      <span>🔓 Связь платы с BMS (для офиц. JK App):</span>
      <div>
        <button class="btn-sm" style="background:#f59e0b;color:#000" onclick="sendCmd('ble',0)">Отключить</button>
        <button class="btn-sm" style="background:#10b981;color:#000" onclick="sendCmd('ble',1)">Вернуть</button>
      </div>
    </div>
    <div id="cmdMsg" style="font-size:.75rem;color:var(--muted);margin-top:6px"></div>
  </div>

  <div class="hero">
    <div class="hero-top">
      <div>
        <div class="soc-big"><span id="soc">--</span>%</div>
        <div class="soc-sub">Остаток: <strong id="remCap">--</strong> / <span id="nomCap">230</span> А·ч (<span id="remWh">--</span> Вт·ч)</div>
      </div>
      <div class="state-pill" id="modePill">Ожидание</div>
    </div>
    <div class="bar-wrap"><div class="bar-fill" id="socBar"></div></div>
    <div class="eta-row">
      <span>Прогноз времени:</span>
      <strong id="etaTxt">Расчет...</strong>
    </div>
  </div>

  <div class="grid-3">
    <div class="stat">
      <div class="stat-lbl">Напряжение</div>
      <div class="stat-val"><span id="volt">--</span> <span class="stat-unit">В</span></div>
    </div>
    <div class="stat">
      <div class="stat-lbl">Ток</div>
      <div class="stat-val"><span id="curr">--</span> <span class="stat-unit">А</span></div>
    </div>
    <div class="stat">
      <div class="stat-lbl">Мощность</div>
      <div class="stat-val"><span id="pwr">--</span> <span class="stat-unit">Вт</span></div>
    </div>
  </div>

  <div class="switches">
    <div class="sw" id="swChg" onclick="document.getElementById('ctrlBox').classList.add('open')"><i></i><span>Заряд</span></div>
    <div class="sw" id="swDsg" onclick="document.getElementById('ctrlBox').classList.add('open')"><i></i><span>Разряд</span></div>
    <div class="sw" id="swBal" onclick="document.getElementById('ctrlBox').classList.add('open')"><i></i><span>Балансир</span></div>
  </div>

  <div class="section">
    <div class="sec-head">
      <div>Ячейки 1–8 (LiFePO4)</div>
      <span>Δ <b id="deltaMv" style="color:var(--text)">--</b> мВ • Сред: <b id="avgV" style="color:var(--text)">--</b> В</span>
    </div>
    <div class="cells" id="cellsGrid"></div>
  </div>

  <div class="section">
    <div class="sec-head">
      <div>Температура и Ресурс</div>
      <span>Циклов: <b id="cycles" style="color:var(--text)">--</b></span>
    </div>
    <div class="temps">
      <div class="t-box"><small>MOSFET</small><b id="tMos">-- °C</b></div>
      <div class="t-box"><small>Датчик T1</small><b id="t1">-- °C</b></div>
      <div class="t-box"><small>Датчик T2</small><b id="t2">-- °C</b></div>
    </div>
  </div>

  <div class="footer" id="footInfo">Автообновление каждые 2 сек</div>

<script>
const cellsGrid = document.getElementById('cellsGrid');
for(let i=1;i<=8;i++){
  cellsGrid.innerHTML += `<div class="cell" id="cbox${i}">
    <div class="cell-top"><span class="cell-num">#${i}</span><span class="cell-v" id="cv${i}">-.--- В</span></div>
    <div class="cell-bar"><div id="cb${i}"></div></div>
  </div>`;
}

async function sendCmd(cmd, val){
  const pin = document.getElementById('pinIn').value.trim();
  const msg = document.getElementById('cmdMsg');
  msg.textContent = 'Отправка команды в BMS...';
  try{
    const r = await fetch(`/control?pin=${encodeURIComponent(pin)}&cmd=${cmd}&val=${val}`);
    const t = await r.text();
    msg.textContent = r.ok ? '✅ Команда принята платой!' : ('❌ Ошибка: ' + t);
    setTimeout(poll, 500);
  }catch(e){
    msg.textContent = '❌ Ошибка соединения';
  }
}

function calcEta(cur, rem, nom){
  if(Math.abs(cur) < 0.2) return "Покой (нет нагрузки)";
  let hrs = cur > 0 ? Math.max(0, nom - rem) / cur : Math.max(0, rem) / Math.abs(cur);
  if(hrs > 99) return "> 99 ч";
  let m = Math.round(hrs * 60);
  let h = Math.floor(m / 60), rm = m % 60;
  let suf = cur > 0 ? "до 100%" : "до разряда";
  return (h > 0 ? `${h} ч ${rm} мин ` : `${rm} мин `) + suf;
}

async function poll(){
  try{
    const c = new AbortController();
    const t = setTimeout(()=>c.abort(), 4000);
    const r = await fetch('/status', {signal:c.signal, cache:'no-store'});
    clearTimeout(t);
    const d = await r.json();

    const dot = document.getElementById('connDot');
    const txt = document.getElementById('connTxt');
    if(d.blePaused){
      dot.className = 'dot';
      txt.textContent = '⏸ BLE отключен';
    } else if(d.connected || d.hasData){
      dot.className = 'dot ok';
      txt.textContent = d.connected ? 'BMS Онлайн' : 'Обновление...';
    } else {
      dot.className = 'dot';
      txt.textContent = 'Поиск BLE...';
    }

    document.getElementById('soc').textContent = d.soc;
    document.getElementById('socBar').style.width = Math.min(100, Math.max(0, d.soc)) + '%';
    document.getElementById('remCap').textContent = d.remainCap.toFixed(1);
    document.getElementById('nomCap').textContent = d.nominalCap.toFixed(0);
    document.getElementById('remWh').textContent = d.remainingEnergyWh;
    document.getElementById('volt').textContent = d.totalVoltage.toFixed(2);
    document.getElementById('curr').textContent = (d.current > 0 ? '+' : '') + d.current.toFixed(1);
    document.getElementById('pwr').textContent = Math.round(d.power);

    const pill = document.getElementById('modePill');
    if(d.current > 0.25){
      pill.textContent = '⚡ Зарядка';
      pill.style.color = '#10b981';
    } else if(d.current < -0.25){
      pill.textContent = '🔋 Разряд';
      pill.style.color = '#38bdf8';
    } else {
      pill.textContent = 'Ожидание';
      pill.style.color = '#94a3b8';
    }

    document.getElementById('etaTxt').textContent = calcEta(d.current, d.remainCap, d.nominalCap);
    document.getElementById('swChg').className = 'sw' + (d.chg ? ' on' : '');
    document.getElementById('swDsg').className = 'sw' + (d.dsg ? ' on' : '');
    document.getElementById('swBal').className = 'sw' + (d.bal ? ' on' : '');

    document.getElementById('deltaMv').textContent = d.cellDeltaMv;
    document.getElementById('avgV').textContent = d.cellAvg.toFixed(3);
    document.getElementById('cycles').textContent = d.cycleCount;
    document.getElementById('tMos').textContent = d.tempMos.toFixed(1) + ' °C';
    document.getElementById('t1').textContent = d.temp1.toFixed(1) + ' °C';
    document.getElementById('t2').textContent = d.temp2.toFixed(1) + ' °C';

    if(d.cells){
      for(let i=0;i<8;i++){
        const v = d.cells[i] || 0;
        const idx = i + 1;
        document.getElementById('cv'+idx).textContent = v.toFixed(3) + ' В';
        const pct = Math.min(100, Math.max(5, ((v - 2.65) / (3.65 - 2.65)) * 100));
        document.getElementById('cb'+idx).style.width = pct + '%';
        const box = document.getElementById('cbox'+idx);
        box.className = 'cell' + (idx === d.minCellIdx ? ' min' : (idx === d.maxCellIdx ? ' max' : ''));
      }
    }

    document.getElementById('footInfo').textContent =
      `IP: ${d.ip} • Облако: активно • Кадров BMS: ${d.packets}`;
  } catch(e){
    document.getElementById('connDot').className = 'dot err';
    document.getElementById('connTxt').textContent = 'Ожидание ответа...';
  }
}
poll();
setInterval(poll, 2000);
</script>
</body>
</html>
)rawliteral";

void bleTask(void* parameter) {
  vTaskDelay(pdMS_TO_TICKS(1500));

  for (;;) {
    // 1. Если пользователь нажал «Отключить плату от BMS» (чтобы зайти с официального приложения JK BMS)
    if (blePaused) {
      if (pClient && pClient->isConnected()) {
        pClient->disconnect();
        Serial.println("[BLE] Соединение с BMS разорвано по команде пользователя.");
      }
      bmsConnected = false;
      // Страховочный авто-возврат через 20 минут, если забыли включить обратно
      if (millis() - blePausedAtMs > 20UL * 60UL * 1000UL) {
        blePaused = false;
        Serial.println("[BLE] Автоматическое возобновление связи с BMS после 20 мин паузы.");
      }
      vTaskDelay(pdMS_TO_TICKS(300));
      continue;
    }

    if (pClient && !pClient->isConnected()) {
      bmsConnected = false;
    }

    if (!bmsConnected) {
      connectToBMS();
      // Вместо глухого 5-секундного ожидания проверяем blePaused каждые 250 мс
      for (int w = 0; w < 16; w++) {
        if (blePaused || bmsConnected) break;
        vTaskDelay(pdMS_TO_TICKS(250));
      }
    } else {
      // Если пользователь переключил тумблер (Заряд / Разряд / Балансировка)
      if (hasPendingCmd) {
        uint8_t reg = pendingCmdReg;
        uint32_t val = pendingCmdVal;
        hasPendingCmd = false;
        if (reg == 0x96) {
          sendJKCommand(0x96, 0, 0);
        } else {
          Serial.printf("[BLE CMD] Запись регистра 0x%02X = %u\n", reg, (unsigned int)val);
          sendJKCommand(reg, val, 4);
          vTaskDelay(pdMS_TO_TICKS(350));
          sendJKCommand(0x97, 0, 0); // Запрашиваем обновленный кадр настроек 0x01
          vTaskDelay(pdMS_TO_TICKS(350));
          sendJKCommand(0x96, 0, 0); // Запрашиваем обновленный кадр ячеек 0x02
        }
        lastParsedMs = millis();
        needImmediateCloudPublish = true;
      }

      if (millis() - lastParsedMs > 20000UL) {
        sendJKCommand(0x96, 0, 0);
        lastParsedMs = millis();
      }
      vTaskDelay(pdMS_TO_TICKS(200));
    }
  }
}

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println("\n==========================================");
  Serial.println("  Запуск шлюза ESP32 JK-BMS v2.2 (Cloud)");
  Serial.println("==========================================");

  WiFi.mode(WIFI_STA);
  WiFi.setSleep(true);
  esp_wifi_set_ps(WIFI_PS_MIN_MODEM);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  WiFi.setTxPower(WIFI_POWER_15dBm);

  Serial.print("Connecting to WiFi");
  int tries = 0;
  while (WiFi.status() != WL_CONNECTED && tries < 60) {
    delay(500);
    Serial.print(".");
    tries++;
  }
  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    esp_wifi_set_ps(WIFI_PS_MIN_MODEM);
    Serial.print("WiFi connected! IP: ");
    Serial.println(WiFi.localIP());
    if (MDNS.begin(MDNS_NAME)) {
      MDNS.addService("http", "tcp", 80);
      Serial.printf("mDNS запущен: http://%s.local\n", MDNS_NAME);
    }
  }

  NimBLEDevice::init("ESP32-JK-Monitor");
  NimBLEDevice::setPower(ESP_PWR_LVL_P3);
  NimBLEScan* pScan = NimBLEDevice::getScan();
  pScan->setInterval(160);
  pScan->setWindow(48);

  esp_coex_preference_set(ESP_COEX_PREFER_WIFI);

  server.on("/", HTTP_GET, []() {
    lastHttpReqMs = millis();
    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.send_P(200, "text/html; charset=utf-8", INDEX_HTML);
  });

  server.on("/status", HTTP_GET, []() {
    lastHttpReqMs = millis();
    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.sendHeader("Cache-Control", "no-store, no-cache, must-revalidate");
    server.send(200, "application/json; charset=utf-8", buildStatusJson());
  });

  // Эндпоинт управления BMS: /control?pin=1234&cmd=chg&val=1
  server.on("/control", HTTP_GET, []() {
    lastHttpReqMs = millis();
    server.sendHeader("Access-Control-Allow-Origin", "*");
    String pin = server.arg("pin");
    String cmd = server.arg("cmd");
    int val = server.arg("val").toInt();

    if (pin != String(CONTROL_PIN)) {
      server.send(403, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"Неверный пароль/PIN\"}");
      return;
    }
    if (queueBMSControl(cmd, val)) {
      server.send(200, "application/json; charset=utf-8", "{\"ok\":true}");
    } else {
      server.send(400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"Неизвестная команда\"}");
    }
  });

  server.on("/ping", HTTP_GET, []() {
    lastHttpReqMs = millis();
    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.send(200, "text/plain", "OK");
  });

  server.onNotFound([]() {
    lastHttpReqMs = millis();
    server.sendHeader("Location", "/", true);
    server.send(302, "text/plain", "");
  });

  server.begin();
  Serial.println("HTTP WebServer запущен на порту 80 (/, /status, /control).");

  xTaskCreatePinnedToCore(bleTask, "BLETask", 8192, NULL, 1, NULL, 1);
}

unsigned long lastStatusPrint = 0;
unsigned long lastCloudSync   = 0;

void loop() {
  server.handleClient();
  mqttPollIncoming();

  unsigned long now = millis();

  // Облачная синхронизация каждые 3 секунды или мгновенно после команды управления
  if (WiFi.status() == WL_CONNECTED && (needImmediateCloudPublish || (now - lastCloudSync >= 3000))) {
    needImmediateCloudPublish = false;
    lastCloudSync = now;
    if (mqttConnectAndSubscribe()) {
      mqttPublishState(buildStatusJson());
    }
  }

  if (now - lastStatusPrint >= 5000) {
    lastStatusPrint = now;
    if (WiFi.status() == WL_CONNECTED) {
      const char* bleStat = blePaused ? "ПАУЗА" : (bmsConnected ? "OK" : "ПОИСК");
      Serial.printf("[STATUS] WiFi: OK (%s) | Cloud: %s | BLE: %s | SOC: %d%% | V: %.2fV | Кадров: %u\n",
                    WiFi.localIP().toString().c_str(),
                    mqttNet.connected() ? "OK" : "RECONN",
                    bleStat,
                    bms.soc,
                    bms.totalVoltage,
                    bms.packets);
    } else {
      Serial.println("[WiFi] Ожидание авто-переподключения к роутеру...");
      WiFi.reconnect();
    }
  }

  delay(2);
}
