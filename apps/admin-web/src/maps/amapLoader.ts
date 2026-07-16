import type { AmapRuntime } from "./amapTypes";

const AMAP_JS_API_URL = "https://webapi.amap.com/maps";
const DEFAULT_PLUGINS = ["AMap.PlaceSearch", "AMap.Geocoder"];
const COORDINATE_SYSTEM = "GCJ-02";

let cachedRuntime: Promise<AmapRuntime> | undefined;

export function loadAmap(): Promise<AmapRuntime> {
  cachedRuntime ??= loadAmapRuntime();
  return cachedRuntime;
}

export function resetAmapLoaderForTest(): void {
  cachedRuntime = undefined;
  delete window.AMap;
  delete window._AMapSecurityConfig;
}

async function loadAmapRuntime(): Promise<AmapRuntime> {
  const enabled = isEnabled(import.meta.env.VITE_AMAP_ENABLED);
  if (!enabled) {
    return degraded("disabled");
  }

  const jsApiKey = import.meta.env.VITE_AMAP_JS_API_KEY?.trim();
  if (!jsApiKey) {
    return degraded("missing-js-api-key");
  }

  if (window.AMap) {
    return available(window.AMap);
  }

  const securityJsCode = import.meta.env.VITE_AMAP_SECURITY_JS_CODE?.trim();
  if (securityJsCode) {
    window._AMapSecurityConfig = { securityJsCode };
  }

  return new Promise<AmapRuntime>((resolve) => {
    const script = document.createElement("script");
    script.async = true;
    script.src = buildScriptUrl(jsApiKey);
    script.addEventListener("load", () => {
      resolve(window.AMap ? available(window.AMap) : degraded("js-api-not-ready"));
    }, { once: true });
    script.addEventListener("error", () => {
      resolve(degraded("js-api-load-failed"));
    }, { once: true });
    document.head.appendChild(script);
  });
}

function isEnabled(value: string | boolean | undefined): boolean {
  return value === true || value === "true" || value === "1";
}

function buildScriptUrl(jsApiKey: string): string {
  const params = new URLSearchParams({
    v: "2.0",
    key: jsApiKey,
    plugin: DEFAULT_PLUGINS.join(",")
  });
  return `${AMAP_JS_API_URL}?${params.toString()}`;
}

function available(AMap: unknown): AmapRuntime {
  return {
    provider: "AMAP",
    available: true,
    coordinateSystem: COORDINATE_SYSTEM,
    AMap
  };
}

function degraded(degradedReason: string): AmapRuntime {
  return {
    provider: "AMAP",
    available: false,
    degradedReason,
    coordinateSystem: COORDINATE_SYSTEM
  };
}
