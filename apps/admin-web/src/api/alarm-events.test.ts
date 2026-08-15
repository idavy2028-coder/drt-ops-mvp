// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { authStore } from "../auth/authStore";
import { subscribeVehicleAlarmEvents } from "./alarmEvents";
import { listVehicleAlarms, submitVehicleAlarmAction } from "./vehicleAlarms";

const dispatcher = { id: "user-1", username: "dispatcher", roles: ["DISPATCHER"], mustChangePassword: false };

describe("subscribeVehicleAlarmEvents", () => {
  afterEach(() => {
    authStore.clearSessionForTest();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it("uses the bearer header and cursor while delivering a multiline vehicle alarm without a URL token", async () => {
    authStore.setSessionForTest({ accessToken: "access-secret", user: dispatcher });
    const received: Array<{ publicId: string; type: string; status: string; level: number; module: string; occurredAt: string }> = [];
    const fetchStub = vi.fn().mockResolvedValue(new Response(
      "id: 42:11111111-1111-1111-1111-111111111111\n"
      + "event: vehicle-alarm\n"
      + "data: {\"publicId\":\"22222222-2222-2222-2222-222222222222\",\n"
      + "data: \"type\":\"VEHICLE_ALARM_CREATED\",\"status\":\"NEW\",\"level\":2,\"module\":\"ADAS\",\"occurredAt\":\"2026-08-15T02:00:00Z\"}\n\n",
      { status: 200, headers: { "Content-Type": "text/event-stream" } }
    ));
    vi.stubGlobal("fetch", fetchStub);

    const stream = subscribeVehicleAlarmEvents({
      lastEventId: "41:00000000-0000-0000-0000-000000000001",
      onVehicleAlarm: (event) => received.push(event)
    });

    await vi.waitFor(() => expect(received).toEqual([{
      publicId: "22222222-2222-2222-2222-222222222222",
      type: "VEHICLE_ALARM_CREATED",
      status: "NEW",
      level: 2,
      module: "ADAS",
      occurredAt: "2026-08-15T02:00:00Z"
    }]));
    const [url, init] = fetchStub.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/vehicle-alarms/events");
    expect(url).not.toContain("access-secret");
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer access-secret");
    expect(new Headers(init.headers).get("Last-Event-ID")).toBe("41:00000000-0000-0000-0000-000000000001");
    stream.close();
  });

  it("refreshes once after a 401 and reconnects with the renewed bearer token", async () => {
    authStore.setSessionForTest({ accessToken: "expired-token", user: dispatcher });
    vi.spyOn(authStore, "refresh").mockImplementation(async () => {
      authStore.setSessionForTest({ accessToken: "renewed-token", user: dispatcher });
      return true;
    });
    const received: string[] = [];
    const fetchStub = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(new Response(
        "event: vehicle-alarm\ndata: {\"publicId\":\"22222222-2222-2222-2222-222222222222\",\"type\":\"VEHICLE_ALARM_CREATED\",\"status\":\"NEW\",\"level\":1,\"module\":\"DMS\",\"occurredAt\":\"2026-08-15T02:00:00Z\"}\n\n",
        { status: 200 }
      ));
    vi.stubGlobal("fetch", fetchStub);

    const stream = subscribeVehicleAlarmEvents({ onVehicleAlarm: (event) => received.push(event.publicId) });

    await vi.waitFor(() => expect(received).toEqual(["22222222-2222-2222-2222-222222222222"]));
    expect(new Headers(fetchStub.mock.calls[0]?.[1].headers).get("Authorization")).toBe("Bearer expired-token");
    expect(new Headers(fetchStub.mock.calls[1]?.[1].headers).get("Authorization")).toBe("Bearer renewed-token");
    stream.close();
  });

  it("backs off for one, two and four seconds then polls every five seconds until SSE recovers", async () => {
    vi.useFakeTimers();
    authStore.setSessionForTest({ accessToken: "access-secret", user: dispatcher });
    const fetchStub = vi.fn().mockRejectedValue(new TypeError("network unavailable"));
    vi.stubGlobal("fetch", fetchStub);
    const states: boolean[] = [];
    const polls: number[] = [];

    const stream = subscribeVehicleAlarmEvents({
      onDegradedChange: (degraded) => states.push(degraded),
      poll: () => { polls.push(Date.now()); }
    });

    await vi.runAllTicks();
    await vi.advanceTimersByTimeAsync(1_000);
    await vi.advanceTimersByTimeAsync(2_000);
    await vi.advanceTimersByTimeAsync(5_000);

    expect(fetchStub).toHaveBeenCalledTimes(4);
    expect(states).toEqual([true]);
    expect(polls).toHaveLength(1);
    stream.close();
  });

  it("aborts the pending fetch when the workbench is unmounted", async () => {
    authStore.setSessionForTest({ accessToken: "access-secret", user: dispatcher });
    let requestSignal: AbortSignal | undefined;
    vi.stubGlobal("fetch", vi.fn((_url: string, init: RequestInit) => {
      requestSignal = init.signal ?? undefined;
      return new Promise<Response>(() => undefined);
    }));

    const stream = subscribeVehicleAlarmEvents({});
    await vi.waitFor(() => expect(requestSignal).toBeDefined());
    stream.close();

    expect(requestSignal?.aborted).toBe(true);
  });
});

describe("vehicle alarm API", () => {
  afterEach(() => {
    authStore.clearSessionForTest();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("loads filtered alarms through the authorized read endpoint", async () => {
    authStore.setSessionForTest({ accessToken: "access-secret", user: dispatcher });
    const fetchStub = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: [{
      publicId: "22222222-2222-2222-2222-222222222222", vehicleId: "vehicle-1", plateNumber: "甘G·A1001",
      standard: "T/JSATL12-2017", module: "ADAS", alarmTypeCode: 1, alarmType: "前向碰撞", level: 2,
      status: "NEW", occurredAt: "2026-08-15T02:00:00Z", endedAt: null, locationQualityStatus: "GOOD",
      hasAttachment: true, version: 4, longitude: 118, latitude: 32, speedKph: 60
    }] })));
    vi.stubGlobal("fetch", fetchStub);

    const alarms = await listVehicleAlarms({ level: 2, status: "NEW", vehicleId: "vehicle-1", module: "ADAS", hasAttachment: true });

    expect(alarms[0]?.plateNumber).toBe("甘G·A1001");
    expect(fetchStub.mock.calls[0]?.[0]).toBe("/api/vehicle-alarms?level=2&status=NEW&vehicleId=vehicle-1&module=ADAS&hasAttachment=true");
    expect(new Headers(fetchStub.mock.calls[0]?.[1].headers).get("Authorization")).toBe("Bearer access-secret");
  });

  it("submits every alarm action against its public identity with a confirmed reason", async () => {
    authStore.setSessionForTest({ accessToken: "access-secret", user: dispatcher });
    const fetchStub = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: { publicId: "22222222-2222-2222-2222-222222222222" } })));
    vi.stubGlobal("fetch", fetchStub);

    await submitVehicleAlarmAction("22222222-2222-2222-2222-222222222222", {
      action: "ACKNOWLEDGE", expectedVersion: 4, reason: "已电话核实驾驶员状态", confirmed: true
    });

    expect(fetchStub.mock.calls[0]?.[0]).toBe("/api/vehicle-alarms/22222222-2222-2222-2222-222222222222/actions");
    expect(fetchStub.mock.calls[0]?.[1]).toEqual(expect.objectContaining({ method: "POST" }));
    expect(JSON.parse(fetchStub.mock.calls[0]?.[1].body as string)).toEqual({
      action: "ACKNOWLEDGE", expectedVersion: 4, reason: "已电话核实驾驶员状态", confirmed: true
    });
  });
});
