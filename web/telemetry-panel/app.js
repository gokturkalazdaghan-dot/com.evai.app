// web/telemetry-panel/app.js
//
// Paneli gateway'in WebSocket kanalina baglar.
//
// TEMEL KURAL: bilinmeyen deger "—" gosterilir, 0 DEGIL.
// Standart OBD-II her degeri vermez (paket voltaji, menzil, lastik
// basinci ureticiye ozel PID'lerdedir). Bilinmeyeni sifir gostermek,
// sofore olmayan bir menzile guvendirir.

const UNKNOWN = '—';

/** Kac okumalik gecmis gosterilecek. */
const SPARK_HISTORY = 18;

/** Bu sure veri gelmezse baglanti "sessiz" sayilir. */
const STALE_AFTER_MS = 30_000;

const el = (id) => document.getElementById(id);

const dom = {
  batteryPercent: el('batteryPercent'),
  batteryRing: el('batteryRing'),
  batteryDup: el('batteryDup'),
  range: el('range'),
  voltage: el('voltage'),
  speed: el('speed'),
  odometer: el('odometer'),
  chargeStatus: el('chargeStatus'),
  chargeTime: el('chargeTime'),
  powerFlow: el('powerFlow'),
  alerts: el('alerts'),
  connectionState: el('connectionState'),
  latency: el('latency'),
  lastUpdate: el('lastUpdate'),
  sparkbars: el('sparkbars'),
};

// 2πr, r=50 (styles.css ile ayni)
const RING_CIRCUMFERENCE = 314.16;

const history = [];
let lastPacketAt = 0;

// ---------------------------------------------------------------------
// Yardimcilar
// ---------------------------------------------------------------------

/** Deger yoksa "—" yazar ve soluk gosterir. */
function setValue(node, value, suffix = '') {
  if (value === null || value === undefined || Number.isNaN(value)) {
    node.textContent = UNKNOWN;
    node.classList.add('unknown');
    return;
  }
  node.textContent = `${value}${suffix}`;
  node.classList.remove('unknown');
}

function setBattery(percent) {
  if (percent === null || percent === undefined) {
    dom.batteryPercent.textContent = UNKNOWN;
    // Bilinmeyen seviye icin halkayi BOS birak; dolu gostermek yalan olur.
    dom.batteryRing.style.strokeDashoffset = RING_CIRCUMFERENCE;
    setValue(dom.batteryDup, null);
    return;
  }

  const clamped = Math.max(0, Math.min(100, percent));
  dom.batteryPercent.textContent = `${Math.round(clamped)}%`;
  dom.batteryRing.style.strokeDashoffset =
    RING_CIRCUMFERENCE - (RING_CIRCUMFERENCE * clamped) / 100;
  setValue(dom.batteryDup, `${Math.round(clamped)}`, '%');

  history.push(clamped);
  while (history.length > SPARK_HISTORY) history.shift();
  renderSparkbars();
}

function renderSparkbars() {
  dom.sparkbars.replaceChildren(
    ...history.map((value) => {
      const bar = document.createElement('span');
      // En az %8 yukseklik: sifir da gorunur bir cubuk olmali.
      bar.style.height = `${Math.max(8, value)}%`;
      return bar;
    }),
  );
}

function renderCharging(telemetry) {
  const { isCharging, chargePowerKw, batteryPercent } = telemetry;

  if (isCharging === null || isCharging === undefined) {
    dom.chargeStatus.textContent = UNKNOWN;
    dom.chargeStatus.className = 'status unknown';
  } else if (isCharging) {
    dom.chargeStatus.textContent = 'CONNECTED';
    dom.chargeStatus.className = 'status connected';
  } else {
    dom.chargeStatus.textContent = 'DISCONNECTED';
    dom.chargeStatus.className = 'status disconnected';
  }

  setValue(dom.powerFlow, chargePowerKw !== null ? chargePowerKw.toFixed(1) : null, ' kW');

  // Tahmini sure yalnizca GERCEKTEN hesaplanabiliyorsa gosterilir:
  // sarj oluyor + guc biliniyor + seviye biliniyor. Aksi halde N/A.
  const canEstimate =
    isCharging === true &&
    typeof chargePowerKw === 'number' && chargePowerKw > 0 &&
    typeof batteryPercent === 'number' &&
    typeof telemetry.batteryCapacityKwh === 'number' && telemetry.batteryCapacityKwh > 0;

  if (!canEstimate) {
    dom.chargeTime.textContent = 'N/A';
    dom.chargeTime.classList.add('unknown');
    return;
  }

  const remainingKwh = ((100 - batteryPercent) / 100) * telemetry.batteryCapacityKwh;
  const hours = remainingKwh / chargePowerKw;
  const minutes = Math.round(hours * 60);
  dom.chargeTime.textContent = minutes >= 60
    ? `${Math.floor(minutes / 60)}s ${minutes % 60}dk`
    : `${minutes} dk`;
  dom.chargeTime.classList.remove('unknown');
}

/**
 * Uyari listesi.
 *
 * YALNIZCA GERCEKTEN BILINEN seyler listelenir. Gorseldeki "TPMS OK" ve
 * "Batt Temp" satirlari standart OBD-II'de YOKTUR (ureticiye ozel
 * PID'ler gerekir) -- veri gelmedigi surece yesil bir "OK" basmak,
 * kontrol edilmemis bir sistemi saglikli ilan etmek olurdu.
 */
function renderAlerts(telemetry, connected) {
  const items = [];

  items.push(connected
    ? { level: 'ok', text: 'BLE bağlı' }
    : { level: 'critical', text: 'BLE bağlantısı yok' });

  const battery = telemetry?.batteryPercent;
  if (typeof battery === 'number') {
    if (battery <= 30) {
      items.push({ level: 'critical', text: `Batarya %${Math.round(battery)} — şarj gerekli` });
    } else if (battery <= 50) {
      items.push({ level: 'warn', text: `Batarya %${Math.round(battery)}` });
    } else {
      items.push({ level: 'ok', text: `Batarya %${Math.round(battery)}` });
    }
  } else {
    items.push({ level: 'idle', text: 'Batarya seviyesi okunamıyor' });
  }

  if (telemetry?.packVoltage === null || telemetry?.packVoltage === undefined) {
    items.push({ level: 'idle', text: 'Paket voltajı: üreticiye özel PID gerekli' });
  }

  dom.alerts.replaceChildren(
    ...items.map(({ level, text }) => {
      const li = document.createElement('li');
      li.className = `alert alert--${level}`;
      const dot = document.createElement('span');
      dot.className = 'dot';
      li.append(dot, document.createTextNode(text));
      return li;
    }),
  );
}

function render(telemetry) {
  lastPacketAt = Date.now();

  setBattery(telemetry.batteryPercent);
  setValue(dom.range, telemetry.rangeKm !== null ? Math.round(telemetry.rangeKm) : null, ' km');

  // Paket voltaji varsa o gosterilir; yoksa 12V modul voltaji ACIKCA
  // etiketlenir. 12V'u "VOLTAGE" diye gostermek, kullaniciyi paket
  // voltajina bakiyor sanmasina yol acardi.
  if (telemetry.packVoltage !== null && telemetry.packVoltage !== undefined) {
    setValue(dom.voltage, telemetry.packVoltage.toFixed(0), ' V');
  } else if (telemetry.controlModuleVoltage !== null && telemetry.controlModuleVoltage !== undefined) {
    setValue(dom.voltage, telemetry.controlModuleVoltage.toFixed(1), ' V (12V)');
  } else {
    setValue(dom.voltage, null);
  }

  setValue(dom.speed, telemetry.speedKph !== null ? Math.round(telemetry.speedKph) : null, ' km/s');
  setValue(
    dom.odometer,
    telemetry.odometerKm !== null ? Math.round(telemetry.odometerKm).toLocaleString('tr-TR') : null,
    ' km',
  );

  renderCharging(telemetry);
  renderAlerts(telemetry, true);

  setValue(dom.latency, telemetry.latencyMs !== null ? telemetry.latencyMs : null, ' ms');
  dom.lastUpdate.textContent = new Date(telemetry.capturedAtEpochMs).toLocaleTimeString('tr-TR');
}

// ---------------------------------------------------------------------
// Baglanti
// ---------------------------------------------------------------------

/**
 * Panel, cihazin imzalama anahtarina SAHIP DEGILDIR (tarayiciya konsa
 * herkes cihaz taklidi yapabilirdi). Bunun yerine kisa omurlu bir
 * belirtec kullanilir: mobil uygulama imzali bir istekle
 * `/v1/telemetry/panel-token` cagirip belirteci panele aktarir
 * (QR/derin baglanti). Gelistirmede URL parametresiyle verilebilir.
 */
function readCredentials() {
  const params = new URLSearchParams(location.search);
  return {
    gatewayUrl: params.get('gateway') ?? 'http://localhost:3000',
    subjectId: params.get('subject') ?? '',
    token: params.get('token') ?? '',
  };
}

async function start() {
  const { gatewayUrl, subjectId, token } = readCredentials();

  if (!subjectId || !token) {
    dom.connectionState.textContent = 'Kimlik yok (?subject=…&token=… gerekli)';
    renderAlerts(null, false);
    return;
  }

  // socket.io istemcisi gateway tarafindan servis edilir.
  const { io } = await import(`${gatewayUrl}/socket.io/socket.io.esm.min.js`);

  const socket = io(`${gatewayUrl}/telemetry`, {
    transports: ['websocket'],
    reconnectionDelay: 1000,
    reconnectionDelayMax: 8000,
  });

  socket.on('connect', () => {
    dom.connectionState.textContent = 'WebSocket bağlı';
    socket.emit('subscribe', { subjectId, token }, (response) => {
      if (!response?.ok) {
        dom.connectionState.textContent = response?.error ?? 'Abone olunamadı';
        renderAlerts(null, false);
      }
    });
  });

  socket.on('telemetry', render);

  socket.on('disconnect', () => {
    dom.connectionState.textContent = 'Bağlantı koptu, yeniden deneniyor…';
    renderAlerts(null, false);
  });

  socket.on('connect_error', (error) => {
    dom.connectionState.textContent = `Bağlanılamadı: ${error.message}`;
    renderAlerts(null, false);
  });

  // Sessiz kalan bir baglanti "canli" gorunmemeli: soket acik olabilir
  // ama telefon veri gondermiyor olabilir (arac kapali, dongle cikmis).
  setInterval(() => {
    if (lastPacketAt && Date.now() - lastPacketAt > STALE_AFTER_MS) {
      dom.connectionState.textContent = 'Veri akışı durdu';
      renderAlerts(null, false);
    }
  }, 5000);
}

start();
