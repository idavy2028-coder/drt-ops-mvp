// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import RuleSetCreateForm from "./RuleSetCreateForm.vue";

describe("RuleSetCreateForm", () => {
  afterEach(() => cleanup());

  it("emits the confirmed Tongwei defaults with the supplied name", async () => {
    const { emitted } = render(RuleSetCreateForm, { props: { saving: false } });

    await fireEvent.update(screen.getByLabelText("规则组名称"), "通渭县试点动态调度规则");
    await fireEvent.click(screen.getByRole("button", { name: "创建规则组" }));

    expect(emitted().create).toEqual([[{
      name: "通渭县试点动态调度规则",
      maxWaitMinutes: 5,
      maxDetourMinutes: 8,
      bookingWindowMinutes: 60,
      autoDispatchScoreThreshold: 82,
      manualReviewScoreThreshold: 62,
      waitWeight: 0.35,
      detourWeight: 0.20,
      stabilityWeight: 0.30,
      utilizationWeight: 0.15,
      insertionPolicy: "REALTIME_INSERTION"
    }]]);
  });

  it("disables the create action while submitting", () => {
    render(RuleSetCreateForm, { props: { saving: true } });

    expect(screen.getByRole("button", { name: "正在创建" })).toBeDisabled();
  });
});
