import { Api, ApiError } from "../services/Api";

export class ReadTicket {
  epoch: number;
  revision: number;
  constructor(epoch: number, revision: number) {
    this.epoch = epoch;
    this.revision = revision;
  }
}

// Each independent query owns a scope. Changing filters or resetting a session retires older callbacks.
export class ReadScope {
  private revision: number = 0;
  begin(): ReadTicket {
    return new ReadTicket(Api.epoch, ++this.revision);
  }
  owns(ticket: ReadTicket): boolean {
    return ticket.revision === this.revision;
  }
  current(ticket: ReadTicket): boolean {
    return this.owns(ticket) && ticket.epoch === Api.epoch;
  }
  shouldReport(ticket: ReadTicket, error: Error): boolean {
    if (this.owns(ticket)) return true;
    // Current-session expiry belongs to the shell even after the initiating page has closed.
    const failure = error as ApiError;
    return failure.status === 401 && failure.sessionEpoch === Api.epoch;
  }
  invalidate(): void {
    this.revision++;
  }
}
