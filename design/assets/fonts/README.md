# 公寓服务设计预览本地字体

本目录仅供当前离线设计预览自托管使用，不向系统安装字体。获取与子集化日期为 2026 年 11 月 7 日。字体均依据 SIL Open Font License 1.1 分发，原始许可证逐份保留。

## 字体文件

| 文件 | 原始字体 | 用途 | CSS 建议 |
| --- | --- | --- | --- |
| `dorm-plex.woff2` | IBM Plex Sans SC Regular | 正文、表单、列表和说明 | `font-family: 'Dorm Plex'`，400 |
| `dorm-plex-semibold.woff2` | IBM Plex Sans SC SemiBold | 表头、按钮、重要标签 | 与正文同一 CSS family，600 |
| `dorm-serif.woff2` | Noto Serif SC，固定600字重 | 当前暖白编辑式方案的中文标题与品牌字 | `font-family: 'Dorm Serif'`，600，normal |
| `dorm-sign.woff2` | Smiley Sans Oblique 2.0.1 | 旧版导视试稿的展示字，保留供历史方案参考 | `font-family: 'Dorm Sign'`，400；字形本身已有倾斜，不额外斜体 |
| `dorm-chivo.woff2` | Chivo 可变字体 | 门牌数字、统计数值、日期和编号 | `font-family: 'Dorm Chivo'`，100–900 可变字重 |

得意黑的上游明确不建议用于正文和手机界面。本项目仅提取其少量展示字的表达；移动正文、输入框、表单标签、状态和可访问性文本始终使用 IBM Plex 子集。

## 子集覆盖与内部名称

正文 Regular 包含 GB2312 可解码字符、项目当前 HTML/CSS/JavaScript/设计文档中的中文、ASCII 与常用中文标点。SemiBold 和展示字包含当前项目扫描得到的中文、ASCII、中文标点和业务补充词。这样保留一般录入时的常用字，避免只覆盖几条演示文案。Chivo 保留基本拉丁、扩展拉丁和可用数字符号，不承担中文排版。

对应字符清单见 `subset-characters.txt`，覆盖统计、实际字重、可变轴与 SHA-256 见 `font-manifest.json`。新增罕见姓名、繁体或其他语言后，应扩大相应子集；未覆盖字符由 CSS 后备字体补足。

五个 WOFF2 合计 1,824,112 字节，其中新加入的衬线标题字体227,360字节；正文 Regular 与 SemiBold 合计 1,423,824 字节。Regular 覆盖7,543个Unicode码位，SemiBold覆盖1,036个。衬线字体在当前项目文本与业务补充词扫描中覆盖1,131个所需字符，无缺字；它只承担展示标题，后续新增的生僻字由CSS中的系统衬线后备字体补足。旧得意黑的 `≤`、`≥` 不在源字体中；Chivo仅用于拉丁与数字，不能单独作为中文后备字体。

IBM 和 Smiley 的许可证包含保留字体名。由于子集化属于修改，生成文件内部 family 分别改为 `Residence UI`、`Residence Sign`、`Residence Numeral`，不冒充原始完整字体；版权与许可记录保留。`@font-face` 的 `Dorm Plex`、`Dorm Sign`、`Dorm Chivo` 是项目内部 CSS 别名。

暖白编辑式修订新增的 Noto Serif SC 先提取所需字符，再从可变源实例化为 600 字重并压缩为 WOFF2，内部 family 改为 `Residence Editorial`，CSS别名为 `Dorm Serif`。衬线字符清单见 `serif-subset-characters.txt`；它并非全量中文字体，也不再包含可变字体轴。

## 官方来源与许可证

- IBM Plex Sans SC： [IBM 官方字体包](https://github.com/IBM/plex/tree/master/packages/plex-sans-sc)，使用其中 `fonts/complete/woff2/hinted/IBMPlexSansSC-Regular.woff2` 与 `IBMPlexSansSC-SemiBold.woff2`。许可原文见 `OFL-IBM-Plex.txt`。
- Smiley Sans： [官方仓库](https://github.com/atelier-anchor/smiley-sans)，从 [v2.0.1 发布包](https://github.com/atelier-anchor/smiley-sans/releases/tag/v2.0.1) 的 `SmileySans-Oblique.ttf` 提取。许可原文见 `OFL-Smiley-Sans.txt`。
- Chivo： [Google Fonts 官方字体目录](https://github.com/google/fonts/tree/main/ofl/chivo)，源文件为 `Chivo[wght].ttf`。许可原文见 `OFL-Chivo.txt`。
- Noto Serif SC：[Google Fonts官方目录](https://github.com/google/fonts/tree/main/ofl/notoserifsc)，源文件为 `NotoSerifSC[wght].ttf`，Google Fonts元数据记录其上游为[notofonts/noto-cjk](https://github.com/notofonts/noto-cjk)。许可原文见 `OFL-Noto-Serif-SC.txt`。按OFL1.1保留版权及许可，采用独立内部名称发布项目子集。

使用 fontTools 4.68.0 与 Brotli 子集化并生成 WOFF2。工具只装在任务临时目录，项目无需 Python 包或 npm 依赖。未运行字体仓库的构建或安装脚本。

## CSS 接入示例

```css
@font-face {
  font-family: 'Dorm Plex';
  src: url('assets/fonts/dorm-plex.woff2') format('woff2');
  font-weight: 400;
  font-style: normal;
  font-display: swap;
}
@font-face {
  font-family: 'Dorm Plex';
  src: url('assets/fonts/dorm-plex-semibold.woff2') format('woff2');
  font-weight: 600;
  font-style: normal;
  font-display: swap;
}
@font-face {
  font-family: 'Dorm Serif';
  src: url('assets/fonts/dorm-serif.woff2') format('woff2');
  font-weight: 600;
  font-style: normal;
  font-display: swap;
}
@font-face {
  font-family: 'Dorm Sign';
  src: url('assets/fonts/dorm-sign.woff2') format('woff2');
  font-weight: 400;
  font-style: normal;
  font-display: swap;
}
@font-face {
  font-family: 'Dorm Chivo';
  src: url('assets/fonts/dorm-chivo.woff2') format('woff2');
  font-weight: 100 900;
  font-style: normal;
  font-display: swap;
}
```
