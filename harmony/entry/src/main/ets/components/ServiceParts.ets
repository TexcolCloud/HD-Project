import { palette } from './Palette';

export interface LabelArgs { text: string; night: boolean; }
export interface FieldArgs {
  label: string;
  value: string;
  change: (value: string) => void;
  night: boolean;
  page: string;
  placeholder?: string;
  multiline?: boolean;
  inputType?: InputType;
  id?: string;
  invalidPhonePaste?: () => void;
  error?: string;
}
export interface DetailArgs { label: string; value: string; night: boolean; }
export interface PrimaryArgs { label: string; click: () => void; night: boolean; page: string; busy?: boolean; uploading?: boolean; enabled?: boolean; }
export interface SecondaryArgs { label: string; click: () => void; night: boolean; busy?: boolean; weight?: number; }
export interface SectionArgs { title: string; night: boolean; action?: string; click?: () => void; }
export interface EmptyArgs { message: string; night: boolean; }

export function fieldInputId(label: string, page: string): string {
  if (label === '报修标题 *') return 'repair-title';
  if (label === '故障描述 *') return 'repair-description';
  if (label === '联系电话 *') return page === 'visitor-create' ? 'visitor-phone' : 'repair-phone';
  if (label === '访客姓名 *') return 'visitor-name';
  if (label === '来访事由 *') return 'visitor-purpose';
  if (label === '服务地址') return 'api-base-url';
  if (label === '检查日期 *') return 'hygiene-date';
  if (label === '实际检查时间 *') return 'hygiene-time';
  return 'field-' + label;
}

export function primaryInputId(page: string): string {
  if (page === 'repair-create') return 'repair-submit';
  if (page === 'visitor-create') return 'visitor-submit';
  if (page === 'hygiene-create') return 'hygiene-submit';
  if (page === 'action') return 'repair-action-submit';
  return 'primary-' + page;
}

export function repairStatusColor(status: string, night: boolean): string {
  if (['SUBMITTED', 'PENDING_CONFIRMATION'].includes(status)) return night ? '#E5BE8B' : '#87532A';
  if (['ACCEPTED', 'PROCESSING'].includes(status)) return night ? '#BDCFD4' : '#466170';
  if (status === 'COMPLETED') return night ? '#B3CCA0' : '#3A6448';
  if (status === 'REJECTED') return night ? '#F1A99C' : '#A13E37';
  return palette(night).muted;
}

// ArkUI tracks Builder state only through one directly supplied object literal.
// Keep dynamic content, theme and button state in the same reference parameter.
@Builder
export function serviceLabel($$: LabelArgs) { Text($$.text).fontSize(16).fontColor(palette($$.night).ink).fontWeight(FontWeight.Medium).width('100%') }

@Builder
export function serviceField($$: FieldArgs) {
  Column({space: 8}) {
    serviceLabel({text: $$.label, night: $$.night})
    if ($$.multiline) {
      TextArea({text: $$.value, placeholder: $$.placeholder || ''}).id(fieldInputId($$.label, $$.page)).onChange($$.change).fontSize(16).fontColor(palette($$.night).ink)
        .placeholderColor(palette($$.night).muted).backgroundColor(palette($$.night).surface).border({width: 1, color: palette($$.night).outline})
        .borderRadius(4).padding(12).height(112).maxLength(1000)
    } else {
      TextInput({text: $$.value, placeholder: $$.placeholder || ''}).id($$.id || fieldInputId($$.label, $$.page)).type($$.inputType ?? InputType.Normal)
        .inputFilter($$.inputType === InputType.PhoneNumber ? '[0-9]' : '')
        .onChange((value: string) => $$.change($$.inputType === InputType.PhoneNumber ? value.replace(/\D/g, '').slice(0, 11) : value)).fontSize(16).fontColor(palette($$.night).ink)
        .placeholderColor(palette($$.night).muted).backgroundColor(palette($$.night).surface).border({width: 1, color: palette($$.night).outline})
        .borderRadius(4).padding({left: 12, right: 12}).height(50).maxLength($$.inputType === InputType.PhoneNumber ? 11 : 150)
        .onPaste((content: string, event: PasteEvent): void => {
          const reject = $$.invalidPhonePaste;
          if (reject && !/^1[3-9][0-9]{9}$/.test(content.trim())) {
            event.preventDefault?.();
            reject();
          }
        })
    }
    if ($$.error) { Text($$.error).fontSize(14).fontColor(palette($$.night).danger).width('100%') }
  }.alignItems(HorizontalAlign.Start).width('100%').margin({bottom: 18})
}

@Builder
export function servicePrimary($$: PrimaryArgs) {
  Button({type: ButtonType.Normal}) {
    Text($$.busy ? '正在保存…' : $$.label).fontSize(16).fontWeight(FontWeight.Medium).fontColor(palette($$.night).onPrimary)
  }.id(primaryInputId($$.page)).width('100%').constraintSize({minHeight: 50}).padding({top: 8, bottom: 8, left: 12, right: 12})
    .backgroundColor(palette($$.night).ink).fontColor(palette($$.night).onPrimary).borderRadius(4)
    .enabled($$.enabled !== false && !$$.busy && !$$.uploading).onClick($$.click)
}

@Builder
export function serviceSecondary($$: SecondaryArgs) {
  Button({type: ButtonType.Normal}) { Text($$.label).fontSize(14).fontColor(palette($$.night).ink) }.layoutWeight($$.weight ?? 0).constraintSize({minHeight: 48}).padding({top: 8, bottom: 8, left: 12, right: 12}).fontColor(palette($$.night).ink).backgroundColor(palette($$.night).surface)
    .border({width: 1, color: palette($$.night).outline}).borderRadius(4).enabled(!$$.busy).onClick($$.click)
}

@Builder
export function serviceSection($$: SectionArgs) {
  Row() {
    Text($$.title).fontSize(18).fontWeight(FontWeight.Medium).fontColor(palette($$.night).ink).layoutWeight(1)
    if ($$.action) { Button({type: ButtonType.Normal}) { Text($$.action).fontSize(14).fontColor(palette($$.night).ink) }.fontColor(palette($$.night).ink).backgroundColor(Color.Transparent).constraintSize({minHeight: 48}).padding({top: 8, bottom: 8, left: 12, right: 12}).onClick(() => { if ($$.click) $$.click(); }) }
  }.width('100%').margin({top: 18, bottom: 8})
}

@Builder
export function serviceEmpty($$: EmptyArgs) {
  Text($$.message).fontSize(14).fontColor(palette($$.night).muted).lineHeight(24).width('100%').padding(24)
    .textAlign(TextAlign.Center)
}

@Builder
export function serviceDetail($$: DetailArgs) {
  Row({space: 16}) {
    Text($$.label).fontSize(14).fontColor(palette($$.night).muted).width(88)
    Text($$.value || '未填写').fontSize(14).fontColor(palette($$.night).ink).layoutWeight(1).textAlign(TextAlign.End)
  }.width('100%').padding({top: 10, bottom: 10}).border({width: {bottom: 0.5}, color: palette($$.night).line})
}
