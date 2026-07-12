import type { AuthSession, CurrentUser } from "./types";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? "";

export function login(username: string, password: string): Promise<AuthSession> {
  return authRequest("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password })
  });
}

export function refresh(): Promise<AuthSession> {
  return authRequest("/api/auth/refresh", { method: "POST" });
}

export async function logout(): Promise<void> {
  await authRequest<void>("/api/auth/logout", { method: "POST" });
}

async function authRequest<T>(path: string, options: RequestInit): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body !== undefined && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const response = await fetch(buildUrl(path), { ...options, headers, credentials: "include" });
  if (!response.ok) {
    throw new Error(`Request failed with status ${response.status}`);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return ((await response.json()) as { data: T }).data;
}

function buildUrl(path: string): string {
  const normalizedBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.slice(0, -1) : apiBaseUrl;
  return `${normalizedBaseUrl}${path.startsWith("/") ? path : `/${path}`}`;
}

export type { AuthSession, CurrentUser };
