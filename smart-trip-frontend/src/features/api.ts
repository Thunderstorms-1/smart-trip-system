export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const token = localStorage.getItem("AUTH_TOKEN");
  const form = body instanceof FormData;
  let r: Response;
  try {
    r = await fetch("/api" + path, {
      method,
      headers: {
        ...(body && !form ? { "Content-Type": "application/json" } : {}),
        ...(token ? { Authorization: "Bearer " + token } : {}),
      },
      body: body ? (form ? body : JSON.stringify(body)) : undefined,
    });
  } catch {
    throw new ApiError(0, "连接失败，请检查网络后重试");
  }
  const v = await r.json().catch(() => null);
  if (!r.ok || v?.code !== 200)
    throw new ApiError(
      r.status,
      v?.message ||
        (r.status === 401 ? "登录已过期，请重新登录" : "服务暂时不可用"),
    );
  return v.data as T;
}
export const money = (n?: number) =>
  new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency: "CNY",
    maximumFractionDigits: 0,
  }).format(n || 0);
export const requestKey = () => crypto.randomUUID();
