import { createRouter, createWebHistory } from "vue-router";
import { installRouteMotion, routeScroll } from "../features/route-motion";
const protectedPage = { requiresAuth: true };
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior: routeScroll,
  routes: [
    { path: "/", component: () => import("../features/Home.vue") },
    { path: "/login", component: () => import("../features/Login.vue") },
    { path: "/square", component: () => import("../features/Square.vue") },
    {
      path: "/my-trips",
      component: () => import("../features/MyTrips.vue"),
      meta: protectedPage,
    },
    {
      path: "/profile",
      component: () => import("../features/Profile.vue"),
      meta: protectedPage,
    },
    { path: "/authors/:id", component: () => import("../features/Author.vue") },
    {
      path: "/posts/:id",
      component: () => import("../features/PublicTrip.vue"),
    },
    {
      path: "/share/:token",
      component: () => import("../features/PublicTrip.vue"),
    },
    {
      path: "/ai-generate",
      component: () => import("../features/Generate.vue"),
      meta: protectedPage,
    },
    {
      path: "/trip/edit/:tripId",
      component: () => import("../features/Editor.vue"),
      meta: protectedPage,
    },
    ...(import.meta.env.DEV
      ? [{ path: "/__dev/rag", component: () => import("../features/RagDiagnostics.vue"), meta: protectedPage }]
      : []),
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
router.beforeEach((to) => {
  document.title = "智游伴路 · 旅行路书";
  if (to.meta.requiresAuth && !localStorage.getItem("AUTH_TOKEN"))
    return { path: "/login", query: { redirect: to.fullPath } };
});
installRouteMotion(router);
export default router;
