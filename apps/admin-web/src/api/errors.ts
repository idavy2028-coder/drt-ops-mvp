export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message?: string
  ) {
    super(message || defaultMessage(status));
    this.name = "ApiError";
  }
}

export async function apiErrorFromResponse(response: Response): Promise<ApiError> {
  const body = await response.text();
  return new ApiError(response.status, extractMessage(body));
}

export function userMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  if (error instanceof TypeError) {
    return "暂时无法连接运营服务，请检查网络后重试";
  }
  return fallback;
}

function extractMessage(body: string): string | undefined {
  if (!body) {
    return undefined;
  }
  try {
    const parsed = JSON.parse(body) as { data?: { message?: unknown } };
    return typeof parsed.data?.message === "string" ? parsed.data.message : undefined;
  } catch {
    return undefined;
  }
}

function defaultMessage(status: number): string {
  switch (status) {
    case 400:
      return "提交内容不符合要求，请检查后重试";
    case 401:
      return "登录状态已失效，请重新登录";
    case 403:
      return "当前账号没有执行此操作的权限";
    case 404:
      return "未找到对应数据，可能已被删除或变更";
    case 409:
      return "当前数据状态已变化，请刷新后重试";
    default:
      return "服务暂时不可用，请稍后重试";
  }
}
