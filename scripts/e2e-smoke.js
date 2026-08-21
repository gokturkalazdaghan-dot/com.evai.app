#!/usr/bin/env node
// scripts/e2e-smoke.js
//
// Uctan uca duman testi: Gateway -> PostGIS -> price-saving-agent -> LLM.
//
// Calistirma:
//   node scripts/e2e-smoke.js
//
// Neden gerekli: /v1/stations/nearby ve /v1/voice/query, DeviceAttestationGuard
// VE RequestSignatureGuard arkasindadir. Imzasiz bir curl her zaman 401 doner,
// bu yuzden "servis ayakta mi" sorusu bu endpoint'ler icin curl ile
// yanitlanamaz. Bu script gercek bir cihaz kaydeder, istekleri imzalar ve
// tam zinciri dogrular.
//
// Imza sozlesmesi (bkz. common/guards/request-signature.guard.ts):
//   payload   = `${method}|${path}|${timestamp}|${sha256hex(body)}`
//   path      = query string HARIC ham URL yolu
//   body      = GET icin bos string, aksi halde JSON.stringify(body)
//   imza      = SHA256, cihazin ozel anahtariyla; public key DER/SPKI base64
//
// Attestation header'lari (x-eva-platform / x-eva-attestation) BILEREK
// gonderilmez: DEVICE_ATTESTATION_ENFORCED=false iken guard, header yoksa
// gelistirme bypass'ini uygular. Header gonderilirse gercek dogrulama
// denenir ve basarisiz olur.

const { generateKeyPairSync, createSign, createHash, randomUUID } = require('crypto');

const BASE_URL = process.env.GATEWAY_URL || 'http://localhost:3000';
const TIMEOUT_MS = Number(process.env.SMOKE_TIMEOUT_MS || 60000);

let passed = 0;
let failed = 0;

function report(name, ok, detail) {
  const tag = ok ? '[OK]  ' : '[FAIL]';
  console.log(`${tag} ${name}`);
  if (detail) {
    for (const line of String(detail).split('\n')) console.log(`       ${line}`);
  }
  if (ok) passed++;
  else failed++;
  return ok;
}

function sha256hex(input) {
  return createHash('sha256').update(input).digest('hex');
}

function buildSignedHeaders({ deviceId, privateKey, method, path, body }) {
  const timestamp = Date.now();
  // Guard, GET icin body'yi hesaba katmaz; diger metotlarda parse edilmis
  // govdeyi yeniden stringify eder. Bu yuzden govdeyi TAM OLARAK burada
  // uretilen dize ile gondermek zorundayiz (anahtar sirasi dahil).
  const bodyHash = method === 'GET' ? sha256hex('') : sha256hex(JSON.stringify(body));
  const payload = `${method}|${path}|${timestamp}|${bodyHash}`;

  const signature = createSign('SHA256').update(payload).end().sign(privateKey).toString('base64');

  return {
    'x-eva-device-id': deviceId,
    'x-eva-signature': signature,
    'x-eva-signature-timestamp': String(timestamp),
  };
}

async function request(method, path, { body, headers = {} } = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(`${BASE_URL}${path}`, {
      method,
      headers: { 'Content-Type': 'application/json', ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
    const text = await res.text();
    let json = null;
    try {
      json = JSON.parse(text);
    } catch {
      /* JSON olmayabilir (ornegin bos yanit) */
    }
    return { status: res.status, text, json };
  } finally {
    clearTimeout(timer);
  }
}

async function main() {
  console.log('='.repeat(64));
  console.log(' EVA AI -- uctan uca duman testi');
  console.log(` hedef: ${BASE_URL}`);
  console.log('='.repeat(64));

  // ----------------------------------------------------------------
  // 1) Cihaz anahtar cifti + kayit
  // ----------------------------------------------------------------
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const publicKeyBase64 = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  const deviceId = randomUUID();

  console.log('\n[1] Cihaz kaydi (POST /v1/devices/register)');
  const reg = await request('POST', '/v1/devices/register', {
    body: { deviceId, publicKeyBase64 },
  });
  report(
    'cihaz kaydedildi',
    reg.status === 201 || reg.status === 200,
    `HTTP ${reg.status} ${reg.text.slice(0, 200)}`
  );

  // ----------------------------------------------------------------
  // 2) Imzasiz istek REDDEDILMELI (guard gercekten calisiyor mu?)
  // ----------------------------------------------------------------
  console.log('\n[2] Negatif kontrol -- imzasiz istek reddedilmeli');
  const unsigned = await request('GET', '/v1/stations/nearby?lat=41.0082&lon=28.9784');
  report(
    'imzasiz istek 401 aldi',
    unsigned.status === 401,
    `HTTP ${unsigned.status} ${unsigned.text.slice(0, 160)}`
  );

  // ----------------------------------------------------------------
  // 3) Imzali GET /v1/stations/nearby
  // ----------------------------------------------------------------
  console.log('\n[3] Imzali istasyon sorgusu (GET /v1/stations/nearby)');
  const stationsPath = '/v1/stations/nearby';
  const stationsQuery = '?lat=41.0082&lon=28.9784&radiusMeters=10000';
  const stations = await request('GET', `${stationsPath}${stationsQuery}`, {
    headers: buildSignedHeaders({
      deviceId,
      privateKey,
      method: 'GET',
      path: stationsPath,
      body: undefined,
    }),
  });

  const stationsOk = stations.status === 200 && Array.isArray(stations.json);
  report(
    'istasyon sorgusu 200 dondu',
    stationsOk,
    stationsOk
      ? `${stations.json.length} istasyon: ` +
        stations.json.map((s) => s.name || s.stationId).join(', ')
      : `HTTP ${stations.status} ${stations.text.slice(0, 300)}`
  );

  // ----------------------------------------------------------------
  // 4) Imzali POST /v1/voice/query -- tam zincir + LLM
  // ----------------------------------------------------------------
  console.log('\n[4] Imzali sesli sorgu (POST /v1/voice/query) -- LLM dahil tam zincir');
  const voicePath = '/v1/voice/query';
  const voiceBody = {
    transcript: 'Yakinimda en ucuz sarj istasyonu nerede?',
    lat: 41.0082,
    lon: 28.9784,
    batterySocPercent: 35,
  };
  const startedAt = Date.now();
  const voice = await request('POST', voicePath, {
    body: voiceBody,
    headers: buildSignedHeaders({
      deviceId,
      privateKey,
      method: 'POST',
      path: voicePath,
      body: voiceBody,
    }),
  });
  const elapsedMs = Date.now() - startedAt;

  // NestJS POST icin varsayilan basari kodu 201'dir (200 DEGIL).
  const voiceOk =
    (voice.status === 200 || voice.status === 201) &&
    voice.json &&
    typeof voice.json.spokenReply === 'string';
  report(
    'sesli sorgu 200 + gecerli yanit',
    voiceOk,
    voiceOk
      ? `gecikme ${elapsedMs} ms\nyanit: ${voice.json.spokenReply}\n` +
        `onerilen: ${voice.json.recommendedStationName ?? '(yok)'} ` +
        `${voice.json.estimatedPricePerKwh ?? ''}`
      : `HTTP ${voice.status} (${elapsedMs} ms) ${voice.text.slice(0, 400)}`
  );

  // ----------------------------------------------------------------
  // 5) Replay korumasi -- ayni imza ikinci kez REDDEDILMELI
  // ----------------------------------------------------------------
  console.log('\n[5] Replay korumasi -- ayni imza iki kez kullanilamamali');
  const replayHeaders = buildSignedHeaders({
    deviceId,
    privateKey,
    method: 'GET',
    path: stationsPath,
    body: undefined,
  });
  const first = await request('GET', `${stationsPath}${stationsQuery}`, {
    headers: replayHeaders,
  });
  const second = await request('GET', `${stationsPath}${stationsQuery}`, {
    headers: replayHeaders,
  });
  report(
    'ilk istek kabul, tekrar reddedildi',
    first.status === 200 && second.status === 401,
    `ilk: HTTP ${first.status} | tekrar: HTTP ${second.status} ${second.text.slice(0, 120)}`
  );

  console.log('\n' + '='.repeat(64));
  console.log(` BASARILI: ${passed}   BASARISIZ: ${failed}`);
  console.log('='.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error('\nBEKLENMEYEN HATA:', err);
  process.exit(1);
});
