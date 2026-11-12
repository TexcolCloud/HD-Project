import { Api } from '../../services/Api';
import { ServiceMessage, ServiceMessagePage, ServiceMessageSummary } from '../../models/ApiModels';
import { AppVisibility } from '../dispatch/AppVisibility';
import { ForegroundPoll } from '../dispatch/ForegroundPoll';

interface InboxSnapshot { summary: ServiceMessageSummary; page: ServiceMessagePage; }

// Foreground delivery bridge; the server inbox remains the durable source of truth.
export class InboxPoller {
  private poll: ForegroundPoll<InboxSnapshot> | null = null;
  private unsubscribe: () => void = () => {};
  private highestId: string = '';
  private initialized: boolean = false;
  stop(): void {
    this.poll?.stop(); this.poll = null;
    this.unsubscribe(); this.unsubscribe = (): void => {};
    this.highestId = ''; this.initialized = false;
  }
  start(onCount: (count: number) => void, onNew: () => Promise<void>, onError: (error: Error) => void): void {
    this.stop();
    this.poll = new ForegroundPoll<InboxSnapshot>(async (): Promise<InboxSnapshot> => {
      const summary = await Api.request<ServiceMessageSummary>('/messages/summary');
      const page = await Api.request<ServiceMessagePage>('/messages?unreadOnly=true&pageSize=20');
      return {summary: summary, page: page};
    }, (snapshot: InboxSnapshot): void => {
      onCount(snapshot.summary.unreadCount);
      let fresh: boolean = false;
      for (const item of snapshot.page.items) {
        if (item.id.length > this.highestId.length || (item.id.length === this.highestId.length && item.id > this.highestId)) {
          this.highestId = item.id; fresh = true;
        }
      }
      if (this.initialized && fresh) onNew().catch(onError);
      this.initialized = true;
    }, onError);
    this.unsubscribe = AppVisibility.subscribe((foreground: boolean): void => { this.poll?.setForeground(foreground); });
    this.poll.setSession(Api.token);
  }
}
