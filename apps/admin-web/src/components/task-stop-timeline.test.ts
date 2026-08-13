// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import type { TaskStop } from "../api/types";
import TaskStopTimeline from "./TaskStopTimeline.vue";

const stops: TaskStop[] = [
  {
    id: "stop-2",
    virtualStopId: "hospital",
    sequenceNumber: 2,
    stopType: "ALIGHTING",
    plannedArrivalAt: "2026-08-13T01:28:00Z",
    status: "PLANNED"
  },
  {
    id: "stop-1",
    virtualStopId: "station",
    sequenceNumber: 1,
    stopType: "BOARDING",
    plannedArrivalAt: "2026-08-13T01:05:00Z",
    actualArrivalAt: "2026-08-13T01:06:00Z",
    status: "BOARDED"
  },
  {
    id: "stop-4",
    virtualStopId: "market",
    sequenceNumber: 4,
    stopType: "ALIGHTING",
    plannedArrivalAt: "2026-08-13T01:17:00Z",
    actualArrivalAt: "2026-08-13T01:18:00Z",
    status: "ALIGHTED"
  },
  {
    id: "stop-3",
    virtualStopId: "square",
    sequenceNumber: 3,
    stopType: "BOARDING",
    plannedArrivalAt: "2026-08-13T01:14:00Z",
    actualArrivalAt: "2026-08-13T01:15:00Z",
    status: "ARRIVED"
  },
  {
    id: "stop-5",
    virtualStopId: "missing-stop-1234",
    sequenceNumber: 5,
    stopType: "BOARDING",
    plannedArrivalAt: "invalid",
    status: "PLANNED"
  },
  {
    id: "stop-6",
    virtualStopId: "no-actual",
    sequenceNumber: 6,
    stopType: "BOARDING",
    plannedArrivalAt: "2026-08-13T01:40:00Z",
    status: "ARRIVED"
  }
];

describe("TaskStopTimeline", () => {
  afterEach(cleanup);

  it("按任务序号匹配站名，并使用上海时区展示实际或计划时间", () => {
    render(TaskStopTimeline, {
      props: {
        stops,
        stopNameById: {
          market: "文化广场",
          hospital: "通渭县人民医院",
          station: "通渭县汽车站",
          square: "中心广场"
        }
      }
    });

    const items = screen.getAllByRole("listitem");
    expect(items.map((item) => item.textContent)).toEqual([
      expect.stringContaining("通渭县汽车站"),
      expect.stringContaining("通渭县人民医院"),
      expect.stringContaining("中心广场"),
      expect.stringContaining("文化广场"),
      expect.stringContaining("未知站点 · missing-"),
      expect.stringContaining("未知站点 · no-actua")
    ]);
    expect(screen.getByText("已到站 09:06 · 已上车")).toBeInTheDocument();
    expect(screen.getByText("计划到站 09:28")).toBeInTheDocument();
    expect(screen.getByText("已到站 09:15")).toBeInTheDocument();
    expect(screen.getByText("已到站 09:18 · 已下车")).toBeInTheDocument();
    expect(screen.getByText("计划到站 --")).toBeInTheDocument();
    expect(screen.getByText("已到站 --")).toBeInTheDocument();
    expect(items[0]).toHaveClass("is-complete");
    expect(items[2]).toHaveClass("is-current");
    expect(items[1]).toHaveClass("is-upcoming");
  });

  it("没有站点时保留可访问的友好空状态", () => {
    render(TaskStopTimeline, { props: { stops: [], stopNameById: {} } });

    expect(screen.getByRole("list", { name: "站点步骤" })).toHaveTextContent("暂无站点");
    expect(screen.getByText("等待任务同步")).toBeInTheDocument();
  });
});
