import { describe, expect, it } from "vitest";
import { gcj02ToWgs84, wgs84ToGcj02 } from "./coordinateTransform";

describe("GCJ-02 与 WGS84 坐标转换", () => {
  it("将通渭县 GCJ-02 坐标转换为 WGS84 后可还原", () => {
    const gcj02 = { longitude: 105.2421, latitude: 35.2103 };

    const wgs84 = gcj02ToWgs84(gcj02);
    const restored = wgs84ToGcj02(wgs84);

    expect(wgs84).not.toEqual(gcj02);
    expect(restored.longitude).toBeCloseTo(gcj02.longitude, 5);
    expect(restored.latitude).toBeCloseTo(gcj02.latitude, 5);
  });

  it("保持中国境外坐标不变", () => {
    const paris = { longitude: 2.3522, latitude: 48.8566 };

    expect(gcj02ToWgs84(paris)).toEqual(paris);
    expect(wgs84ToGcj02(paris)).toEqual(paris);
  });
});
