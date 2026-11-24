import { it, expect } from "vitest";
import { inspectionChanges } from "./inspectionChanges";
it("比较服务端更正前后快照，仅列出变更并翻译字段和枚举", () => {
  expect(
    inspectionChanges(
      JSON.stringify({
        before: {
          floorScore: 15,
          round: "RECHECK",
          grade: "PASS",
          issueNote: "相同",
        },
        after: {
          floorScore: 22,
          round: "RECHECK2",
          grade: "GOOD",
          issueNote: "相同",
        },
      }),
    ),
  ).toEqual([
    { label: "地面得分", before: "15", after: "22" },
    { label: "巡检轮次", before: "整改复查", after: "第二次复查" },
    { label: "等级", before: "合格", after: "良好" },
  ]);
});
it("支持字段级对照、单值以及早期文本记录", () => {
  expect(
    inspectionChanges({
      desk_score: { before: 0, after: 23 },
      bedScore: 18,
      issueNote: null,
    }),
  ).toEqual([
    { label: "桌面得分", before: "0", after: "23" },
    { label: "床铺得分", after: "18" },
    { label: "问题说明", after: "—" },
  ]);
  expect(inspectionChanges("管理员核对后更正")).toEqual([
    { label: "变更说明", after: "管理员核对后更正" },
  ]);
});
