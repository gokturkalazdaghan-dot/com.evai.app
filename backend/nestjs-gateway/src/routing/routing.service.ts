// backend/nestjs-gateway/src/routing/routing.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';

/** Rotanin nasil elde edildigi. Istemci bunu kullaniciya DOGRU anlatmali. */
export type RouteQuality = 'road' | 'straight_line';

export interface LatLng {
  lat: number;
  lon: number;
}

export interface RouteResult {
  /** Google encoded polyline (precision 5). Haritada cizilecek geometri. */
  encodedPolyline: string;
  distanceMeters: number;
  /** Trafik bilinmiyorsa serbest akis tahmini; null olabilir. */
  durationSeconds: number | null;
  quality: RouteQuality;
  provider: string;
}

const EARTH_RADIUS_METERS = 6_371_000;

/** Kus ucusu mesafenin kaba surus mesafesine cevrimi (yol dolambaci). */
const DETOUR_FACTOR = 1.3;

/** Fallback sure tahmini icin varsayilan ortalama hiz. */
const FALLBACK_AVG_SPEED_KMH = 45;

const ROUTES_API_URL = 'https://routes.googleapis.com/directions/v2:computeRoutes';
const OSRM_BASE_URL = 'https://router.project-osrm.org/route/v1/driving';

const REQUEST_TIMEOUT_MS = 8_000;

/**
 * Iki nokta arasinda surulebilir rota uretir.
 *
 * NEDEN COK SAGLAYICILI
 * ---------------------
 * Google Routes API ayri bir SUNUCU anahtari ve faturalandirma ister;
 * uygulamaya gomulu Android SDK anahtari bu servise yetkili DEGILDIR
 * (ve olmamalidir - APK'dan cikarilabilir). Anahtar hazir olana kadar
 * ozellik komple calismaz durumda kalmasin diye sirali fallback var.
 *
 * NEDEN DUZ CIZGI EN SON
 * ----------------------
 * Duz cizgi bir rota DEGILDIR. Tamamen sessiz kalmaktansa gosterilir,
 * ama `quality: 'straight_line'` ile isaretlenir ve Eva bunu "kus ucusu"
 * diye soyler. Uydurma bir surus suresi kesin bilgi gibi sunulmaz.
 */
@Injectable()
export class RoutingService {
  private readonly logger = new Logger(RoutingService.name);

  constructor(private readonly config: ConfigService) {}

  async computeRoute(origin: LatLng, destination: LatLng): Promise<RouteResult> {
    const googleKey = this.config.get<string>('GOOGLE_ROUTES_API_KEY');
    if (googleKey) {
      const viaGoogle = await this.tryGoogleRoutes(origin, destination, googleKey);
      if (viaGoogle) return viaGoogle;
    }

    const viaOsrm = await this.tryOsrm(origin, destination);
    if (viaOsrm) return viaOsrm;

    this.logger.warn('Gercek rota alinamadi; kus ucusu cizgiye dusuldu.');
    return this.straightLine(origin, destination);
  }

  private async tryGoogleRoutes(
    origin: LatLng,
    destination: LatLng,
    apiKey: string,
  ): Promise<RouteResult | null> {
    try {
      const response = await this.fetchWithTimeout(ROUTES_API_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Goog-Api-Key': apiKey,
          'X-Goog-FieldMask':
            'routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline',
        },
        body: JSON.stringify({
          origin: { location: { latLng: { latitude: origin.lat, longitude: origin.lon } } },
          destination: {
            location: { latLng: { latitude: destination.lat, longitude: destination.lon } },
          },
          travelMode: 'DRIVE',
          routingPreference: 'TRAFFIC_AWARE',
        }),
      });

      if (!response.ok) {
        const detail = (await response.text()).slice(0, 200);
        this.logger.warn(`Google Routes ${response.status}: ${detail}`);
        return null;
      }

      const payload = (await response.json()) as {
        routes?: Array<{
          duration?: string;
          distanceMeters?: number;
          polyline?: { encodedPolyline?: string };
        }>;
      };

      const route = payload.routes?.[0];
      const polyline = route?.polyline?.encodedPolyline;
      if (!route || !polyline || typeof route.distanceMeters !== 'number') return null;

      return {
        encodedPolyline: polyline,
        distanceMeters: route.distanceMeters,
        durationSeconds: this.parseGoogleDuration(route.duration),
        quality: 'road',
        provider: 'google_routes',
      };
    } catch (err) {
      this.logger.warn(
        `Google Routes cagrisi basarisiz: ${err instanceof Error ? err.message : String(err)}`,
      );
      return null;
    }
  }

  private async tryOsrm(origin: LatLng, destination: LatLng): Promise<RouteResult | null> {
    try {
      const coords = `${origin.lon},${origin.lat};${destination.lon},${destination.lat}`;
      const url = `${OSRM_BASE_URL}/${coords}?overview=full&geometries=polyline`;
      const response = await this.fetchWithTimeout(url, { method: 'GET' });
      if (!response.ok) return null;

      const payload = (await response.json()) as {
        code?: string;
        routes?: Array<{ geometry?: string; distance?: number; duration?: number }>;
      };

      if (payload.code !== 'Ok') return null;
      const route = payload.routes?.[0];
      if (!route?.geometry || typeof route.distance !== 'number') return null;

      return {
        encodedPolyline: route.geometry,
        distanceMeters: Math.round(route.distance),
        durationSeconds: typeof route.duration === 'number' ? Math.round(route.duration) : null,
        quality: 'road',
        provider: 'osrm',
      };
    } catch (err) {
      this.logger.warn(
        `OSRM cagrisi basarisiz: ${err instanceof Error ? err.message : String(err)}`,
      );
      return null;
    }
  }

  /** Son care: iki noktayi birlestiren dogru parcasi. */
  private straightLine(origin: LatLng, destination: LatLng): RouteResult {
    const straight = haversineMeters(origin, destination);
    const approxDriving = Math.round(straight * DETOUR_FACTOR);
    return {
      encodedPolyline: encodePolyline([
        [origin.lat, origin.lon],
        [destination.lat, destination.lon],
      ]),
      distanceMeters: approxDriving,
      durationSeconds: Math.round((approxDriving / 1000 / FALLBACK_AVG_SPEED_KMH) * 3600),
      quality: 'straight_line',
      provider: 'haversine',
    };
  }

  /** Google sureyi "123s" bicimiyle doner. */
  private parseGoogleDuration(value: string | undefined): number | null {
    if (!value) return null;
    const match = /^(\d+(?:\.\d+)?)s$/.exec(value);
    return match ? Math.round(Number(match[1])) : null;
  }

  private async fetchWithTimeout(url: string, init: RequestInit): Promise<Response> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      return await fetch(url, { ...init, signal: controller.signal });
    } finally {
      clearTimeout(timer);
    }
  }
}

export function haversineMeters(a: LatLng, b: LatLng): number {
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLon = toRad(b.lon - a.lon);
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);
  const h = Math.sin(dLat / 2) ** 2 + Math.sin(dLon / 2) ** 2 * Math.cos(lat1) * Math.cos(lat2);
  return Math.round(2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(h)));
}

/** Google encoded polyline (precision 5) kodlayicisi. */
export function encodePolyline(points: Array<[number, number]>): string {
  let lastLat = 0;
  let lastLon = 0;
  let result = '';

  for (const [lat, lon] of points) {
    const latE5 = Math.round(lat * 1e5);
    const lonE5 = Math.round(lon * 1e5);
    result += encodeSignedNumber(latE5 - lastLat);
    result += encodeSignedNumber(lonE5 - lastLon);
    lastLat = latE5;
    lastLon = lonE5;
  }
  return result;
}

function encodeSignedNumber(value: number): string {
  let v = value < 0 ? ~(value << 1) : value << 1;
  let output = '';
  while (v >= 0x20) {
    output += String.fromCharCode((0x20 | (v & 0x1f)) + 63);
    v >>= 5;
  }
  output += String.fromCharCode(v + 63);
  return output;
}
