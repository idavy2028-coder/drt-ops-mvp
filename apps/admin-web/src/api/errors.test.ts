import { describe, expect, it } from "vitest";
import { ApiError, apiErrorFromResponse, userMessage } from "./errors";

describe("userMessage", () => {
  it("converts HTTP errors into operational guidance", () => {
    expect(userMessage(new ApiError(401), "加载失败")).toBe("登录状态已失效，请重新登录");
    expect(userMessage(new ApiError(403), "加载失败")).toBe("当前账号没有执行此操作的权限");
    expect(userMessage(new ApiError(409), "加载失败")).toBe("当前数据状态已变化，请刷新后重试");
  });

  it("preserves a business message returned by the API", async () => {
    const error = await apiErrorFromResponse(new Response(JSON.stringify({ data: { message: "订单尚未进入可调度状态" } }), {
      status: 409,
      headers: { "Content-Type": "application/json" }
    }));
    expect(userMessage(error, "调度失败")).toBe("订单尚未进入可调度状态");
  });

  it("does not expose technical messages returned by server errors", async () => {
    const error = await apiErrorFromResponse(new Response(JSON.stringify({
      data: { message: "org.postgresql.util.PSQLException: relation ride_order does not exist" }
    }), {
      status: 500,
      headers: { "Content-Type": "application/json" }
    }));

    expect(userMessage(error, "加载失败")).toBe("服务暂时不可用，请稍后重试");
  });
});
