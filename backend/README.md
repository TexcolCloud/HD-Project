# DormLink Java 后端

Spring Boot **4.1.2**，Java 编译基线21，已使用本机JDK25编译运行。数据访问使用参数化 Spring JDBC，保留事务、数据库约束与显式行锁；无需Redis、消息队列或内存业务仓库。API契约在 [`../contracts/API.md`](../contracts/API.md) 和 [`../contracts/openapi.json`](../contracts/openapi.json)。

## 启动

在本目录使用PowerShell：

```powershell
# 首次自动从Maven Central下载固定Maven3.9.12，校验SHA-512
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

默认 `local` profile 使用H2文件库 `data/dormlink.mv.db`，重启保留业务数据，附件位于 `data/uploads/`。默认API为 `http://127.0.0.1:8080/api/v1`。健康接口 `/health` 会实际访问SQL。

项目已配套MySQL8.4 Docker环境，可从项目根目录运行：

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d
.\scripts\run-backend.ps1
```

也可手动设置 `SPRING_PROFILES_ACTIVE=mysql`、`DB_URL`、`DB_USER`、`DB_PASSWORD` 后运行JAR。默认URL为 `jdbc:mysql://127.0.0.1:13306/dormlink?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC`，默认用户dormlink，密码必须通过环境变量提供。不要把实际凭据提交到版本库。

初始建表脚本为 `src/main/resources/schema.sql`。新增结构由 `SchemaMigrations` 在初始建表后按版本应用，当前 `db/migration/V2__dynamic_dispatch.sql` 增加会话心跳、预约字段和调度表，`schema_migration` 保存版本与SHA-256。迁移只增列/建表；部分DDL失败后重启按元数据跳过已经添加的列，完成后才记录版本。已应用文件校验和改变会中止启动，不能静默重写历史迁移。旧库不清空，既有工单和入住保留。

## 本地账号和会话

空库且 `APP_SEED=true`（默认）时，事务创建虚构演示数据：

| 用户名 | 角色与范围 |
| --- | --- |
| manager1 | 宿管，A/B栋 |
| manager2 | 宿管，仅B栋，用于越权验收 |
| student1 | 学生，A栋302 |
| student2 | 学生，B栋405 |
| student3 | 学生，未入住 |

本地演示密码统一为 `Demo@123456`，数据库仅保存BCrypt摘要。新建学生账号用户名为学号；当前本地课程演示流程使用同一初始密码，正式对外部署前须改为校方受控账号初始化，并更换全部演示密码。`APP_SEED=false` 禁止空库自动演示初始化；已有数据永不被种子覆盖。

Web先GET `/auth/csrf`，携带Cookie和 `X-XSRF-TOKEN` 登录 `clientType=WEB`；登录后重新GET csrf。服务端为Web设置HttpOnly SameSite Cookie，对Cookie身份的写请求校验数据库会话绑定CSRF token。鸿蒙登录 `clientType=MOBILE` 取得随机Bearer token；数据库只保存其SHA-256摘要。会话绝对有效期8小时，登出、禁用账号及改密撤销；无需JWT或Redis。

对公网HTTPS部署设 `COOKIE_SECURE=true`，Web和API同源；开发Cookie非Secure是为了本机HTTP和模拟器联调。默认CORS只允许本机5173/4173。

## 已实现的业务

- 楼栋和房间档案、容量与状态；学生档案与查询；入住、原子调宿、退宿和历史。
- 访客登记、重复在访提示、离访、更正、脱敏查询与明文查看审计。
- 地面/桌面/床铺/卫生间四项卫生评分、总分等级、同房同日同轮次有效唯一、更正/作废/复查关联和历史审计。
- 学生本人报修、故障联系与房间快照、工单合法动作、办理人约束、追加进度、返修确认及完整事件轨迹。
- 真实JPEG/PNG/WebP上传、像素/大小检查、JPEG重编码去除EXIF、私有文件存储、绑定前后继承权限；每组6张、单图10MB。WebP由锁定的TwelveMonkeys `imageio-webp:3.12.0` ImageIO SPI读取，不依赖JDK自带WebP解码，也不依赖客户端先转码；插件说明见[官方项目](https://github.com/haraldk/TwelveMonkeys)。
- 数据范围内入住、工单、卫生、访客统计；SQL持久化，当前指标与区间指标分别计算。
- 随机会话、楼栋范围、同学生唯一在住、房间行锁、版本检查、同用户事务内幂等记录。
- 动态多宿管路线：30秒前台心跳、服务端120秒活跃判定、账号去重、跨端共享暂停和可用时段；推荐与实际认领分离，数据库唯一有效claim，出发/到达/受阻/恢复和显式交接独立于维修状态。

调度应用拆分为 `DispatchPresence`（会话与账号可用性）、`DispatchRepository`（持久化及版本发布）、`DispatchCoordinator`（合并事件与事务外计算）、`DispatchService`（权限和执行规则），纯算法为 `cn.dormlink.dispatch.RoutePlanner`。业务事务提交后只发本机dirty事件，定时器用短事务持久化revision/事件再取快照；计算不占业务事务，发布时锁版本并重新核验工单、人员资格和通行配置指纹。冲突最多重试3次，继续有变化则保留dirty等待下一轮。启动强制重规划，避免进程中断丢失本机dirty信号后继续使用旧结果。

默认出发点为首个启用授权楼栋1层，未人工确认时 `positionUpdatedAt=null`；首次可用时段为未来8小时，API清空availableUntil后不接受新推荐。楼层移动估算每层2分钟、同层1分钟，A/B栋之间初始化双向5分钟，均为可核对的估算。跨楼通行链接可通过有双方楼栋权限的宿管维护；没有连接返回待安排原因，不用房号算距离。单批最多500个任务，多余项保留明确BATCH_LIMIT原因，不丢弃。

正在现场和已出发任务保留锁定前缀；同一宿管不能同时执行两个EN_ROUTE/ON_SITE任务，受阻任务保留责任但可处理其它站点。到达不自动调用维修start或finish；旧维修start入口同事务同步ON_SITE和现场位置。学生只能更改结构化时间偏好，硬预约确认、处理时长和是否上门由宿管维护；传回相同的原安排字段不会扩大或误拒权限。

学生不能读取他人工单/附件，也不能查看入住前或退宿后的房间巡检。学生调宿不改变历史报修房间及宿管范围。已完成/已取消/已驳回均为终态，不能直接重开。

## DeepSeek AI

默认配置用户选择的 `deepseek-flash` 和官方 `https://api.deepseek.com`。通过环境变量提供 `AI_API_KEY`，可选覆盖 `AI_BASE_URL`、`AI_MODEL`、`AI_DAILY_LIMIT`。没有key时返回明确503 `AI_NOT_CONFIGURED`，表单仍可手填；没有伪造AI回复。

请求由Java发出，先校验学生身份、图片所有权和用户同意，只发送故障文本及最多3张鉴权读取的图片。图片重编码后转换base64 data URL，不公开文件链接；不自动附加学号、手机号、房间和访客资料。结构化返回标题、分类、摘要、不确定性和待补充问题，界面必须经人工采用和再次提交。

默认每人每日10次、每分钟2次、全局并行2个，HTTP总超时15秒。用量与失败状态写入 `ai_invocation`，不默认记录完整提示词/图片。当前限制按调用次数，不是币种费用预算；模型收费与实际可用额度由所配置账号承担并在供应商后台管理。

AI请求使用 `Idempotency-Key`：`AiCommand`保存路由、提交标识和请求体，`AiRequestLedger`复用现有SQL幂等表，在短事务内校验用户和已排序授权楼栋、预占次数并写RUNNING；HTTP期间不持数据库锁。成功建议连同服务端facts/evidence/generatedAt完整缓存，同key重试不重新调用模型，异内容复用key返回409。角色和工单楼栋权限在读缓存前仍校验，授权范围变化不会返回旧范围简报。

进行中同key返回409 `AI_REQUEST_RUNNING`；明确初次失败返回503 `AI_UNAVAILABLE`，失败key重放返回409 `AI_REQUEST_FAILED`；结果未确认或RUNNING超过90秒返回409 `AI_RESULT_UNKNOWN`，均不会静默重调。网络状态不明时客户端保留原key查询，只有用户明确重新生成才用新key。幂等记录按既有24小时保留策略清理。管理研判会对实际故障标题、描述和历史备注中已知姓名、连续或带空格/括号/连字符的电话和长数字标识脱敏，并先做全角字符规范化；不会把源联系人字段直接放进模型内容。

官方接入与视觉输入格式： [Agent integrations](https://api-docs.deepseek.com/quick_start/agent_integrations/)、[Vision](https://api-docs.deepseek.com/guides/vision/)。供应商模型可用性应以实际账号调用验证；本地未提供真实key时，验收证据仅能证明503降级和HTTP适配结构。

## 验证

```powershell
.\mvnw.cmd test
# 根目录，运行中的真实MySQL后端HTTP验收
python scripts/acceptance-api.py
```

Java测试使用独立H2内存库和随机端口，不读取真实AI密钥。原业务集成测试覆盖登录/撤销、角色/跨楼栋/他人资源、房间筛选、并发最后一个名额、完整工单状态、重复检查与修正审计、上传绑定前后权限、损坏PNG的415错误、新建停用学生禁止登录，以及原始WebP直接multipart上传和JPEG重编码。AI协议测试使用明确的本地HTTP fixture，验证多模态组包、管理研判授权、完整结果缓存、同key并发只调用一次、失败/未知结果不静默重调，以及实际含身份文本的脱敏；它们不代表已经调用真实DeepSeek账号。调度集成测试覆盖presence去重/过期、共享暂停、唯一claim、执行互斥、显式交接和旧计划拒绝发布。`target/red-*.log` 与 `target/green-*.log` 保留本次Red→Green开发记录，实际测试数量及结果在 `target/surefire-reports/`。

真实MySQL并发回归在单独的 `dormlink_concurrency_test` 库运行。先由本机数据库维护账号建此库并授予开发用户权限，然后从被忽略环境文件设置 `DB_USER`、`DB_PASSWORD`，设置 `MYSQL_TEST_URL` 指向该测试库，执行 `mvnw.cmd -Dtest=MySqlConcurrencyTest test`。不设置此变量时默认跳过，且测试拒绝指向正在演示的dormlink库。

该回归使用不同宿管、独立事务和明确同步点，验证入住与房间停用/缩容、同版本楼栋编辑、重复离访，以及等待跨楼栋调宿后重新校验学生编辑权限。命令事务显式READ_COMMITTED，先锁业务行再读取权限/版本，带版本条件写入；住房操作按学生→楼栋→房间的稳定顺序取锁。测试不清表、不修改种子数据。

根任务的 `output/acceptance/api-results.json` 记录真实MySQL HTTP验收，不等同浏览器或鸿蒙真机UI验收。测试和演示会增加标有测试名称的虚构记录，不删除已有业务数据。

## 代码入口

`SecurityConfig/AuthService` 管身份和CSRF；`BusinessSupport` 管事务内幂等和审计；`HousingService` 管入住锁；`RepairService` 管状态；`OperationsService` 管访客/卫生；`FileService` 管图片；`StatisticsService` 管口径；`AiService` 管外部模型。各Controller只做HTTP路由和统一响应包装。

已知边界：单校单实例；按房间容量，无独立床号；受控初始化维护宿管账号和楼栋授权；普通业务界面不开放任意角色赋予或工单转派。私有附件目录和数据库需一起备份恢复。
