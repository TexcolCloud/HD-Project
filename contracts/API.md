# HD Platform API 1.3

实现固定契约，前缀 `/api/v1`，端口 `8080`。所有实体ID为字符串。成功 `{data:T,traceId:string}`；失败 `{error:{code,message,fieldErrors:[]},traceId}` 使用真实HTTP状态。列表 `data={items:T[],total:number,page:number,pageSize:number}`，默认1/20最大100。

1.3增量补齐服务闭环；1.2设备接口与1.1公开schema、操作保持兼容。可执行检查为 `python scripts/check-contracts.py`。字段以 `generate_openapi.py` 及生成结果为准。

## 服务闭环（1.3）

- `POST /dispatch/tasks/{orderId}/takeover`：有资格宿管填写原因，以工单与任务双版本异常接续；原负责人可用时拒绝。接续后行程受阻，现场核对后恢复。
- `GET /mobility/efficiency`：本人近30天可信通行片段、可比较样本、预计／实际误差及个人校准方向数；没有足量样本时返回空值与解释。
- `GET /messages`、`GET /messages/summary`、`POST /messages/{id}/read`：本人持久收件箱、投递状态统计与显式已读。消息查看仍核对当前账号及范围，已读不推进业务。
- `GET/POST /hygiene-remediations`、`GET/PATCH /hygiene-remediations/{id}`：整改查询／创建／责任和期限调整；列表支持`inspectionId`精确定位已有任务。
- `POST /hygiene-remediations/{id}/feedback|review|cancel`：责任学生反馈、宿管关联有效合格复查验收或退回、带原因取消；写入保持版本、幂等和历史。
- `GET/POST /ai/manager/repairs/{id}/feedback`：真实本人建议的采用／修改／忽略和实际结果；`canFollowUp`区分可读历史与可追加本人反馈。
- `User.mustChangePassword`为可选扩展。首次／重置后仅允许身份查询、改密和退出，业务接口返回`PASSWORD_CHANGE_REQUIRED`；既有演示账号维持原登录方式。

业务说明与验收边界见[16](../docs/16-服务闭环与联合验收.md)，受控账号维护和配套恢复见[17](../docs/17-账号与恢复运行手册.md)。

## 现场辅助（1.2）

- `GET /mobility/session` 返回本人工作会话、最新位置、授权地点和采样策略；最新位置不是轨迹。
- `POST /mobility/sessions` 仅移动端开启，需要确认 `mobility-v1` 用途。同账号已有有效来源时，明确接管并提交旧会话版本。
- `POST /mobility/sessions/{id}/pause|resume|end` 使用来源代际和预期版本；恢复仅原移动会话允许，Web 可暂停和结束本人会话。
- `POST /mobility/sessions/{id}/samples` 每批 1–20 条、正文最多 16KB；同来源片段、递增序号、服务器重新判断时序、精度和速度。短 ACK 只含批次计数／序号，`context=null`，最新位置另取 `GET /mobility/session`。
- `POST /mobility/sessions/{id}/anchor-confirmations` 提交已登记地点、来源 MANUAL／QR 和位置预期版本。扫码内容为 `dormlink-anchor:<scanCode>`，先预览再确认，静态码不是到场证明。
- `GET /mobility/anchors` 宿管读取负责楼栋地点；学生只读当前入住楼栋的有效地点。`POST /mobility/anchors`、`PATCH /mobility/anchors/{id}` 仅宿管维护负责范围，更新须版本检查。没有核验坐标时留空。
- `GET /mobility/travel-models` 返回有权查看的共享通行估算及样本依据。至少 5 段、3 个独立工作会话；只采用已确认入口间、显式出发／到达的片段。
- `POST /mobility/travel-models/{fromId}/{toId}/rollback` 按预期版本停用该方向的学习估算，恢复既有人工基线；需要同时拥有两端楼栋权限。

观测约 60 秒后降级，120 秒来源租约过期；暂停、注销、撤销或结束后不再用于调度。速度未知返回 null，GPS 不证明楼层／房间；缺少实测路径长度时不把分钟数除以速度。位置采集不触发接单、到场、完工或考勤动作，也不默认发送给 AI。

种子账号（仅本地开发）：`manager1`、`manager2`、`student1`、`student2`、`student3`；密码统一 `Demo@123456`。manager1负责A/B栋，manager2仅B栋；student1住A栋302，student2住B栋405，student3未入住。数据持久化。

## 认证

- `GET /auth/csrf` → `{token:string,headerName:"X-XSRF-TOKEN"}`，Web登录前先请求（携带credentials）。
- `POST /auth/login` body `{username,password,clientType:"WEB"|"MOBILE"}` → `{user:User,token?:string}`。WEB服务端Set-Cookie `HD_SESSION` (HttpOnly,SameSite=Lax)，MOBILE返回token使用 `Authorization: Bearer ...`。WEB所有POST/PATCH/DELETE需 `X-XSRF-TOKEN`，登录后重新GET csrf。
- `GET /auth/me` → `User`；`POST /auth/logout` → `{loggedOut:true}`。
- `POST /auth/change-password` body `{oldPassword,newPassword}` → `{changed:true}` 并撤销会话。
- `User={id,username,displayName,role:"MANAGER"|"STUDENT",buildingIds:string[],studentId?:string,currentRoom?:Room,mustChangePassword?:boolean}`。

Web代理 `/api` 到 http://127.0.0.1:8080，credentials:include；鸿蒙使用MOBILE bearer，没有CSRF要求。不在客户端保存任何AI密钥。

## 公共类型

实体时间字段是ISO8601字符串；version整数。可选字段返回null。查询 `q,buildingId,roomId,status,startDate,endDate,page,pageSize`（不适用项忽略）。GET单资源无权统一404。

- `Building={id,code,name,floorCount,enabled,version}`。
- `Room={id,buildingId,buildingName,roomNo,floorNo,capacity,occupiedCount,enabled,remark,version}`。
- `Student={id,userId,studentNo,name,college,major,grade,className,phone,status,roomId?,roomNo?,buildingName?,version}`。学生不读其他学生；未入住池手机号脱敏。
- `Residence={id,studentId,studentName,studentNo,roomId,roomNo,buildingId,buildingName,checkedInAt,checkedOutAt?,reason,version}`。
- `Repair={id,orderNo,reporterStudentId,reporterName,roomId,roomNo,buildingId,buildingName,title,description,category,priority,urgentReason,contactName,contactPhone,contactTimeSlot,status,handlerUserId?,handlerName?,relatedOrderId?,submittedAt,completedAt?,version,allowedActions:string[],reportImages:FileAsset[],resultImages:FileAsset[],latestResult?:string}`。
- `RepairEvent={id,orderId,fromStatus?,toStatus,action,operatorName,note,createdAt}`。
- `Visitor={id,visitorName,phone,visitedStudentId,studentName,roomId,roomNo,buildingId,buildingName,purpose,remark,arrivedAt,leftAt?,status:"VISITING"|"LEFT",version}`。列表phone掩码；明文另POST reveal-contact。
- `Hygiene={id,roomId,roomNo,buildingId,buildingName,inspectionDate,round,inspectedAt,floorScore,deskScore,bedScore,bathroomScore,totalScore,grade,issueNote,remediationNote,inspectorName,relatedInspectionId?,voidedAt?,version,images:FileAsset[]}`。
- `FileAsset={id,originalName,mime,sizeBytes,url,thumbnailUrl,usage}`。url/thumbnailUrl为 `/api/v1/files/{id}/content` 等受保护URL。
- 类别 `UTILITIES|FURNITURE|NETWORK|OTHER`，紧急程度 `NORMAL|URGENT`。
- 状态 `SUBMITTED|ACCEPTED|PROCESSING|PENDING_CONFIRMATION|COMPLETED|CANCELLED|REJECTED`。

## 管理档案

- GET `/buildings` → 分页Building；POST body `{code,name,floorCount,enabled?}` → Building；PATCH `/{id}` body同上+expectedVersion。
- GET `/rooms` → 分页Room；GET `/{id}` → Room；POST body `{buildingId,roomNo,floorNo,capacity,enabled?,remark?}` → Room；PATCH `/{id}` 同上+expectedVersion。
- GET `/students` → 分页Student；GET `/{id}` → Student；POST `{studentNo,name,college,major?,grade?,className?,phone,status?}` → Student（创建同学号学生账号，只保存独立随机口令摘要并要求首次改密；核验身份后由[受控维护工具](../docs/17-账号与恢复运行手册.md)签发临时凭据，不返回共享初始密码）；PATCH `/{id}` 同上+expectedVersion。
- GET `/residences` → 分页Residence；POST `/residences/check-in` `{studentId,roomId,checkedInAt?}` → Residence；POST `/{id}/transfer` `{targetRoomId,reason,expectedVersion}` → 新Residence；POST `/{id}/check-out` `{reason,expectedVersion}` → Residence。
- GET `/me/room` → Room|null；GET `/me/residences` → 分页Residence。

## 报修

- GET `/repairs` → 分页Repair；GET `/repairs/{id}` → Repair；GET `/{id}/events` → `RepairEvent[]`。
- POST `/repairs` `{title,description,category,priority,urgentReason?,contactName,contactPhone,contactTimeSlot?,reportImageIds:string[],relatedOrderId?}` → Repair。URGENT时urgentReason必填。
- POST `/repairs/{id}/{action}` body `{expectedVersion,reason?,note?,resultDescription?,resultImageIds?:string[]}` → Repair。
- action对应allowedActions：`accept` SUBMITTED→ACCEPTED；`reject` SUBMITTED→REJECTED必填reason；`start` ACCEPTED→PROCESSING；`finish` PROCESSING→PENDING_CONFIRMATION必填resultDescription；`confirm` PENDING_CONFIRMATION→COMPLETED；`rework` PENDING_CONFIRMATION→PROCESSING必填reason（学生或原办理宿管）；`cancel` SUBMITTED→CANCELLED；`notes`原办理人同状态追加说明必填note。
- 新建、动作、其他POST业务提交携带 `Idempotency-Key` UUID，同一次网络重试复用。PATCH及动作携带expectedVersion，冲突409。

## 访客和卫生

- GET `/visitors` → 分页Visitor；GET `/{id}` → Visitor；POST `{visitorName,phone,visitedStudentId,purpose,remark?,arrivedAt?,confirmDuplicate?:boolean}` → Visitor；相同电话在访时未confirmDuplicate返回409 `VISITOR_DUPLICATE_CONFIRMATION_REQUIRED`，客户端二次明确后重发新key。
- POST `/visitors/{id}/leave` `{leftAt?,expectedVersion}` → Visitor；PATCH `/{id}` `{visitorName?,phone?,purpose?,remark?,arrivedAt?,leftAt?,correctionReason,expectedVersion}` → Visitor；POST `/{id}/reveal-contact` → `{phone}`。
- GET `/hygiene-inspections` → 分页Hygiene；GET `/{id}` → Hygiene；GET `/me/hygiene-inspections` → 分页Hygiene。
- POST `/hygiene-inspections` `{roomId,inspectionDate,round,inspectedAt?,floorScore,deskScore,bedScore,bathroomScore,issueNote?,remediationNote?,relatedInspectionId?,imageIds:string[]}` → Hygiene。
- PATCH `/{id}` 同上+`expectedVersion,correctionReason` → Hygiene；POST `/{id}/void` `{expectedVersion,reason}` → Hygiene。
- GET `/hygiene-inspections/{id}/history` 仅宿管 → `CorrectionEvent[]={id,action,operatorName,note,changes:string,createdAt}`，changes为JSON字符串保存更正前后值。
- 四项各0~25，总分后端计算；grade `EXCELLENT|GOOD|PASS|RECTIFY`，90/80/60界；低于60必填issueNote。round默认ROUTINE，复查RECHECK（多次可RECHECK2等，长度≤20）。

## 统计

- GET `/statistics/overview` → `{roomCount,capacity,occupiedCount,availableCount,occupancyRate,openRepairs,pendingConfirmation,visitingCount,todayVisitors,hygieneAverage:number|null,rectifyRoomCount}`，occupancyRate为百分数0~100。
- GET `/statistics/repairs` → `{byStatus:{name,value}[],byCategory:{name,value}[],trend:{date,count}[],averageHours:number|null,total,completed}`。
- GET `/statistics/hygiene` → `{average:number|null,byGrade:{name,value}[],trend:{date,average}[],rectifyRoomCount}`。
- GET `/statistics/visitors` → `{visitingCount,total,trend:{date,count}[]}`。
- 学生不访问统计，只通过本人列表和/me/room计算首页服务概况；宿管移动端可读overview。

## 附件和AI

- POST `/files` multipart `file` + `usage=REPORT|RESULT|INSPECTION|REMEDIATION` → FileAsset；REPORT和REMEDIATION仅学生，RESULT和INSPECTION仅宿管。JPEG/PNG/WebP≤10MB，每组最多6（提交业务时验证）。上传后业务绑定前只上传者可读；绑定整改凭证后按当前责任、入住区间和楼栋范围鉴权，旧轮次归档，宿管可按范围读取历史。
- GET `/files/{id}/content`、`/thumbnail` 图片二进制（需Cookie/Bearer）；DELETE `/{id}`仅本人TEMP → `{deleted:true}`。
- POST `/ai/repair-suggestions` `{description,imageIds:string[],consentToExternalAnalysis:true}` → `{suggestionId,title,category,summary,uncertainty,questions:string[],riskTags:string[],requiresHumanReview:true}`。未配置返回503 `AI_NOT_CONFIGURED`，不得用假结果替代；手动填写继续可用。
- GET `/health` 无需登录 → `{status:"UP",database:"UP"}`。

## 多宿管动态调度

精确请求与响应以[OpenAPI](openapi.json)中的`Dispatch*`、`RepairVisitRequest`为准。以下端点仅宿管使用（学生可调整本人未结工单的上门偏好）：

- `POST /dispatch/heartbeat {}` → `DispatchManager`，更新当前有效会话的心跳，不使用业务幂等缓存；同账号多会话按人去重，30秒前台心跳、120秒活跃窗口。
- `GET /dispatch/board` → `DispatchBoard`；包含单调`revision`、`dirty`、范围内在线／参与人数、`me`、可见`routes`、`unassigned`及原因。未认领路线推荐不改变工单状态。
- `PATCH /dispatch/me` → `DispatchManager`，设置接单意愿、本次`availableUntil`及`positionBuildingId/positionFloorNo`，带`expectedVersion`。空或过期时段不接新任务，楼栋须在授权范围。
- `POST /dispatch/replan {expectedRevision?}` → `DispatchBoard`，手工补充重算；新增工单、办理状态与人员资格变化由后端自动触发。
- `POST /dispatch/tasks/{orderId}/claim {expectedOrderVersion,expectedRevision?}` → `Repair`，复用真实接单及唯一办理责任约束。
- `POST /dispatch/tasks/{orderId}/{depart|arrive|block|resume} {expectedTaskVersion,reason?}` → `DispatchTask`。受阻必填原因。物理执行状态与维修业务状态独立；到达不自动开始维修，同一宿管只能有一个在途或现场任务。
- `POST /dispatch/tasks/{orderId}/handoff {targetManagerId,reason,expectedOrderVersion,expectedTaskVersion}` → `Repair`；仅原办理人可交接已接单、未出发／到场任务，接收人需有权限且可参与调度。Web和鸿蒙均提供交接界面；异常接续另见1.3的`takeover`接口，不能用重规划隐式转移责任。
- `GET /dispatch/travel` → `DispatchTravel`；`PATCH /dispatch/travel/{fromId}/{toId} {minutes,expectedVersion}` → `DispatchTravelLink`。通行时间是楼栋／楼层粒度估算，两个楼栋均需有权限；`baselineTravelMinutes=-1`表示无可比较的提交顺序，不得据此计算节省率。
- `Repair`新增`windowStart/windowEnd`（ISO8601或null）、`windowConfirmed`、`estimatedMinutes`、`visitRequired`、`waitingReason`；新建请求可选上门偏好起止。`PATCH /repairs/{id}/visit`带订单版本，学生仅能改本人偏好并清除确认，宿管才能确认预约及修改服务安排。仅`SUBMITTED/ACCEPTED/PROCESSING`可编辑。

所有写操作沿用Cookie/CSRF或Bearer鉴权，身份与权限由后端决定；推荐被新版本替换不代表已接单任务被转交。详细模块说明与边界见[动态调度方案](../docs/08-多宿管动态调度方案.md)。

## 宿管只读AI

- `POST /ai/manager/brief`、`POST /ai/manager/repairs/{id}`，请求`ManagerAssistRequest={consentToExternalAnalysis:true,includeImages?:boolean}` → `ManagerAdvice`。
- 简报仅发送范围内工单摘要和统计；单工单研判可主动附前3张故障图。`facts`和`evidence`由服务端统计／授权查询产生；模型输出不修改工单、人员归属、预约或路线。
- 请求携带`Idempotency-Key`，同用户／路径／键且相同请求体复用完整成功结果；进行中返回409 `AI_REQUEST_RUNNING`，同键异内容409。明确失败的首次调用503 `AI_UNAVAILABLE`，失败键重放409 `AI_REQUEST_FAILED`；90秒仍未知则409 `AI_RESULT_UNKNOWN`，均不自动再次调用供应商。后两者提示用户明确重新生成；网络未知或进行中重试保留原键，明确终态后下一次主动生成使用新键。
- 权限在缓存读取前核验，授权范围变化拒绝旧简报缓存；未配置503 `AI_NOT_CONFIGURED`。学生填写助手和宿管助手共用日／分钟额度、并发限制、超时与调用审计。当前已实际验证官方纯文本工单研判、Web展示及本人建议反馈留档；官方附图研判、值班简报的真实调用未验收，协议fixture不能替代这些场景。证据与边界见[16](../docs/16-服务闭环与联合验收.md)。
