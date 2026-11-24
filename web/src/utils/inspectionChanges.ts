import { dateTime, statusNames } from "../ui";
export type ChangeRow = { label: string; before?: string; after: string };
const labels: Record<string, string> = {
  floorScore: "地面得分",
  deskScore: "桌面得分",
  bedScore: "床铺得分",
  bathroomScore: "卫生间得分",
  totalScore: "总分",
  grade: "等级",
  round: "巡检轮次",
  issueNote: "问题说明",
  remediationNote: "整改建议",
  inspectionDate: "检查日期",
  inspectedAt: "检查时刻",
  roomNo: "房号",
  buildingName: "楼栋",
  inspectorName: "检查人",
  imageIds: "现场图片",
  images: "现场图片",
  version: "版本",
  updatedAt: "更新时间",
  voidedAt: "作废时间",
};
function record(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}
function display(key: string, value: unknown): string {
  if (value === null || value === undefined || value === "") return "—";
  if (typeof value === "boolean") return value ? "是" : "否";
  if (Array.isArray(value))
    return value.map((v) => display(key, v)).join("、") || "—";
  if (record(value))
    return typeof value.originalName === "string"
      ? value.originalName
      : Object.entries(value)
          .map(([k, v]) => (labels[k] ?? k) + " " + display(k, v))
          .join("，");
  const raw = String(value);
  if (key === "grade" || key === "round") return statusNames[raw] || raw;
  if (
    ["inspectedAt", "updatedAt", "voidedAt"].includes(key) &&
    Number.isFinite(Date.parse(raw))
  )
    return dateTime(raw);
  return raw;
}
export function inspectionChanges(value: unknown): ChangeRow[] {
  let data = value;
  if (typeof data === "string") {
    try {
      data = JSON.parse(data);
    } catch {
      return [{ label: "变更说明", after: data as string }];
    }
  }
  if (!record(data)) return [{ label: "变更说明", after: display("", data) }];
  const rows: ChangeRow[] = [];
  function add(
    field: string,
    after: unknown,
    before?: unknown,
    compare = false,
  ) {
    const key = field.replace(/_([a-z])/g, (_, letter: string) =>
      letter.toUpperCase(),
    );
    if (compare && JSON.stringify(before) === JSON.stringify(after)) return;
    rows.push({
      label: labels[key] ?? key,
      after: display(key, after),
      before: compare ? display(key, before) : undefined,
    });
  }
  if (record(data.before) && record(data.after)) {
    for (const field of new Set([
      ...Object.keys(data.before),
      ...Object.keys(data.after),
    ]))
      add(field, data.after[field], data.before[field], true);
  } else
    for (const [field, change] of Object.entries(data)) {
      if (record(change) && ("before" in change || "after" in change))
        add(field, change.after, change.before, true);
      else add(field, change);
    }
  return rows;
}
