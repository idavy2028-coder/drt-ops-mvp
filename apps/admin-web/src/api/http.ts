import { authStore } from "../auth/authStore";
import { apiErrorFromResponse } from "./errors";

export interface ApiResponseEnvelope<T> {
  data: T;
}

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? "";
let refreshPromise: Promise<boolean> | null = null;

export function unwrapApiResponse<T>(response: ApiResponseEnvelope<T>): T {
  return response.data;
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  return sendRequest<T>(path, options, true);
}

async function sendRequest<T>(path: string, options: RequestInit, canRefresh: boolean): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body !== undefined && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  if (authStore.accessToken !== null) {
    headers.set("Authorization", `Bearer ${authStore.accessToken}`);
  }

  const response = await fetch(buildUrl(path), {
    ...options,
    headers
  });

  if (response.status === 401 && canRefresh) {
    refreshPromise ??= authStore.refresh().finally(() => {
      refreshPromise = null;
    });
    if (await refreshPromise) {
      return sendRequest<T>(path, options, false);
    }
  }

  if (!response.ok) {
    throw await apiErrorFromResponse(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return unwrapApiResponse((await response.json()) as ApiResponseEnvelope<T>);
}

function buildUrl(path: string): string {
  const normalizedBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.slice(0, -1) : apiBaseUrl;
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  return `${normalizedBaseUrl}${normalizedPath}`;
}
