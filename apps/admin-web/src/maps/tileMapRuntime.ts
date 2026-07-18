import * as L from "leaflet";
import "leaflet/dist/leaflet.css";
import "@geoman-io/leaflet-geoman-free/dist/leaflet-geoman.css";
import { fromLeafletLatLng, toLeafletLatLng } from "./coordinateTransform";
import type { GeoPoint, TileMapHandle } from "./tileMapTypes";

const DEFAULT_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
const DEFAULT_TILE_ATTRIBUTION = "&copy; <a href=\"https://www.openstreetmap.org/copyright\" target=\"_blank\" rel=\"noreferrer\">OpenStreetMap contributors</a>";
const DEFAULT_TILE_MAX_ZOOM = 19;

export function createTileMap(container: HTMLElement, centerGcj02: GeoPoint, zoom: number): TileMapHandle {
  const map = L.map(container).setView(toLeafletLatLng(centerGcj02), zoom);
  const baseLayer = L.tileLayer(tileUrl(), {
    attribution: tileAttribution(),
    maxZoom: tileMaxZoom()
  }).addTo(map);
  let baseLayerFailed = false;
  const baseLayerErrorListeners = new Set<() => void>();

  baseLayer.on("tileerror", () => {
    baseLayerFailed = true;
    baseLayerErrorListeners.forEach((listener) => listener());
  });

  return {
    map,
    get baseLayerFailed(): boolean {
      return baseLayerFailed;
    },
    destroy(): void {
      baseLayerErrorListeners.clear();
      map.remove();
    },
    fitLayers(layers: L.Layer[]): void {
      if (layers.length === 0) {
        return;
      }

      const bounds = L.featureGroup(layers).getBounds();
      if (bounds.isValid()) {
        map.fitBounds(bounds, { padding: [24, 24] });
      }
    },
    onBaseLayerError(listener: () => void): () => void {
      baseLayerErrorListeners.add(listener);
      return () => baseLayerErrorListeners.delete(listener);
    },
    onClick(listener: (point: GeoPoint) => void): () => void {
      const handler = (event: L.LeafletMouseEvent): void => {
        listener(fromLeafletLatLng(event.latlng.lat, event.latlng.lng));
      };
      map.on("click", handler);
      return () => map.off("click", handler);
    }
  };
}

function tileUrl(): string {
  return import.meta.env.VITE_TILE_URL?.trim() || DEFAULT_TILE_URL;
}

function tileAttribution(): string {
  return import.meta.env.VITE_TILE_ATTRIBUTION?.trim() || DEFAULT_TILE_ATTRIBUTION;
}

function tileMaxZoom(): number {
  const configured = Number(import.meta.env.VITE_TILE_MAX_ZOOM);
  return Number.isFinite(configured) && configured > 0 ? configured : DEFAULT_TILE_MAX_ZOOM;
}
