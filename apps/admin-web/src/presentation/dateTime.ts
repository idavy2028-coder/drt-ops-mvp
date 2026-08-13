const SHANGHAI_TIME_ZONE = "Asia/Shanghai";

export function shanghaiDateKey(value?: string): string | null {
  const parts = dateParts(value, {
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  });

  return parts === null ? null : `${parts.year}-${parts.month}-${parts.day}`;
}

export function formatShanghaiDateTime(
  value?: string,
  mode: "table" | "time" = "table"
): string {
  const parts = dateParts(value, {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false
  });

  if (parts === null) {
    return "--";
  }

  return mode === "time"
    ? `${parts.hour}:${parts.minute}`
    : `${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`;
}

function dateParts(
  value: string | undefined,
  options: Intl.DateTimeFormatOptions
): Record<string, string> | null {
  if (!value) {
    return null;
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return null;
  }

  return Object.fromEntries(
    new Intl.DateTimeFormat("en-US", {
      timeZone: SHANGHAI_TIME_ZONE,
      ...options
    })
      .formatToParts(date)
      .map(({ type, value: partValue }) => [type, partValue])
  );
}
