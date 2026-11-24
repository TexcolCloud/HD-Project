import { it, expect, vi } from "vitest";
import { find, node, renderer, text } from "../test/renderer";
import DashboardPage from "./DashboardPage.vue";

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock("../api/client", () => ({ ...api, ApiError: class extends Error {} }));
vi.mock("../components/ManagerAssist.vue", () => ({
  default: { render: () => null },
}));
vi.mock("vue", async (original) => ({
  ...(await original<typeof import("vue")>()),
  vModelSelect: {},
}));

it("工作台跨分页优先展示待接较急工单，接单后刷新", async () => {
  let accepted = false;
  const statuses: string[] = [];
  const order = (
    title: string,
    status: string,
    priority: string,
    day: number,
  ) => ({
    id: title,
    title,
    status,
    priority,
    submittedAt: new Date(Date.UTC(2026, 0, day)).toISOString(),
    allowedActions: status === "SUBMITTED" ? ["accept"] : [],
    version: 4,
  });
  api.get.mockImplementation(
    async (path: string, q: { status: string; page: number }) => {
      if (path === "/statistics/overview")
        return { occupancyRate: 0, capacity: 0, pendingConfirmation: 2 };
      if (path !== "/repairs") return { items: [], total: 0 };
      statuses.push(q.status);
      if (q.status === "SUBMITTED") {
        if (q.page === 2)
          return {
            items: [order("较急先接", "SUBMITTED", "URGENT", 1)],
            total: 2,
          };
        return {
          items: [order("一般待接", "SUBMITTED", "NORMAL", 4)],
          total: accepted ? 1 : 2,
        };
      }
      return {
        items: [
          order(
            q.status,
            q.status,
            q.status === "ACCEPTED" ? "URGENT" : "NORMAL",
            6,
          ),
        ],
        total: 1,
      };
    },
  );
  api.post.mockImplementation(async () => {
    accepted = true;
  });
  const root = node("root"),
    app = renderer.createApp(DashboardPage);
  app.component("RouterLink", { template: "<a><slot/></a>" });
  app.mount(root);
  try {
    const table = () => find(root, (n) => n.tag === "tbody")!;
    await vi.waitFor(() => expect(text(root)).toContain("较急先接"));
    expect(
      table()
        .children.filter((n) => n.tag === "tr")
        .map((row) => text(find(row, (n) => n.tag === "strong")!)),
    ).toEqual(["较急先接", "一般待接", "ACCEPTED", "PROCESSING"]);
    expect(new Set(statuses)).toEqual(
      new Set(["SUBMITTED", "ACCEPTED", "PROCESSING"]),
    );
    const accept = find(
      table(),
      (n) => n.tag === "button" && text(n).trim() === "接单",
    )!;
    await (accept.props.onClick as () => Promise<void>)();
    expect(api.post).toHaveBeenCalledWith("/repairs/较急先接/accept", {
      expectedVersion: 4,
    });
    await vi.waitFor(() => expect(text(table())).not.toContain("较急先接"));
  } finally {
    app.unmount();
  }
});
