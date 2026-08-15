// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import type { VehicleAlarmView } from "../api/vehicleAlarms";
import AlarmBoard from "./AlarmBoard.vue";

describe("AlarmBoard", () => {
  afterEach(cleanup);

  it("keeps new alarms first while exposing operational summaries and every requested filter", async () => {
    render(AlarmBoard, { props: { alarms: alarms(), canHandle: true } });

    const metrics = within(screen.getByLabelText("报警概览"));
    expect(metrics.getByText("未确认")).toBeInTheDocument();
    expect(metrics.getByText("最高等级")).toBeInTheDocument();
    expect(metrics.getByText("最近发生")).toBeInTheDocument();
    expect(screen.getByText("位置可疑")).toBeInTheDocument();
    expect(screen.getByText("附件暂不可用")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /查看报警/ })[0]).toHaveTextContent("疲劳驾驶");

    await fireEvent.update(screen.getByLabelText("报警模块"), "DMS");
    expect(screen.getAllByRole("button", { name: /查看报警/ })).toHaveLength(1);
    expect(screen.getByRole("button", { name: /查看报警 甘G·A1002 疲劳驾驶/ })).toBeInTheDocument();

    await fireEvent.update(screen.getByLabelText("报警模块"), "ALL");
    await fireEvent.update(screen.getByLabelText("车辆车牌"), "甘G·A1001");
    expect(screen.getAllByRole("button", { name: /查看报警/ })).toHaveLength(1);
    expect(screen.getByRole("button", { name: /查看报警 甘G·A1001 前向碰撞/ })).toBeInTheDocument();
  });

  it("requires a reason and an explicit confirmation before emitting a public-id action", async () => {
    const view = render(AlarmBoard, { props: { alarms: [alarms()[1]], canHandle: true } });

    await fireEvent.click(screen.getByRole("button", { name: /查看报警/ }));
    await fireEvent.click(screen.getByRole("button", { name: "确认报警" }));
    const confirm = screen.getByRole("button", { name: "确认执行" });
    expect(confirm).toBeDisabled();
    await fireEvent.update(screen.getByLabelText("处理原因（同时作为备注）"), "已电话核实驾驶员状态");
    await fireEvent.click(screen.getByLabelText("我已核实并确认执行该处理"));
    expect(confirm).toBeEnabled();
    await fireEvent.click(confirm);

    expect(view.emitted("action")).toEqual([[{
      publicId: "alarm-new", action: "ACKNOWLEDGE", expectedVersion: 6,
      reason: "已电话核实驾驶员状态", confirmed: true
    }]]);
  });

  it("does not render handling controls for read-only users or any internal alarm material", async () => {
    const sensitiveAlarm = {
      ...alarms()[0],
      terminalId: "terminal-secret", payloadDigest: "digest-secret", terminalAlarmIdentifier: "terminal-alarm-secret",
      permanentMediaUrl: "https://media.example/internal/secret"
    } as VehicleAlarmView;
    render(AlarmBoard, { props: { alarms: [sensitiveAlarm], canHandle: false } });

    expect(screen.getByRole("button", { name: /查看报警 甘G·A1003 车道偏离/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "确认报警" })).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent("terminal-secret");
    expect(document.body).not.toHaveTextContent("digest-secret");
    expect(document.body).not.toHaveTextContent("terminal-alarm-secret");
    expect(document.body).not.toHaveTextContent("https://media.example/internal/secret");
  });

  it("collapses without removing the map-adjacent header affordance", async () => {
    render(AlarmBoard, { props: { alarms: alarms(), canHandle: true } });

    await fireEvent.click(screen.getByRole("button", { name: "收起报警看板" }));
    expect(screen.getByRole("button", { name: "展开报警看板" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /查看报警/ })).not.toBeInTheDocument();
  });
});

function alarms(): VehicleAlarmView[] {
  return [
    {
      publicId: "alarm-quarantined", vehicleId: "vehicle-3", plateNumber: "甘G·A1003", standard: "T/JSATL12-2017",
      module: "ADAS", alarmTypeCode: 3, alarmType: "车道偏离", level: 2, status: "NEW",
      occurredAt: "2026-08-15T02:02:00Z", endedAt: null, locationQualityStatus: "QUARANTINED", hasAttachment: true,
      version: 2, longitude: 0, latitude: 0, speedKph: null
    },
    {
      publicId: "alarm-new", vehicleId: "vehicle-2", plateNumber: "甘G·A1002", standard: "T/JSATL12-2017",
      module: "DMS", alarmTypeCode: 2, alarmType: "疲劳驾驶", level: 3, status: "NEW",
      occurredAt: "2026-08-15T02:04:00Z", endedAt: null, locationQualityStatus: "GOOD", hasAttachment: false,
      version: 6, longitude: 118, latitude: 32, speedKph: 52
    },
    {
      publicId: "alarm-acknowledged", vehicleId: "vehicle-1", plateNumber: "甘G·A1001", standard: "T/JSATL12-2017",
      module: "ADAS", alarmTypeCode: 1, alarmType: "前向碰撞", level: 1, status: "ACKNOWLEDGED",
      occurredAt: "2026-08-15T01:58:00Z", endedAt: null, locationQualityStatus: "GOOD", hasAttachment: false,
      version: 4, longitude: 118.1, latitude: 32.1, speedKph: 60
    }
  ];
}
