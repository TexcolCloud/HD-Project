# 调度、访问安排与管理AI模块

本轮沿用R3暖白／暖黑；主题取自`components/Palette.ets`。`Index.ets`只负责页面入口、登录会话钩子及原工单详情回调，新增业务不继续写入其方法。

## 模块与接口

| 模块 | 职责 | 对外入口 |
| --- | --- | --- |
| `features/dispatch/DispatchController.ets` | 会话级调度状态、受保护请求、board缓存、版本门控、设置与执行动作 | `startSession/stopSession`、`subscribe`、`refresh/replan/saveSettings/taskAction/openRepair` |
| `features/dispatch/ForegroundPoll.ets` | 前台循环：30秒登录心跳与5秒可见路线只读刷新共用生命周期、单飞与代际失效 | `setSession/setForeground/stop`；可注入scheduler验证 |
| `features/dispatch/RequestFlight.ets` | 手动、轮询、心跳后GET按登录epoch共用一个在途请求 | `run/reset` |
| `features/dispatch/AppVisibility.ets` | UIAbility前后台事件唯一来源 | `setForeground/subscribe` |
| `features/dispatch/RouteRevision.ets` | 拒绝迟到旧计划；允许同版本刷新明细 | `accept/value/clear` |
| `features/dispatch/RoutePage.ets` | 订阅调度快照、展示本次路线、变化提示与待安排原因 | `controller/night/onOpenRepair` |
| `features/dispatch/RouteTaskCard.ets` | 当前位置／下一站／剩余／暂缓站点及明确执行动作 | `task`与`onAction/onOpen` |
| `features/dispatch/RouteSettings.ets` | 接单意愿、负责范围内当前位置、本次有效可用截止时间 | `manager/buildings`与`onSave/onCancel` |
| `features/visits/VisitWindowFields.ets` | 学生可上门范围的独立输入及不可变草稿 | `draft/night/onChange` |
| `features/visits/VisitArrangement.ets` | 原工单中的访问范围修改、宿管确认／时长／等待原因 | `repair/manager/night/onSaved` |
| `features/assist/ManagerAssist.ets` | 值班简报／单单研判的主动调用、展示事实和引用 | 可选`orderId`、`night/onOpenRepair/onUnauthorized` |

所有后台数据类型来自`contracts/openapi.json`生成的`ApiModels.ets`，没有另写调度或管理AI响应DTO。

## 会话与版本约束

登录成功后只有MANAGER开启心跳；服务端按账号去重，同账号Web与鸿蒙不由客户端相加人数。暂停接单仍在线，设置按账号共享。

UIAbility后台事件停止定时器并使在途结果失效，回到前台立即心跳。注销、换号、401和根页面销毁停止会话；旧账号迟到响应不能覆盖新账号计划。RoutePage有订阅且前台时独立每5秒只读刷新board，不发送额外心跳。离开路线／进入后台停止5秒循环，恢复前台立即一次。RoutePage只解除UI订阅，进入原工单详情不会停止仍在前台的30秒登录心跳。手动刷新、5秒刷新与心跳后的board读取通过RequestFlight共用同一个epoch请求；旧账号完成不能清掉新账号在途请求。

Controller保留最后一次计划，网络失败明确显示缓存／离线提示并阻止新执行动作；再次成功读取服务端后恢复。较旧revision被拒绝，新revision显示变化文字。同版本可以更新具体任务状态。界面不在本地决定派单、人数或维修完成。

当前位置来自手选或服务端已到达记录，不用室内GPS。只有任务物理状态EN_ROUTE／ON_SITE表示在途／现场；BLOCKED恢复为ASSIGNED后仍可再次出发，不拿repair.PROCESSING冒充物理位置。

接受新任务时必须明确填写未来的可用截止时间，空值／过期会显著提示暂不参与新任务。设置中的版本冲突保留当前输入，可显式载入最新状态。未完成、在途和当前站点的责任仍由服务端维护。

## 平台网络与AI

API24的NetworkKit没有PATCH（官方标注API26起支持）；`Api.patch`使用本机SDK内已有的RemoteCommunicationKit RCP发送真正PATCH，设置与访问安排不更改为伪POST。参见[官方RCP说明](https://developer.huawei.com/consumer/en/doc/harmonyos-references/remote-communication-rcp/)。开发网络配置为本机HTTP联调启用对应组件，HTTPS证书仍使用默认校验。

管理AI首页为折叠的「值班简报」，工单详情为「AI研判」。必须主动同意才调用。值班简报只分析文字与统计，不显示附图选项；单单研判含图默认false，可明确附带本单前3张故障图。显示服务端事实、模型建议、待补充问题、不确定性和授权工单引用。建议没有业务动作按钮；引用通过正常详情鉴权打开。503／超时保留人工办理。AI_UNAVAILABLE、AI_REQUEST_FAILED和AI_RESULT_UNKNOWN是明确终态，释放本地键供用户下一次主动生成；网络未知、AI_REQUEST_RUNNING与未配置保留原键，不自动重调。学生助手和管理助手共用此策略。

学生结构化可上门时段均转换为带`+08:00`的ISO8601，未开启时不形成硬预约。后续学生修改使预约待确认，宿管才可确认预约、修改预计时长或暂不安排上门的原因。原报修进度和完工确认流程继续复用。

## 验证

使用系统Node执行：

```powershell
node --test harmony/tests/date-format.test.mjs harmony/tests/submission-keys.test.mjs harmony/tests/pending-repairs.test.mjs harmony/tests/dispatch-heartbeat.test.mjs harmony/tests/dispatch-revision.test.mjs harmony/tests/dispatch-flight.test.mjs harmony/tests/dispatch-recovery.test.mjs
```

回归直接读取实际ArkTS源码，覆盖时间、幂等提交、未结列表、定时器生命周期与旧响应／旧版本拒绝。恢复测试仅替换原生I/O端口，通过实际Controller的后台刷新验证HTTP错误在接收当前账号的有效版本后清除；其它账号与过期版本不能清错。Hvigor负责原生编译检查；设备实际安装、RCP PATCH、接单设置、两端版本变化和执行闭环须由模拟器与应用内浏览器联合验证，不能用编译或这些测试替代。
