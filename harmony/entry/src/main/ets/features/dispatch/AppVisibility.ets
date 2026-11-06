// UIAbility is the single source of foreground/background truth for the login session.
export class AppVisibility {
  private static foreground: boolean = true;
  private static listeners: Array<(foreground: boolean) => void> = [];
  static setForeground(foreground: boolean): void {
    if (foreground === AppVisibility.foreground) return;
    AppVisibility.foreground = foreground;
    for (const listener of AppVisibility.listeners.slice()) listener(foreground);
  }
  static subscribe(listener: (foreground: boolean) => void): () => void {
    AppVisibility.listeners.push(listener); listener(AppVisibility.foreground);
    return (): void => {
      AppVisibility.listeners = AppVisibility.listeners.filter((item: (foreground: boolean) => void): boolean => item !== listener);
    };
  }
}
