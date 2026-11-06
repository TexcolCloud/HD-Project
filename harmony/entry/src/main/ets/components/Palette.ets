export class Palette {
  readonly canvas: string;
  readonly surface: string;
  readonly ink: string;
  readonly muted: string;
  readonly line: string;
  readonly soft: string;
  readonly accent: string;
  readonly onPrimary: string;
  readonly danger: string;
  constructor(night: boolean) {
    this.canvas = night ? '#1E211E' : '#F5F1E8'; this.surface = night ? '#272B26' : '#FFFDF9';
    this.ink = night ? '#ECE6D9' : '#242622'; this.muted = night ? '#BCBBAF' : '#62665C';
    this.line = night ? '#454A42' : '#DAD5C9'; this.soft = night ? '#33382F' : '#EDE8DF';
    this.accent = night ? '#E2A183' : '#A64B2A'; this.onPrimary = night ? '#232721' : '#FFFDF8';
    this.danger = night ? '#F1A99C' : '#A13E37';
  }
}
const DAY = new Palette(false);
const NIGHT = new Palette(true);
export function palette(night: boolean): Palette { return night ? NIGHT : DAY; }
