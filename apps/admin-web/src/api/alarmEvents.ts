import { authStore } from "../auth/authStore";
import type { UUID } from "./types";

export interface VehicleAlarmStreamEvent {
  publicId: UUID;
  type: string;
  status: string;
  level: number;
  module: string;
  occurredAt: string;
}

export interface VehicleAlarmEventHandlers {
  lastEventId?: string;
  onVehicleAlarm?: (event: VehicleAlarmStreamEvent) => void;
  onHeartbeat?: () => void;
  onResyncRequired?: () => void;
  onDegradedChange?: (degraded: boolean) => void;
  poll?: () => void | Promise<void>;
}

export interface VehicleAlarmEventSubscription {
  close(): void;
}

const RECONNECT_DELAYS_MS = [1_000, 2_000, 4_000] as const;
const POLL_INTERVAL_MS = 5_000;

export function subscribeVehicleAlarmEvents(handlers: VehicleAlarmEventHandlers): VehicleAlarmEventSubscription {
  let closed = false;
  let controller: AbortController | undefined;
  let reconnectTimer: number | undefined;
  let pollTimer: number | undefined;
  let consecutiveFailures = 0;
  let degraded = false;
  let lastEventId = handlers.lastEventId;
  let refreshAttempted = false;

  void connect();

  return {
    close() {
      closed = true;
      controller?.abort();
      if (reconnectTimer !== undefined) window.clearTimeout(reconnectTimer);
      stopPolling();
    }
  };

  async function connect(): Promise<void> {
    if (closed) return;
    controller = new AbortController();
    try {
      const response = await fetch(buildUrl("/api/vehicle-alarms/events"), {
        headers: eventHeaders(lastEventId),
        credentials: "include",
        signal: controller.signal
      });
      if (response.status === 401 && !refreshAttempted) {
        refreshAttempted = true;
        if (await authStore.refresh()) {
          await connect();
          return;
        }
      }
      if (!response.ok || response.body === null) {
        throw new Error(`vehicle alarm event stream failed with ${response.status}`);
      }
      await readEvents(response.body);
      if (!closed) scheduleReconnect();
    } catch (error) {
      if (!closed && !controller.signal.aborted) scheduleReconnect();
    }
  }

  function scheduleReconnect(): void {
    consecutiveFailures += 1;
    if (consecutiveFailures >= RECONNECT_DELAYS_MS.length) startPolling();
    const delay = RECONNECT_DELAYS_MS[Math.min(consecutiveFailures - 1, RECONNECT_DELAYS_MS.length - 1)];
    reconnectTimer = window.setTimeout(() => { void connect(); }, delay);
  }

  function startPolling(): void {
    if (pollTimer !== undefined) return;
    setDegraded(true);
    pollTimer = window.setInterval(() => { void handlers.poll?.(); }, POLL_INTERVAL_MS);
  }

  function stopPolling(): void {
    if (pollTimer === undefined) return;
    window.clearInterval(pollTimer);
    pollTimer = undefined;
  }

  function setDegraded(next: boolean): void {
    if (degraded === next) return;
    degraded = next;
    if (!next) stopPolling();
    handlers.onDegradedChange?.(next);
  }

  async function readEvents(stream: ReadableStream<Uint8Array>): Promise<void> {
    const reader = stream.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    try {
      while (!closed) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");
        let separator = buffer.indexOf("\n\n");
        while (separator >= 0) {
          dispatchEvent(buffer.slice(0, separator));
          buffer = buffer.slice(separator + 2);
          separator = buffer.indexOf("\n\n");
        }
      }
    } finally {
      reader.releaseLock();
    }
  }

  function dispatchEvent(frame: string): void {
    let event = "message";
    let id: string | undefined;
    const data: string[] = [];
    for (const line of frame.split("\n")) {
      if (line.startsWith(":")) continue;
      const separator = line.indexOf(":");
      const field = separator < 0 ? line : line.slice(0, separator);
      const value = separator < 0 ? "" : line.slice(separator + 1).replace(/^ /, "");
      if (field === "event") event = value;
      if (field === "id") id = value;
      if (field === "data") data.push(value);
    }
    if (id !== undefined) lastEventId = id;
    if (event === "heartbeat") {
      markHealthy();
      handlers.onHeartbeat?.();
      return;
    }
    if (event === "resync-required") {
      lastEventId = undefined;
      markHealthy();
      handlers.onResyncRequired?.();
      return;
    }
    if (event !== "vehicle-alarm" || data.length === 0) return;
    const parsed = JSON.parse(data.join("\n")) as unknown;
    if (isVehicleAlarmStreamEvent(parsed)) {
      markHealthy();
      handlers.onVehicleAlarm?.(parsed);
    }
  }

  function markHealthy(): void {
    consecutiveFailures = 0;
    refreshAttempted = false;
    setDegraded(false);
  }
}

function eventHeaders(lastEventId?: string): Headers {
  const headers = new Headers({ Accept: "text/event-stream" });
  if (authStore.accessToken !== null) headers.set("Authorization", `Bearer ${authStore.accessToken}`);
  if (lastEventId) headers.set("Last-Event-ID", lastEventId);
  return headers;
}

function buildUrl(path: string): string {
  const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? "";
  const normalizedBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.slice(0, -1) : apiBaseUrl;
  return `${normalizedBaseUrl}${path.startsWith("/") ? path : `/${path}`}`;
}

function isVehicleAlarmStreamEvent(value: unknown): value is VehicleAlarmStreamEvent {
  if (typeof value !== "object" || value === null) return false;
  const event = value as Record<string, unknown>;
  return typeof event.publicId === "string"
    && typeof event.type === "string"
    && typeof event.status === "string"
    && typeof event.level === "number"
    && typeof event.module === "string"
    && typeof event.occurredAt === "string";
}
