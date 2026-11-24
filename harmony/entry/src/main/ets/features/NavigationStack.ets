export interface PageEntry {
  page: string;
  scrollY: number;
  recordId: string;
  resume?: () => void;
}

export function rootTab(page: string): boolean {
  return ['home', 'repairs', 'profile'].includes(page);
}

export function requiresLogoutOnBack(page: string, mustChangePassword: boolean): boolean {
  return page === 'password' && mustChangePassword;
}

export class NavigationStack {
  private entries: PageEntry[] = [];
  reset(page: string): void {
    this.entries = [{ page: page, scrollY: 0, recordId: '' }];
  }
  enter(page: string, currentPage: string, offset: number, recordId: string = '', resume?: () => void): void {
    if (!this.entries.length || this.entries[this.entries.length - 1].page !== currentPage) this.reset(currentPage);
    if (page === currentPage) return;
    this.entries[this.entries.length - 1] = { page: currentPage, scrollY: offset, recordId: recordId, resume: resume };
    this.entries.push({ page: page, scrollY: 0, recordId: '' });
  }
  replace(page: string): void {
    if (this.entries.length) this.entries.pop();
    this.entries.push({page: page, scrollY: 0, recordId: ''});
  }
  previous(): string {
    return this.entries.length > 1 ? this.entries[this.entries.length - 2].page : '';
  }
  back(): PageEntry | null {
    if (this.entries.length < 2) return null;
    this.entries.pop();
    return this.entries[this.entries.length - 1];
  }
}

export class BackPanels {
  private handlers: Array<() => boolean> = [];
  register(handler: () => boolean): () => void {
    this.handlers.push(handler);
    return (): void => { this.handlers = this.handlers.filter((item: () => boolean): boolean => item !== handler); };
  }
  close(): boolean {
    for (let i = this.handlers.length - 1; i >= 0; i--) {
      if (this.handlers[i]()) return true;
    }
    return false;
  }
}
