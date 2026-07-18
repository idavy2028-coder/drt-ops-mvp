import type { GeoPoint } from "./tileMapTypes";

const EARTH_RADIUS = 6378245;
const ECCENTRICITY_SQUARED = 0.00669342162296594323;
const PI = Math.PI;

export function gcj02ToWgs84(point: GeoPoint): GeoPoint {
  if (isOutsideChina(point)) {
    return { ...point };
  }

  let longitude = point.longitude;
  let latitude = point.latitude;

  for (let attempt = 0; attempt < 10; attempt += 1) {
    const converted = wgs84ToGcj02({ longitude, latitude });
    const longitudeDelta = converted.longitude - point.longitude;
    const latitudeDelta = converted.latitude - point.latitude;

    longitude -= longitudeDelta;
    latitude -= latitudeDelta;

    if (Math.abs(longitudeDelta) < 1e-7 && Math.abs(latitudeDelta) < 1e-7) {
      break;
    }
  }

  return { longitude, latitude };
}

export function wgs84ToGcj02(point: GeoPoint): GeoPoint {
  if (isOutsideChina(point)) {
    return { ...point };
  }

  const latitudeOffset = transformLatitude(point.longitude - 105, point.latitude - 35);
  const longitudeOffset = transformLongitude(point.longitude - 105, point.latitude - 35);
  const radians = point.latitude / 180 * PI;
  const magic = 1 - ECCENTRICITY_SQUARED * Math.sin(radians) * Math.sin(radians);
  const squareRootMagic = Math.sqrt(magic);

  return {
    longitude: point.longitude + longitudeOffset * 180 / (EARTH_RADIUS / squareRootMagic * Math.cos(radians) * PI),
    latitude: point.latitude + latitudeOffset * 180 / ((EARTH_RADIUS * (1 - ECCENTRICITY_SQUARED)) / (magic * squareRootMagic) * PI)
  };
}

export function toLeafletLatLng(point: GeoPoint): [number, number] {
  const wgs84 = gcj02ToWgs84(point);
  return [wgs84.latitude, wgs84.longitude];
}

export function fromLeafletLatLng(latitude: number, longitude: number): GeoPoint {
  return wgs84ToGcj02({ longitude, latitude });
}

function isOutsideChina(point: GeoPoint): boolean {
  return point.longitude < 72.004 || point.longitude > 137.8347 || point.latitude < 0.8293 || point.latitude > 55.8271;
}

function transformLatitude(longitude: number, latitude: number): number {
  let result = -100 + 2 * longitude + 3 * latitude + 0.2 * latitude * latitude + 0.1 * longitude * latitude + 0.2 * Math.sqrt(Math.abs(longitude));
  result += (20 * Math.sin(6 * longitude * PI) + 20 * Math.sin(2 * longitude * PI)) * 2 / 3;
  result += (20 * Math.sin(latitude * PI) + 40 * Math.sin(latitude / 3 * PI)) * 2 / 3;
  return result + (160 * Math.sin(latitude / 12 * PI) + 320 * Math.sin(latitude * PI / 30)) * 2 / 3;
}

function transformLongitude(longitude: number, latitude: number): number {
  let result = 300 + longitude + 2 * latitude + 0.1 * longitude * longitude + 0.1 * longitude * latitude + 0.1 * Math.sqrt(Math.abs(longitude));
  result += (20 * Math.sin(6 * longitude * PI) + 20 * Math.sin(2 * longitude * PI)) * 2 / 3;
  result += (20 * Math.sin(longitude * PI) + 40 * Math.sin(longitude / 3 * PI)) * 2 / 3;
  return result + (150 * Math.sin(longitude / 12 * PI) + 300 * Math.sin(longitude / 30 * PI)) * 2 / 3;
}
