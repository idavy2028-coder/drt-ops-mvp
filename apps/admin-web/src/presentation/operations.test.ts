import { describe, expect, it } from "vitest";
import { auditReasonFor, formatMinutes, formatPercentage } from "./operations";

it("formats a decimal ratio as a percentage", () => {
  expect(formatPercentage("0.875")).toBe("88%");
  expect(formatPercentage("0.145")).toBe("15%");
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
  it("translates known dispatch reasons into operational explanations", () => {
    expect(auditReasonFor("AUTO_DISPATCH")).toBe("自动派单");
    expect(auditReasonFor("MANUAL_REVIEW")).toBe("转人工复核");
    expect(auditReasonFor("NO_FEASIBLE_PLAN")).toBe("暂无可行派车方案");
    expect(auditReasonFor("PENDING_MANUAL_REVIEW")).toBe("转人工复核");
  });

  it("translates a manual review reason into an operational explanation", () => {
    expect(auditReasonFor("MANUAL_REVIEW_THRESHOLD_REACHED")).toBe("自动派单未满足阈值，转人工复核");
  });

  it.each([
    ["NO_CANDIDATE_TASK", "当前无运行车辆任务可用于调度"],
    ["ALL_CANDIDATES_REJECTED", "候选车辆均不满足调度约束"],
    ["AUTO_DISPATCH_THRESHOLD_REACHED", "最优方案达到自动派发阈值"],
    ["LOW_SCORE", "最优方案未达到人工复核阈值"]
  ])("translates algorithm reason %s", (reason, label) => {
    expect(auditReasonFor(reason)).toBe(label);
  });

  it("presents an audit stop identifier as a readable reference", () => {
    expect(auditReasonFor("3923bcea-fcee-4dd0-85cd-a410fa11dc12")).toBe("关联站点 · 3923bcea");
  });

  it("hides unknown system reason codes while preserving manual reasons", () => {
    expect(auditReasonFor("ROUTE_CAPACITY_EXHAUSTED")).toBe("系统处理原因（查看详情）");
    expect(auditReasonFor("调度员确认道路封闭")).toBe("调度员确认道路封闭");
  });
});
