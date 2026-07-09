export interface ApiResponseEnvelope<T> {
  data: T;
}

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? "";

export function unwrapApiResponse<T>(response: ApiResponseEnvelope<T>): T {
  return response.data;
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body !== undefined && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(buildUrl(path), {
    ...options,
    headers
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Request failed with status ${response.status}`);
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
