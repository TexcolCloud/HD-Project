# 鸿蒙应用模块与接口

2026-11-25 按 `main @ 522344f` 核对。`pages/Index.ets` 管理认证、主题与系统栏、导航和页面组合。各业务 Workspace 渲染页面，控制器管理请求与状态。设备采集、消息轮询、草稿和图片资源各有独立生命周期。双角色导航、R3 日夜主题与 Pura 90 模拟器验收见[界面与交付版本](../docs/18-界面收敛与交付版本.md)，业务流程见[联合验收](../docs/16-服务闭环与联合验收.md)。

## 模块与接口

| 模块 | 职责 | 对外入口 |
| --- | --- | --- |
| `features/NavigationStack.ets` | 按角色判定底栏可见性，维护多级页面栈。返回时恢复原滚动位置和业务上下文，页内面板可先处理返回 | `rootTab/bottomTabs/activeTab`、`NavigationStack.enter/back`、`BackPanels.close` |
| `features/service/ServiceCenterController.ets` | 服务消息读取、自动／本页批量已读、关联业务跳转，以及卫生整改的指派、反馈和关联复查验收 | `loadMessages/openMessage/readPage/openRemediation/loadRechecks/review` |
| `features/service/ServiceCenterWorkspace.ets` | 展示收件箱、整改列表与详情，以及按角色开放的反馈、调整与验收表单 | `page/state/manager/controller/onRecheck` |
| `features/service/InboxPoller.ets` | 管理前台未读数轮询，以会话代次及已读修订号丢弃迟到结果，防止角标回滚 | `start/markRead/stop` |
| `features/account/AccountWorkspace.ets` | 预置账号激活、手机号绑定与换绑、短信找回密码、宿管恢复码的签发与找回校验 | `screen`（`activate/account/recover`）、`onComplete/onExit` |
| `services/LocalDrafts.ets` | 按服务地址与账号隔离报修、访客、巡检的限期文字草稿，未决提交保留对应幂等键 | `save/read/clear`、`reserveKey/completeKey` |
| `services/ImageSource.ets`、`components/SecureImage.ets` | 页面内优先请求缩略图，打开半模态时按需请求独立高清图，通过 ResourceSlot 释放解码资源 | `embeddedImage/previewImage`、`url/thumbnailUrl/night` |
| `components/Palette.ets` | 提供 R3 暖白／暖黑语义色，控件边界 `outline` 对表面的对比度 ≥3.0:1，`line` 用于弱分隔 | `palette(night)` |
| `features/dispatch/DispatchController.ets` | 会话级调度状态、受保护请求、board缓存、版本门控、设置与执行动作 | `startSession/stopSession`、`subscribe`、`refresh/replan/saveSettings/taskAction/openRepair` |
| `features/dispatch/ForegroundPoll.ets` | 前台循环：30秒登录心跳与5秒可见路线只读刷新共用生命周期、单飞与代际失效 | `setSession/setForeground/stop`；可注入scheduler验证 |
| `features/dispatch/RequestFlight.ets` | 手动、轮询、心跳后GET按登录epoch共用一个在途请求 | `run/reset` |
| `features/dispatch/AppVisibility.ets` | UIAbility前后台事件唯一来源 | `setForeground/subscribe` |
| `features/dispatch/RouteRevision.ets` | 拒绝迟到旧计划；允许同版本刷新明细 | `accept/value/clear` |
| `features/dispatch/RoutePage.ets` | 订阅调度快照、展示本次路线、变化提示与待安排原因 | `controller/night/onOpenRepair/onFinish/onCalibrate` |
| `features/dispatch/RouteTaskCard.ets` | 当前位置／下一站／剩余／暂缓站点及明确执行动作 | `task`与`onAction/onOpen` |
| `features/dispatch/RouteSettings.ets` | 接单意愿、负责范围内当前位置、本次有效可用截止时间 | `manager/buildings`与`onSave/onCancel` |
| `features/visits/VisitWindowFields.ets` | 学生可上门范围的独立输入及不可变草稿 | `draft/night/onChange` |
| `features/visits/VisitArrangement.ets` | 原工单中的访问范围修改、宿管确认／时长／等待原因 | `repair/manager/night/onSaved` |
| `features/visitors/VisitorController.ets` | 授权楼栋筛选、到访与详情读取、确认离访和版本保护 | `loadBuildings/loadVisitors/loadDetail/leave` |
| `features/hygiene/HygieneController.ets` | 现场巡检和复查、同轮记录匹配、上次评分对照、带原因更正与并发处理 | `loadComparison/usePreviousScores/correct/recheck/submitHygiene` |
| `features/assist/ManagerAssist.ets` | 值班简报／单单研判的主动调用、展示事实和引用 | 可选`orderId`、`night/onOpenRepair/onUnauthorized` |
| `features/mobility/MobilityController.ets` | 独立工作会话、唯一来源租约、代际门控、有界低频批次、手工锚点 | `attach/detach/subscribe/start/action/confirm/refresh` |
| `features/mobility/SystemMobility.ets` | API24位置／传感器／权限成对订阅、低电量退避、后台持续任务 | `start/stop/isCollecting/isBackgroundEnabled` |
| `features/mobility/MobilityEstimator.ets` | WGS84位移、单调时间、规则质量、计步重置；无原始轨迹存储 | `MobilityEstimator.accept/reset`、`StepBaseline.delta/reset` |
| `features/mobility/MobilityPage.ets` | 用途确认、状态和位置新鲜度、锚点预览、下一站核对建议 | `controller/dispatch/night/onOpenRoute` |
| `features/mobility/DeviceActions.ets` | CameraPicker、ScanKit、CalendarKit创建页、显式系统通知 | `takePhoto/scanAnchor/calendar/notify` |
| `features/photos/PhotoDraftController.ets` | 相册／拍照共享六张上限、上传重试、换号取消和清理 | `choose/retry/remove/reset/subscribe` |
| `features/photos/PhotoAttachments.ets` | 相册／拍照入口、三列已上传缩略图、重试与移除 | `controller/usage/night/disabled` |
| `services/ResourceSlot.ets` | 持有一个原生资源，销毁后释放迟到解码结果 | `begin/accept/clear` |

所有后台数据类型来自`contracts/openapi.json`生成的`ApiModels.ets`，没有另写调度或管理AI响应DTO。

## 会话与版本约束

登录成功后只有MANAGER开启心跳；服务端按账号去重，同账号Web与鸿蒙不由客户端相加人数。暂停接单仍在线，设置按账号共享。

UIAbility后台事件停止定时器并使在途结果失效，回到前台立即心跳。注销、换号、401和根页面销毁停止会话；旧账号迟到响应不能覆盖新账号计划。RoutePage有订阅且前台时独立每5秒只读刷新board，不发送额外心跳。离开路线／进入后台停止5秒循环，恢复前台立即一次。RoutePage只解除UI订阅，进入原工单详情不会停止仍在前台的30秒登录心跳。手动刷新、5秒刷新与心跳后的board读取通过RequestFlight共用同一个epoch请求；旧账号完成不能清掉新账号在途请求。

Controller保留最后一次计划，网络失败明确显示缓存／离线提示并阻止新执行动作；再次成功读取服务端后恢复。较旧revision被拒绝，新revision显示变化文字。同版本可以更新具体任务状态。界面不在本地决定派单、人数或维修完成。

工作位置证据来自人工／扫码锚点或设备观测；GPS不证明室内楼层与房间，过期锚点显示未知。只有任务物理状态EN_ROUTE／ON_SITE表示在途／现场；BLOCKED恢复为ASSIGNED后仍可再次出发，不拿repair.PROCESSING冒充物理位置。

设备来源绑定工作会话和当前登录代际。退出／换号／撤权／系统停止／租约失效使旧回调失效，停止采集；暂停和恢复建立新的stream，恢复采用服务端新sourceEpoch。后台发布由原生位置回调驱动，非setInterval；界面过期时钟只在前台运行，租约另有一次性到期保障。每批最多20条、16KB，至少15秒一次，低电量30秒；断网只保留内存中的有限短时摘要，重试使用相同批次ID，不持久保存连续轨迹。ACK不含坐标历史，成功后GET当前工作会话刷新最新位置。

计步是开机累计值，首次／回退／长间隔／暂停重启均重置基线。加速度只形成10秒运动标志，不做轨迹积分；未校准步长时不从步数输出精确速度。位置时间戳按UTC毫秒，系统开机时间纳秒换成毫秒；无效、乱序、过期或非步行速度不形成可靠步行估计。位置与接单意愿、在线人数、工单执行状态独立。

接受新任务时必须明确填写未来的可用截止时间，空值／过期会显著提示暂不参与新任务。设置中的版本冲突保留当前输入，可显式载入最新状态。未完成、在途和当前站点的责任仍由服务端维护。

## 平台网络与AI

API24的NetworkKit没有PATCH（官方标注API26起支持）；`Api.patch`使用本机SDK内已有的RemoteCommunicationKit RCP发送真正PATCH，设置与访问安排不更改为伪POST。参见[官方RCP说明](https://developer.huawei.com/consumer/en/doc/harmonyos-references/remote-communication-rcp/)。开发网络配置为本机HTTP联调启用对应组件，HTTPS证书仍使用默认校验。

管理AI首页为折叠的「值班简报」，工单详情为「AI研判」。必须主动同意才调用。值班简报只分析文字与统计，不显示附图选项；单单研判含图默认false，可明确附带本单前3张故障图。显示服务端事实、模型建议、待补充问题、不确定性和授权工单引用。建议没有业务动作按钮；引用通过正常详情鉴权打开。503／超时保留人工办理。AI_UNAVAILABLE、AI_REQUEST_FAILED和AI_RESULT_UNKNOWN是明确终态，释放本地键供用户下一次主动生成；网络未知、AI_REQUEST_RUNNING与未配置保留原键，不自动重调。学生助手和管理助手共用此策略。

学生结构化可上门时段均转换为带`+08:00`的ISO8601，未开启时不形成硬预约。后续学生修改使预约待确认，宿管才可确认预约、修改预计时长或暂不安排上门的原因。原报修进度和完工确认流程继续复用。

## 验证

在项目根目录使用系统 Node 执行：

```powershell
node --test harmony/tests/*.test.mjs
```

2026-11-25 的 107 项测试全部通过。`palette-contrast.test.mjs` 对照 `components/Palette.ets` 与 `design/tokens.json`，验证日夜主题的边界与正文对比度。`dispatch-recovery.test.mjs` 驱动 `DispatchController`，验证路线更新最多展示三条变化、无变化摘要时提示核对站点、同版本不重复通知，界面提示不包含内部版本号。该测试还覆盖保存设置期间的手动刷新。[主题测试](tests/palette-contrast.test.mjs) · [路线测试](tests/dispatch-recovery.test.mjs)

回归直接读取实际ArkTS源码，覆盖时间、幂等提交、未结列表、定时器生命周期与旧响应／旧版本拒绝。恢复测试仅替换原生I/O端口，通过实际Controller的后台刷新验证HTTP错误在接收当前账号的有效版本后清除；其它账号与过期版本不能清错。Hvigor负责原生编译检查；设备实际安装、RCP PATCH、接单设置、两端版本变化和执行闭环须由模拟器与应用内浏览器联合验证，不能用编译或这些测试替代。

设备适配器回放覆盖拒权、授权页迟到、位置on/off、计步基线、撤权释放、后台任务按ID停止和低电量降频；这是I/O替换测试。API24正式编译确认真实Kit签名可用。当前无真机，不把模拟器／回放记录解释为定位精度、真实步数、熄屏持续或耗电已经通过。
