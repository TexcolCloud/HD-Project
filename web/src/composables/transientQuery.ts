import type { RouteLocationNormalizedLoaded, Router } from "vue-router";
export function clearTransientQuery(
  route: RouteLocationNormalizedLoaded,
  router: Router,
  keys: string[],
) {
  if (!keys.some((key) => key in route.query)) return;
  const query = { ...route.query };
  for (const key of keys) delete query[key];
  return router?.replace?.({ path: route.path, query, hash: route.hash });
}
