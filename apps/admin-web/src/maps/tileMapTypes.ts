import type * as L from "leaflet";

export interface GeoPoint {
  longitude: number;
  latitude: number;
}

export interface TileMapHandle {
  map: L.Map;
  readonly baseLayerFailed: boolean;
  destroy(): void;
  fitLayers(layers: L.Layer[]): void;
  onBaseLayerError(listener: () => void): () => void;
  onClick(listener: (point: GeoPoint) => void): () => void;
}
