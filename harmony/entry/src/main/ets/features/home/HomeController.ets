import { Api } from "../../services/Api";
import {
  pendingRepairPaths,
  newestUnique,
} from "../../services/PendingRepairs";
import {
  User,
  Room,
  Residence,
  ResidencePage,
  Overview,
  Repair,
  RepairPage,
  Hygiene,
  HygienePage,
} from "../../models/ApiModels";
import { ReadScope } from "../ReadScope";
import { WorkspaceHost } from "../WorkspaceHost";

@Observed
export class HomeState {
  loading: boolean = false;
  room: Room | null = null;
  residences: Residence[] = [];
  overview: Overview | null = null;
  repairs: Repair[] = [];
  hygiene: Hygiene[] = [];
}

export class HomeController {
  state: HomeState = new HomeState();
  manager: boolean = false;
  private host: WorkspaceHost;
  private roomRead: ReadScope = new ReadScope();
  private homeRead: ReadScope = new ReadScope();
  private residenceRead: ReadScope = new ReadScope();
  constructor(host: WorkspaceHost) {
    this.host = host;
  }
  suspendReads(): void {
    this.homeRead.invalidate();
    this.residenceRead.invalidate();
    this.state.loading = false;
  }
  initialize(user: User): void {
    this.manager = user.role === "MANAGER";
    this.state.room = user.currentRoom;
  }
  reset(): void {
    this.roomRead.invalidate();
    this.homeRead.invalidate();
    this.residenceRead.invalidate();
    this.state = new HomeState();
  }
  async loadHome(): Promise<void> {
    const ticket = this.homeRead.begin();
    this.state.loading = true;
    try {
      if (this.manager) {
        const overview = await Api.request<Overview>("/statistics/overview");
        if (!this.homeRead.current(ticket)) return;
        this.state.overview = overview;
        const pages = await Promise.all(
          pendingRepairPaths().map(
            (path: string): Promise<RepairPage> =>
              Api.request<RepairPage>(path),
          ),
        );
        if (!this.homeRead.current(ticket)) return;
        this.state.repairs = newestUnique<Repair>(
          pages.map((page: RepairPage): Repair[] => page.items),
          (order: Repair): string => order.id,
          (order: Repair): string => order.submittedAt,
          3,
        );
      } else {
        // Current residence belongs to the session, not to the homepage's preview lifecycle.
        const residence = this.roomRead.begin();
        const room = await Api.request<Room | null>("/me/room");
        if (this.roomRead.current(residence)) this.state.room = room;
        if (!this.homeRead.current(ticket)) return;
        const repairs = await Api.request<RepairPage>("/repairs?pageSize=5");
        if (!this.homeRead.current(ticket)) return;
        this.state.repairs = repairs.items;
        const hygiene = await Api.request<HygienePage>(
          "/me/hygiene-inspections?pageSize=3",
        );
        if (this.homeRead.current(ticket)) this.state.hygiene = hygiene.items;
      }
    } catch (error) {
      if (this.homeRead.shouldReport(ticket, error as Error))
        this.host.fail(error as Error);
    } finally {
      if (this.homeRead.current(ticket)) this.state.loading = false;
    }
  }
  async loadResidences(): Promise<void> {
    const ticket = this.residenceRead.begin();
    try {
      const page = await Api.request<ResidencePage>(
        "/me/residences?pageSize=100",
      );
      if (this.residenceRead.current(ticket))
        this.state.residences = page.items;
    } catch (error) {
      if (this.residenceRead.shouldReport(ticket, error as Error))
        this.host.fail(error as Error);
    }
  }
}
