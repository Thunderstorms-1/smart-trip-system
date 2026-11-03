export const motion = {
  fast: 160,
  base: 280,
  slow: 360,
  distance: 8,
  ease: "cubic-bezier(.22, 1, .36, 1)",
} as const;

export function prefersReducedMotion(): boolean {
  return (
    typeof window !== "undefined" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches
  );
}
