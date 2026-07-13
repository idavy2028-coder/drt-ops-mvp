import { describe, expect, it } from "vitest";
import { auditReasonFor, formatMinutes, formatPercentage } from "./operations";

it("formats a decimal ratio as a percentage", () => {
  expect(formatPercentage("0.875")).toBe("88%");
  expect(formatPercentage(0)).toBe("0%");
});

it("formats minutes with one decimal place", () => {
  expect(formatMinutes("3")).toBe("3.0 分钟");
});

it("preserves an invalid metric value", () => {
  expect(formatPercentage("")).toBe("");
  expect(formatMinutes("   ")).toBe("   ");
  expect(formatPercentage("unknown")).toBe("unknown");
  expect(formatMinutes("unknown")).toBe("unknown");
});

describe("auditReasonFor", () => {
  it("translates a manual review reason into an operational explanation", () => {
    expect(auditReasonFor("MANUAL_REVIEW_THRESHOLD_REACHED")).toBe("自动派单未满足阈值，转人工复核");
  });

  it("presents an audit stop identifier as a readable reference", () => {
    expect(auditReasonFor("3923bcea-fcee-4dd0-85cd-a410fa11dc12")).toBe("关联站点 · 3923bcea");
  });
});
