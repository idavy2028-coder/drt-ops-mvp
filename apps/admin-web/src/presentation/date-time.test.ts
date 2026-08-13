import { describe, expect, it } from "vitest";
import { formatShanghaiDateTime, shanghaiDateKey } from "./dateTime";

describe("上海时区日期展示", () => {
  it("按上海日历日期对时间点分组", () => {
    expect(shanghaiDateKey("2026-08-12T16:30:00.000Z")).toBe("2026-08-13");
  });

  it("输出紧凑表格时间和时分，不暴露 ISO 字符串", () => {
    expect(formatShanghaiDateTime("2026-08-13T01:06:00.000Z", "table")).toBe("08-13 09:06");
    expect(formatShanghaiDateTime("2026-08-13T01:06:00.000Z", "time")).toBe("09:06");
  });

  it("为缺失值和非法时间返回明确兜底", () => {
    expect(shanghaiDateKey("invalid")).toBeNull();
    expect(formatShanghaiDateTime(undefined)).toBe("--");
    expect(formatShanghaiDateTime("invalid")).toBe("--");
  });
});
