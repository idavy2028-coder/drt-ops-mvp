import { describe, expect, it } from "vitest";
import { auditReasonFor } from "./operations";

describe("auditReasonFor", () => {
  it("translates a manual review reason into an operational explanation", () => {
    expect(auditReasonFor("MANUAL_REVIEW_THRESHOLD_REACHED")).toBe("自动派单未满足阈值，转人工复核");
  });

  it("presents an audit stop identifier as a readable reference", () => {
    expect(auditReasonFor("3923bcea-fcee-4dd0-85cd-a410fa11dc12")).toBe("关联站点 · 3923bcea");
  });
});
