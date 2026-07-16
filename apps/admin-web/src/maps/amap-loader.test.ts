// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { loadAmap, resetAmapLoaderForTest } from "./amapLoader";

describe("loadAmap", () => {
  afterEach(() => {
    resetAmapLoaderForTest();
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    document.head.innerHTML = "";
  });

  it("returns a degraded runtime when Amap is disabled", async () => {
    vi.stubEnv("VITE_AMAP_ENABLED", "false");
    vi.stubEnv("VITE_AMAP_JS_API_KEY", "");

    await expect(loadAmap()).resolves.toEqual({
      provider: "AMAP",
      available: false,
      degradedReason: "disabled",
      coordinateSystem: "GCJ-02"
    });
  });

  it("returns a degraded runtime when the JS API key is missing", async () => {
    vi.stubEnv("VITE_AMAP_ENABLED", "true");
    vi.stubEnv("VITE_AMAP_JS_API_KEY", "");

    await expect(loadAmap()).resolves.toEqual({
      provider: "AMAP",
      available: false,
      degradedReason: "missing-js-api-key",
      coordinateSystem: "GCJ-02"
    });
  });

  it("loads the JS API once when Amap is enabled", async () => {
    vi.stubEnv("VITE_AMAP_ENABLED", "true");
    vi.stubEnv("VITE_AMAP_JS_API_KEY", "test-js-key");
    vi.stubEnv("VITE_AMAP_SECURITY_JS_CODE", "test-security-code");

    const appendChild = vi.spyOn(document.head, "appendChild");
    const loadPromise = loadAmap();
    const script = appendChild.mock.calls[0]?.[0] as HTMLScriptElement;
    expect(script).toBeInstanceOf(HTMLScriptElement);
    expect(script.src).toContain("https://webapi.amap.com/maps");
    expect(script.src).toContain("key=test-js-key");
    expect(script.src).toContain("v=2.0");
    expect(script.src).toContain("plugin=AMap.PlaceSearch%2CAMap.Geocoder");
    expect(window._AMapSecurityConfig).toEqual({ securityJsCode: "test-security-code" });

    vi.stubGlobal("AMap", { version: "2.0" });
    script.dispatchEvent(new Event("load"));

    await expect(loadPromise).resolves.toEqual({
      provider: "AMAP",
      available: true,
      coordinateSystem: "GCJ-02",
      AMap: { version: "2.0" }
    });
    expect(appendChild).toHaveBeenCalledTimes(1);
  });
});
