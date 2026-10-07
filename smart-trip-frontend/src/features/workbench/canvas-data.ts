import type { Day, RouteData, RouteLeg, Weather } from "../types";
export function coordinate(value: unknown): number[] | undefined {
  if (typeof value !== "string") return;
  const parts = value.split(",");
  if (parts.length !== 2 || parts.some((p) => !p.trim())) return;
  const [lng, lat] = parts.map(Number);
  if (
    !Number.isFinite(lng) ||
    !Number.isFinite(lat) ||
    Math.abs(lng!) > 180 ||
    Math.abs(lat!) > 90
  )
    return;
  return [lng!, lat!];
}
export function canvasPoints(day: Day, route?: RouteData) {
  return day.items.flatMap((item, index) => {
    if (item.id == null) return [];
    const point = route?.points.find((p) => p.itemId === item.id);
    const position = point
      ? point.resolved
        ? coordinate(point.location)
        : undefined
      : item.poiId &&
          item.coordinateSystem === "GCJ02" &&
          item.longitude != null &&
          item.latitude != null
        ? coordinate(`${item.longitude},${item.latitude}`)
        : undefined;
    return position ? [{ item, index, position }] : [];
  });
}
export function routePaths(leg: RouteLeg, known: Set<number>) {
  if (!leg.available || !known.has(leg.from) || !known.has(leg.to)) return [];
  return (leg.polylines || []).flatMap((line) => {
    const points = line.split(";").map(coordinate);
    return points.length >= 2 && points.every(Boolean)
      ? [points as number[][]]
      : [];
  });
}
export function measured(value: unknown): number | undefined {
  if (
    (typeof value !== "number" && typeof value !== "string") ||
    String(value).trim() === ""
  )
    return;
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : undefined;
}
export function usableWeather(weather: Weather | undefined, date?: string) {
  return Boolean(
    weather?.available &&
      date &&
      weather.date === date &&
      weather.city &&
      weather.dayWeather &&
      weather.low != null &&
      weather.high != null &&
      String(weather.low).trim() &&
      String(weather.high).trim() &&
      Number.isFinite(Number(weather.low)) &&
      Number.isFinite(Number(weather.high)),
  );
}
