export interface PageEntry {
  page: string;
  scrollY: number;
  recordId: string;
  resume?: () => void;
}

export interface TabLink { page: string; id: string; label: string; }
const homeTab: TabLink = {page:'home', id:'tab-home', label:'首页'};
const studentTabs: TabLink[] = [
  homeTab, {page:'repairs',id:'tab-repairs',label:'我的报修'}, {page:'profile',id:'tab-profile',label:'我的'}
];
const managerTabs: TabLink[] = [
  homeTab, {page:'route',id:'tab-route',label:'路线'}, {page:'repairs',id:'tab-repairs',label:'工单'},
  {page:'hygiene',id:'tab-field',label:'巡检访客'}
];
export function bottomTabs(manager: boolean): TabLink[] {
  return manager ? managerTabs : studentTabs;
}
export function activeTab(page: string): string {
  return ['remediations','visitors'].includes(page) ? 'hygiene' : page;
}
export function rootTab(page: string, manager: boolean = false): boolean {
  return page === 'profile' || bottomTabs(manager).some((tab: TabLink): boolean => tab.page === (manager ? activeTab(page) : page));
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
