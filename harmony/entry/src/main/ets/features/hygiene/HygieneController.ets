import { util } from "@kit.ArkTS";
import { Api, UploadedPhoto } from "../../services/Api";
import { SubmissionKeys } from "../../services/SubmissionKeys";
import {
  Room,
  RoomPage,
  Hygiene,
  HygienePage,
  HygieneRequest,
} from "../../models/ApiModels";
import {
  today,
  beijingClock,
  inspectionTimestamp,
} from "../../services/Labels";
import {
  PhotoDraftController,
  PhotoSnapshot,
} from "../photos/PhotoDraftController";
import { WorkspaceHost } from "../WorkspaceHost";
import { ReadScope } from "../ReadScope";

@Observed
export class HygieneState {
  busy: boolean = false;
  uploading: boolean = false;
  loading: boolean = false;
  rooms: Room[] = [];
  roomSearch: string = "";
  hygiene: Hygiene[] = [];
  hygieneTotal: number = 0;
  hygienePage: number = 1;
  inspectionPhotos: UploadedPhoto[] = [];
  failedInspectionPhotos: string[] = [];
  inspectionRoomId: string = "";
  inspectionDate: string = today();
  inspectionTime: string = beijingClock();
  inspectionRound: string = "ROUTINE";
  inspectionRelated: string = "";
  floorScore: string = "23";
  deskScore: string = "23";
  bedScore: string = "23";
  bathroomScore: string = "23";
  issueNote: string = "";
  remediationNote: string = "";
}

export class HygieneController {
  state: HygieneState = new HygieneState();
  manager: boolean = false;
  private host: WorkspaceHost;
  private photoDrafts: PhotoDraftController;
  private inspectionDraftStarted: boolean = false;
  private listRead: ReadScope = new ReadScope();
  private roomRead: ReadScope = new ReadScope();
  private submissionKeys: SubmissionKeys = new SubmissionKeys((): string =>
    util.generateRandomUUID(),
  );
  constructor(host: WorkspaceHost, photos: PhotoDraftController) {
    this.host = host;
    this.photoDrafts = photos;
  }
  suspendReads(): void {
    this.listRead.invalidate();
    this.roomRead.invalidate();
    this.state.loading = false;
  }
  reset(): void {
    this.listRead.invalidate();
    this.roomRead.invalidate();
    this.inspectionDraftStarted = false;
    this.submissionKeys.clear();
    this.state = new HygieneState();
  }
  private requestKey(body: Object, scope: string): string {
    return this.submissionKeys.reserve(scope, JSON.stringify(body));
  }
  photoChanged(snapshot: PhotoSnapshot): void {
    this.state.uploading = snapshot.busy;
    if (snapshot.usage === "HYGIENE") {
      this.state.inspectionPhotos = snapshot.draft.uploaded;
      this.state.failedInspectionPhotos = snapshot.draft.failed;
    }
  }
  beginDraft(): void {
    if (!this.inspectionDraftStarted) {
      this.state.inspectionDate = today();
      this.state.inspectionTime = beijingClock();
      this.inspectionDraftStarted = true;
    }
  }
  async loadRooms(): Promise<void> {
    const ticket = this.roomRead.begin();
    try {
      const data = await Api.request<RoomPage>(
        "/rooms?pageSize=100&q=" + encodeURIComponent(this.state.roomSearch),
      );
      if (!this.roomRead.current(ticket)) return;
      this.state.rooms = data.items;
      if (
        !this.state.rooms.some(
          (room: Room): boolean => room.id === this.state.inspectionRoomId,
        )
      )
        this.state.inspectionRoomId = this.state.rooms.length
          ? this.state.rooms[0].id
          : "";
    } catch (error) {
      if (this.roomRead.shouldReport(ticket, error as Error))
        this.host.fail(error as Error);
    }
  }
  async loadHygiene(): Promise<void> {
    const ticket = this.listRead.begin();
    this.state.loading = true;
    try {
      const data = await Api.request<HygienePage>(
        (this.manager ? "/hygiene-inspections" : "/me/hygiene-inspections") +
          "?page=" +
          this.state.hygienePage +
          "&pageSize=10",
      );
      if (!this.listRead.current(ticket)) return;
      this.state.hygiene = data.items;
      this.state.hygieneTotal = data.total;
    } catch (error) {
      if (this.listRead.shouldReport(ticket, error as Error))
        this.host.fail(error as Error);
    } finally {
      if (this.listRead.current(ticket)) this.state.loading = false;
    }
  }
  roomChoice(): string {
    const selected = this.state.rooms.find(
      (r: Room): boolean => r.id === this.state.inspectionRoomId,
    );
    return selected
      ? selected.buildingName + " " + selected.roomNo
      : "请选择检查宿舍";
  }

  totalScore(): number {
    return (
      Number(this.state.floorScore) +
      Number(this.state.deskScore) +
      Number(this.state.bedScore) +
      Number(this.state.bathroomScore)
    );
  }

  scoreGrade(): string {
    const value = this.totalScore();
    return value >= 90
      ? "优秀"
      : value >= 80
        ? "良好"
        : value >= 60
          ? "合格"
          : "待整改";
  }

  recheck(item: Hygiene): void {
    if (this.state.busy) return;
    // A recheck is a new inspection draft; it never inherits another room's photo work.
    this.photoDrafts.clear("HYGIENE");
    this.state.inspectionPhotos = [];
    this.state.failedInspectionPhotos = [];
    this.state.inspectionRoomId = item.roomId;
    this.state.inspectionRelated = item.id;
    this.state.inspectionDate = today();
    this.state.inspectionTime = beijingClock();
    this.inspectionDraftStarted = true;
    const previous = item.round.startsWith("RECHECK")
      ? Number(item.round.replace("RECHECK", "") || "1")
      : 0;
    this.state.inspectionRound = previous
      ? "RECHECK" + (previous + 1)
      : "RECHECK";
    this.state.issueNote = "";
    this.state.remediationNote = "";
    this.host.navigate("hygiene-create");
  }

  async submitHygiene(): Promise<void> {
    const epoch = Api.epoch,
      state = this.state;
    if (this.state.busy || this.state.uploading) return;
    this.host.setError("");
    if (this.state.failedInspectionPhotos.length) {
      this.host.setError("请重试或移除上传失败的图片");
      return;
    }
    const scoreValues = [
      this.state.floorScore,
      this.state.deskScore,
      this.state.bedScore,
      this.state.bathroomScore,
    ];
    if (scoreValues.some((s: string): boolean => !s.trim())) {
      this.host.setError("四项评分均需填写，最低可填0分");
      return;
    }
    const scores = scoreValues.map((s: string): number => Number(s));
    if (
      scores.some(
        (s: number): boolean => !Number.isInteger(s) || s < 0 || s > 25,
      ) ||
      !this.state.inspectionRoomId
    ) {
      this.host.setError("请选择宿舍，并将每项评分填写为0至25的整数");
      return;
    }
    if (this.totalScore() < 60 && !this.state.issueNote.trim()) {
      this.host.setError("低于60分时，请说明检查发现的问题");
      return;
    }
    let inspectedAt: string = "";
    try {
      inspectedAt = inspectionTimestamp(
        this.state.inspectionDate,
        this.state.inspectionTime,
      );
    } catch (error) {
      this.host.fail(error as Error);
      return;
    }
    const request: HygieneRequest = {
      roomId: this.state.inspectionRoomId,
      inspectionDate: this.state.inspectionDate.trim(),
      inspectedAt: inspectedAt,
      round: this.state.inspectionRound,
      floorScore: scores[0],
      deskScore: scores[1],
      bedScore: scores[2],
      bathroomScore: scores[3],
      issueNote: this.state.issueNote.trim(),
      remediationNote: this.state.remediationNote.trim(),
      imageIds: this.state.inspectionPhotos.map(
        (p: UploadedPhoto): string => p.asset.id,
      ),
    };
    if (this.state.inspectionRelated)
      request.relatedInspectionId = this.state.inspectionRelated;
    this.state.busy = true;
    try {
      const saved = await Api.post<Hygiene>(
        "/hygiene-inspections",
        request,
        this.requestKey(request, "hygiene"),
      );
      Api.assertCurrent(epoch);
      if (state !== this.state) return;
      this.submissionKeys.complete("hygiene");
      this.inspectionDraftStarted = false;
      this.photoDrafts.clear("HYGIENE");
      this.state.inspectionPhotos = [];
      this.state.inspectionRelated = "";
      this.state.inspectionRound = "ROUTINE";
      this.host.flash("卫生评分" + saved.totalScore + "分已保存");
      this.state.hygienePage = 1;
      this.host.navigate("hygiene");
    } catch (error) {
      if (state === this.state) this.host.fail(error as Error);
    } finally {
      if (state === this.state && epoch === Api.epoch) this.state.busy = false;
    }
  }
}
