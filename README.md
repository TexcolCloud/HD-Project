# 学生公寓综合服务管理平台

本轮已完成[全项目扫描与后端重构设计](docs/11-全项目扫描与后端重构设计.md)、[鸿蒙设备能力需求](docs/12-鸿蒙设备能力需求与方案.md)和[P3C质量门禁方案](docs/13-P3C规范与重构质量门禁.md)。这些是待实施设计；当前运行基线仍为下述openGauss版本，不代表已接入定位／传感器或完成重构。

「寓见 DormLink」包含Java后端、Vue宿管管理端和原生ArkTS/ArkUI鸿蒙应用。学生图文报修并跟进工单，宿管办理入住、报修、访客和卫生；新增多宿管动态路线与只读AI研判／值班简报。三端共用数据库、权限与契约，沿用已批准R3暖白／暖黑设计。

Web 入口：[http://127.0.0.1:5173](http://127.0.0.1:5173)。启动后端与 Web 后打开，使用宿管账号登录。API 为 [http://127.0.0.1:8080/api/v1](http://127.0.0.1:8080/api/v1)，健康检查为 [/health](http://127.0.0.1:8080/api/v1/health)。

## 本机启动

在项目根目录使用 PowerShell。后端启动脚本默认连接VMware／openEuler上的openGauss；Java、Node与DevEco仍在开发机运行。数据库替换的实际状态及验证边界见[openGauss迁移与验收](docs/10-openGauss迁移与验收.md)，工具版本见[环境说明](docs/05-实施路线与环境.md)。

先启动openEuler虚拟机及其中的既有openGauss容器，将[配置模板](deploy/opengauss.env.example)复制为被忽略的 `deploy/.env.opengauss` 并填写本机数据库连接。目标应为项目独立的PG兼容数据库；应用凭据只留本机。执行下列脚本仅验证TCP端口可达，SQL登录须在后端健康检查中确认。

```powershell
.\scripts\start-database.ps1
```

分别在两个终端启动后端与 Web；两条命令都需要保持运行：

```powershell
# 终端一：打包、测试，再以openGauss配置启动API
.\scripts\run-backend.ps1
```

已经成功打包、只需重启时可用 `-SkipBuild`；脚本会复制到独立运行JAR，避免Windows锁住target产物。`-BuildOnly`只构建，不要求数据库环境文件。openGauss使用独立DDL及V2脚本并校验已应用版本；不要改写历史迁移或清库。

旧MySQL容器、命名卷及 `deploy/.env` 保留为迁移源；需要显式使用旧库时运行 `start-database.ps1 -Database mysql` 和 `run-backend.ps1 -Database mysql`。旧数据迁移须先停止旧API写入，再按[迁移说明](docs/10-openGauss迁移与验收.md)执行工具；目标已有业务行时工具会拒绝覆盖。

```powershell
# 终端二：首次按锁文件安装依赖，再启动 5173
.\scripts\run-web.ps1
```

在 DevEco 启动现有 Pura 90 模拟器，然后运行：

```powershell
# 生成共享契约模型、构建、安装并启动鸿蒙应用
.\scripts\run-harmony.ps1

# 仅构建时使用
.\scripts\run-harmony.ps1 -BuildOnly
```

鸿蒙脚本默认目标为 `127.0.0.1:5555`，通过 hdc 把设备 8080 转发到开发机。其他已连接目标使用 `-Target '设备标识'`。App 的「服务连接设置」可修改 API 地址，默认 `http://127.0.0.1:8080/api/v1`。当前 HAP 位于 [entry-default-unsigned.hap](harmony/entry/build/default/outputs/default/entry-default-unsigned.hap)，Bundle 为 `com.dormlink.residence`，Ability 为 `EntryAbility`；已在本机模拟器安装，真实手机及发布签名另行配置。

## 课程演示账号

| 用户名 | 身份与初始范围 |
| --- | --- |
| `manager1` | 宿管，A／B栋 |
| `manager2` | 宿管，仅B栋，用于越权检查 |
| `student1` | 学生，初始入住A栋302 |
| `student2` | 学生，初始入住B栋405 |
| `student3` | 学生，未入住 |

本机课程演示密码统一为 `Demo@123456`，不得用于对外部署。种子只初始化空库，业务操作与验收数据会持久化，重复启动不会恢复初始入住状态。新建学生的开发账号为学号，使用同一演示初始密码。

## 动态多宿管路线

宿管Web进入 `/dispatch`，鸿蒙从「本次路线」进入；人员数由有效会话心跳按账号去重，不能手填人数。本人可维护接单意愿、授权范围内位置与可用截止时间；新工单或人员变化会重排可行剩余路线，保留在途／现场前缀和既有办理责任。没有可行方案时展示待安排原因。

路线推荐与实际认领分开，出发／到达／受阻／恢复也不代替维修开始、完工和学生确认。显式跨人交接仅在Web提供，限原办理人尚未开始且未在途／到场的已接单工单。模块、估算边界及本轮终验见[动态调度交付与验收](docs/09-动态调度交付与验收.md)。

## AI 助手

后端已接入 DeepSeek 官方接口适配，默认模型 ID 为 `deepseek-flash`（V4.1 Flash）。openGauss启动方式在 `deploy/.env.opengauss` 中配置 `AI_API_KEY`，可按[配置模板](deploy/opengauss.env.example)调整 `AI_BASE_URL`、`AI_MODEL` 与 `AI_DAILY_LIMIT`，然后重启后端；保留原有数据库配置，密钥只留本机。显式MySQL方式仍读取 `deploy/.env`。

学生助手产生可采纳的填写建议；宿管可主动生成单单研判和文字值班简报，显示服务端事实及授权工单引用。二者共用HTTP、配额、审计和幂等账本，重放相同请求不重复外部调用；AI没有派单、预约或状态写权限。当前真实密钥为空，官方调用未验证；本地HTTP fixture只证明协议、权限／脱敏和缓存行为，不能当作真实模型成功。

## 验证与文档

2026年11月11日本轮openGauss替换已完成：迁移快照22表／567行、16序列与原附件保留并核对；后端47项通过（36项真实openGauss、3项H2 JDBC／DDL、8项纯算法），Web12项、原生18项及构建通过；真实基础HTTP10组、调度HTTP7组、IAB7组与DevEco模拟器6组通过。原生图文新建→Web带图处理→原生确认工单21已完成，运行JAR与已测target一致，旧MySQL源库完整保留。见[本轮汇总](output/acceptance/opengauss-delivery-summary.json)、[运行证明](output/acceptance/opengauss-runtime-results.json)及[迁移与验收](docs/10-openGauss迁移与验收.md)。567是导入快照，后续验收新增记录不改变源库。

2026年11月7日的MySQL历史基线及其测试数量保留在[09](docs/09-动态调度交付与验收.md)和[当时汇总](output/acceptance/dispatch-delivery-summary.json)，不冒充本轮openGauss验证。

既有学生图文报修→Web带图处理→原生确认闭环、宿管现场访客与评分、AI未配置降级均有历史实测；这些结果不直接替代新增调度模块终验。原生卫生图片／复查、真实模型、真实手机及生产环境仍不在已通过范围。

| 入口 | 用途 |
| --- | --- |
| [后端说明](backend/README.md)、[Web说明](web/README.md)、[鸿蒙说明](harmony/README.md) | 各端运行、构建与测试 |
| [共享接口契约](contracts/API.md)、[OpenAPI schema](contracts/openapi.json) | 当前 API 与生成客户端类型的唯一契约 |
| [需求与验收](docs/01-需求与验收.md)、[领域术语](CONTEXT.md) | 功能、权限、状态机和验收标准 |
| [架构与数据](docs/02-架构与数据设计.md)、[接口与AI方案](docs/03-接口与AI方案.md) | 方案依据；实际字段以共享契约和数据库结构为准 |
| [界面规范](docs/04-界面与设计规范.md)、[环境说明](docs/05-实施路线与环境.md) | 已批准视觉与本机工具链 |
| [开发与验收](docs/07-开发与验收.md) | 复现命令、实际证据及未验证边界 |
| [多宿管动态调度方案](docs/08-多宿管动态调度方案.md) | 已实现并完成本轮验收的设计依据：人员去重、分担、动态插入、冻结及交接 |
| [动态调度交付与验收](docs/09-动态调度交付与验收.md) | 11月7日MySQL基线的模块维护、V2升级与跨端验收历史 |
| [openGauss迁移与验收](docs/10-openGauss迁移与验收.md) | openEuler数据库配置、数据保留迁移及本轮真实验收状态 |
| [历史设计预览](design/index.html)、[设计验证记录](docs/06-设计验证记录.md) | 设计阶段的静态原型和视觉对照，不连接当前业务数据库 |

当前交付为单校、本机开发与模拟器环境；尚无生产部署、真实手机验收或目标数据量性能测量记录。
