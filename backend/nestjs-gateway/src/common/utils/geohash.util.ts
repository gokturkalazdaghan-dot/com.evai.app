// backend/nestjs-gateway/src/common/utils/geohash.util.ts
const BASE32_CHARS = '0123456789bcdefghjkmnpqrstuvwxyz';

/**
 * Standart geohash kodlaması. Flutter/RN ve iOS/Android edge katmanları ile
 * bit-bit aynı algoritmayı kullanır — bu sayede client-side üretilen
 * geohash'ler server tarafındaki ST_GeoHash çıktısıyla bire bir eşleşir ve
 * cache key çakışması sıfır.
 */
export function encodeGeohash(lat: number, lon: number, precision: number): string {
  const latRange: [number, number] = [-90, 90];
  const lonRange: [number, number] = [-180, 180];
  let isEven = true;
  let bit = 0;
  let ch = 0;
  let geohash = '';

  while (geohash.length < precision) {
    if (isEven) {
      const mid = (lonRange[0] + lonRange[1]) / 2;
      if (lon > mid) {
        ch |= 1 << (4 - bit);
        lonRange[0] = mid;
      } else {
        lonRange[1] = mid;
      }
    } else {
      const mid = (latRange[0] + latRange[1]) / 2;
      if (lat > mid) {
        ch |= 1 << (4 - bit);
        latRange[0] = mid;
      } else {
        latRange[1] = mid;
      }
    }

    isEven = !isEven;
    if (bit < 4) {
      bit++;
    } else {
      geohash += BASE32_CHARS[ch];
      bit = 0;
      ch = 0;
    }
  }

  return geohash;
}

export function truncateGeohash(geohash9: string, precision: 5 | 7 | 9): string {
  if (precision > geohash9.length) {
    throw new Error(`Geohash uzunluğu (${geohash9.length}) istenen hassasiyetten (${precision}) kısa.`);
  }
  return geohash9.slice(0, precision);
}

/**
 * Bir yarıçap (metre) için hangi geohash hassasiyetinin komşu-hücre
 * taramasında kullanılacağını belirler. Edge ve server aynı tabloyu kullanır.
 */
export function precisionForRadiusMeters(radiusMeters: number): 5 | 7 | 9 {
  if (radiusMeters <= 500) return 9;
  if (radiusMeters <= 5000) return 7;
  return 5;
}
