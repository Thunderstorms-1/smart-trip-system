import { api } from "./api";
export interface MapInstance {
  destroy(): void;
  add(overlay: object): void;
  setFitView(overlays: object[]): void;
}
export interface AMapSDK {
  Map: new (host: HTMLElement, options: { zoom: number }) => MapInstance;
  Marker: new (options: { position: number[]; title: string }) => object;
  Polyline: new (options: {
    path: number[][];
    strokeColor: string;
    strokeWeight: number;
  }) => object;
}
let pending: Promise<AMapSDK> | undefined;
export function loadAmapSdk(): Promise<AMapSDK> {
  const win = window as unknown as {
    AMap?: AMapSDK;
    _AMapSecurityConfig: unknown;
  };
  if (win.AMap) return Promise.resolve(win.AMap);
  if (pending) return pending;
  pending = new Promise<AMapSDK>((resolve, reject) => {
    const controller = new AbortController();
    let script: HTMLScriptElement | undefined;
    let settled = false;
    const finish = (error?: Error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (script) {
        script.onload = null;
        script.onerror = null;
      }
      if (error) {
        controller.abort();
        script?.remove();
        pending = undefined;
        reject(error);
      } else resolve(win.AMap!);
    };
    const timer = setTimeout(
      () => finish(new Error("地图底图加载超时，请重试")),
      10000,
    );
    api<{ key: string; securityJsCode: string }>(
      "/geo/config",
      "GET",
      undefined,
      controller.signal,
    )
      .then((config) => {
        if (settled) return;
        win._AMapSecurityConfig = { securityJsCode: config.securityJsCode };
        script = document.createElement("script");
        script.src =
          "https://webapi.amap.com/maps?v=2.0&key=" +
          encodeURIComponent(config.key);
        script.onload = () =>
          win.AMap ? finish() : finish(new Error("地图底图初始化失败，请重试"));
        script.onerror = () => finish(new Error("地图底图加载失败，请重试"));
        document.head.appendChild(script);
      })
      .catch((e) =>
        finish(e instanceof Error ? e : new Error("地图底图加载失败")),
      );
  });
  return pending;
}
