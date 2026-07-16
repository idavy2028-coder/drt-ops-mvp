import type { MapProviderStatus } from "../api/types";

export type AmapGlobal = unknown;

export interface AmapRuntime extends MapProviderStatus {
  provider: "AMAP";
  AMap?: AmapGlobal;
}

declare global {
  interface Window {
    AMap?: AmapGlobal;
    _AMapSecurityConfig?: {
      securityJsCode: string;
    };
  }
}
