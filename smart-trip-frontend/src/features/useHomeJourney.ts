import { computed, onMounted, ref, watch } from "vue";
import { api } from "./api";
import { useSession } from "./session";
import { useLatestQuery } from "./useLatestQuery";
import type { Post, PostPage, Trip } from "./types";

export function useHomeJourney() {
  const session = useSession();
  const posts = ref<Post[]>([]);
  const trip = ref<Trip | null>(null);
  const recent = ref<Trip | null>(null);
  const target = ref("/my-trips");
  const feed = useLatestQuery();
  const journey = useLatestQuery();

  function loadPosts() {
    return feed.query(
      (signal) => api<PostPage>("/community/posts", "GET", undefined, signal),
      (result) => {
        posts.value = result.items.slice(0, 3);
      },
    );
  }
  function loadJourney() {
    // Drop private content immediately, including while a new session loads.
    trip.value = null;
    recent.value = null;
    target.value = "/my-trips";
    const token = session.token;
    const firstPost = posts.value[0];
    return journey.query(
      async (signal) => {
        if (token) {
          const mine = await api<Trip[]>(
            "/workbench",
            "GET",
            undefined,
            signal,
          );
          if (mine[0]?.id) {
            const detail = await api<Trip>(
              "/workbench/" + mine[0].id,
              "GET",
              undefined,
              signal,
            );
            return {
              trip: detail,
              recent: mine[0],
              target: "/trip/edit/" + mine[0].id,
            };
          }
        }
        if (firstPost) {
          const post = await api<Post>(
            "/community/posts/" + firstPost.id,
            "GET",
            undefined,
            signal,
          );
          return {
            trip: post.trip || null,
            recent: null,
            target: "/posts/" + firstPost.id,
          };
        }
        return { trip: null, recent: null, target: "/my-trips" };
      },
      (result) => {
        // Query cancellation handles superseded requests; this also protects session changes.
        if (session.token !== token) return;
        trip.value = result.trip;
        recent.value = result.recent;
        target.value = result.target;
      },
    );
  }
  watch([() => session.token, () => posts.value[0]?.id], loadJourney, {
    immediate: true,
  });
  onMounted(loadPosts);
  const days = computed(() => trip.value?.days || []);
  const stops = computed(() =>
    days.value.slice(0, 4).map((day, i) => ({
      index: i + 1,
      name: day.items?.[0]?.itemName || day.dayTheme || "安排待完善",
    })),
  );
  const day = computed(
    () => days.value.find((d) => d.items?.length) || days.value[0],
  );
  const items = computed(() => (day.value?.items || []).slice(0, 3));
  const itemCount = computed(() =>
    days.value.reduce((sum, d) => sum + (d.items?.length || 0), 0),
  );
  const duration = computed(() => {
    const all = day.value?.items || [];
    if (
      !all.length ||
      all.some((i) => !Number.isFinite(i.costTimeHours) || i.costTimeHours! < 0)
    )
      return null;
    return +all.reduce((sum, i) => sum + i.costTimeHours!, 0).toFixed(2);
  });
  return {
    session,
    posts,
    trip,
    recent,
    target,
    days,
    stops,
    day,
    items,
    itemCount,
    duration,
    feedBusy: feed.loading,
    feedError: feed.error,
    journeyBusy: journey.loading,
    journeyError: journey.error,
    loadPosts,
    loadJourney,
  };
}
