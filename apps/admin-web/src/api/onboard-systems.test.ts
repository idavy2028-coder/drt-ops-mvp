import { beforeEach, describe, expect, it, vi } from "vitest";
import type { OnboardConfigurationInput } from "./types";
import {
  applyOnboardConfiguration,
  getOnboardSystem,
  listOnboardSystems,
  previewOnboardConfiguration
} from "./onboardSystems";

const request = vi.hoisted(() => vi.fn());
vi.mock("./http", () => ({ request }));

describe("onboardSystems API", () => {
  beforeEach(() => request.mockReset());

  it("uses the exact paged aggregate list and masked detail paths", async () => {
    // Mutation caught: reverting the aggregate client to the physical-terminal API
    // or dropping paging parameters from the management list request.
    await listOnboardSystems(2, 25);
    await getOnboardSystem("33333333-3333-3333-3333-333333333331");

    expect(request).toHaveBeenNthCalledWith(1, "/api/onboard-systems?page=2&size=25");
    expect(request).toHaveBeenNthCalledWith(
      2,
      "/api/onboard-systems/33333333-3333-3333-3333-333333333331"
    );
  });

  it("sends exact preview and apply payloads with masked device aliases", async () => {
    // Mutation caught: posting to the wrong endpoint, dropping expectedVersion/reason,
    // or translating deviceAlias back into a raw terminal selector.
    const input: OnboardConfigurationInput = {
      expectedVersion: 7,
      operatingMode: "DISPATCH_SERVICE" as const,
      devices: [{
        deviceAlias: "device-aaaaaaaaaaaa",
        networkMode: "DIRECT_CELLULAR" as const,
        roles: ["DISPATCH", "LOCATION_PRIMARY", "WAN_UPLINK"] as const,
        protocolProfiles: {
          transportProfile: "JT808_2019",
          businessProfile: "GBT28787_2023",
          safetyProfile: "NONE",
          mediaProfile: "NONE",
          activePositionIntervalSeconds: 30,
          idlePositionIntervalSeconds: 60
        }
      }],
      reason: "双设备角色核对"
    };

    await previewOnboardConfiguration("33333333-3333-3333-3333-333333333331", input);
    await applyOnboardConfiguration("33333333-3333-3333-3333-333333333331", input);

    const expectedBody = JSON.stringify(input);
    expect(request).toHaveBeenNthCalledWith(
      1,
      "/api/onboard-systems/33333333-3333-3333-3333-333333333331/configuration/preview",
      { method: "POST", body: expectedBody }
    );
    expect(request).toHaveBeenNthCalledWith(
      2,
      "/api/onboard-systems/33333333-3333-3333-3333-333333333331/configuration",
      { method: "POST", body: expectedBody }
    );
    expect(expectedBody).toContain("device-aaaaaaaaaaaa");
    expect(expectedBody).not.toContain("terminalCode");
  });
});
