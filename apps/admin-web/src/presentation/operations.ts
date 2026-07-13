import type { DecimalValue } from "../api/types";

const labels: Record<string, string> = {
  PENDING_DISPATCH: "待调度",
  PENDING_MANUAL_REVIEW: "待人工复核",
  CONFIRMED: "已确认",
  IN_PROGRESS: "执行中",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
  UNSERVICEABLE: "不可服务",
  EXCEPTION_CLOSED: "异常关闭",
  DISPATCHED: "待发车",
  EXCEPTION: "异常任务",
  PLANNED: "计划中",
  ARRIVED: "已到站",
  BOARDED: "已上车",
  ALIGHTED: "已下车",
  IDLE: "空闲",
  AVAILABLE: "可用",
  ENABLED: "启用",
  DISABLED: "停用",
  QUALIFIED: "资质有效",
  SYSTEM: "系统",
  USER: "用户",
  RIDE_ORDER: "订单",
  VEHICLE_TASK: "车辆任务",
  USER_ACCOUNT: "用户账号",
  ORDER_PENDING_MANUAL_REVIEW: "转入人工复核",
  MANUAL_REVIEW_APPROVED: "人工确认派单",
  MANUAL_REVIEW_REJECTED: "人工拒绝派单",
  TASK_STARTED: "车辆发车",
  TASK_STOP_ARRIVED: "车辆到站",
  PASSENGER_BOARDED: "乘客上车",
  PASSENGER_ALIGHTED: "乘客下车",
  TASK_COMPLETED: "任务完成",
  TASK_EXCEPTION: "车辆故障关闭",
  TASK_SEVERE_DELAY: "严重延误关闭",
  ORDER_CANCELLED: "订单取消",
  ORDER_NO_SHOW: "乘客未到关闭",
  USER_CREATED: "创建用户",
  USER_PASSWORD_RESET: "重置密码",
  AUTH_LOGIN_FAILED: "登录失败"
};

const auditReasonLabels: Record<string, string> = {
  MANUAL_REVIEW_THRESHOLD_REACHED: "自动派单未满足阈值，转人工复核",
  "invalid authentication attempt": "用户名或密码不正确"
};

function metricNumber(value: DecimalValue): number | null {
  if (typeof value === "string" && value.trim() === "") {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export function formatPercentage(value: DecimalValue): string {
  const parsed = metricNumber(value);
  return parsed === null ? String(value) : `${Math.round(parsed * 100)}%`;
}

export function formatMinutes(value: DecimalValue): string {
  const parsed = metricNumber(value);
  return parsed === null ? String(value) : `${parsed.toFixed(1)} 分钟`;
}

export function labelFor(code: string): string {
  return labels[code] ?? code;
}

export function statusTone(code: string): "neutral" | "active" | "success" | "warning" | "danger" {
  if (["COMPLETED", "CONFIRMED", "BOARDED", "ALIGHTED", "AVAILABLE", "QUALIFIED", "ENABLED"].includes(code)) {
    return "success";
  }
  if (["PENDING_MANUAL_REVIEW", "DISPATCHED"].includes(code)) {
    return "warning";
  }
  if (["IN_PROGRESS", "ARRIVED", "PENDING_DISPATCH", "PLANNED"].includes(code)) {
    return "active";
  }
  if (["CANCELLED", "UNSERVICEABLE", "EXCEPTION", "EXCEPTION_CLOSED", "DISABLED"].includes(code)) {
    return "danger";
  }
  return "neutral";
}

export function shortId(value: string): string {
  return value.length > 8 ? value.slice(0, 8) : value;
}

export function auditReasonFor(reason: string | null | undefined): string {
  if (!reason) {
    return "--";
  }
  if (auditReasonLabels[reason]) {
    return auditReasonLabels[reason];
  }
  if (/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(reason)) {
    return `关联站点 · ${shortId(reason)}`;
  }
  return reason;
}

export function displayDateTime(value: string): string {
  return new Date(value).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  });
}
