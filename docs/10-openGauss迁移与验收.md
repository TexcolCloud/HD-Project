# openEuler／openGauss 数据库迁移与验收

更新日期：2026年11月11日（Asia/Shanghai）。状态：本轮数据库替换、数据保留迁移、自动化／HTTP／三端联调及最终运行核对已完成。Java API当前以openGauss profile连接openEuler虚拟机中的项目PG库。统一交付汇总（本机生成：`output/acceptance/opengauss-delivery-summary.json`）记录实际结果；[07](07-开发与验收.md)和[09](09-动态调度交付与验收.md)保留此前MySQL环境的历史结果，不作为本轮openGauss证据。

## 变更范围

Java后端继续使用Spring JDBC、显式事务、参数化SQL及数据库约束；数据库部署在VMware中的openEuler系统。Vue管理端和原生ArkTS应用仍通过同一Java API访问数据，接口契约、角色权限、R3界面及附件下载入口保持一致。

目标为项目独立的 `dormlink` 库，采用PG兼容模式，避免默认A模式将空字符串视为NULL而改变现有必填字段语义。SSH实际检查为openEuler24.03 LTS-SP4，使用虚拟机中既有的Docker容器 `OpenGauss`，数据库为openGauss-lite7.0.0-RC1、build10d38387、编译日期2025-03-21。容器published15432→5432，持久目录 `/data/OpenGauss`，restart=always；未下载、替换现有镜像或重装虚拟机。原实例默认A库保留，项目主库及platform／dispatch／ai／concurrency四个独立测试库另行创建为PG模式，见环境记录（本机生成：`output/acceptance/opengauss-environment.json`）。

应用角色 `dormlink`不具有SYSADMIN、CREATEDB或CREATEROLE权限，在专属数据库内拥有所需public schema权限。HBA按项目五个数据库和开发机VMnet8主机 `/32` 放行，该角色来自其他主机的连接拒绝；沿用既有实例MD5认证及原密码加密设置，没有修改全局算法。

后端通过[openGauss profile](../backend/src/main/resources/application-opengauss.properties)选择 `org.opengauss:opengauss-jdbc:6.0.6-og`／`org.opengauss.Driver`、[PG初始DDL](../backend/src/main/resources/db/opengauss/schema.sql)和[PG V2](../backend/src/main/resources/db/opengauss/V2__dynamic_dispatch.sql)。JDBC设置 `stringtype=unspecified`、`use_boolean=true`、`currentSchema=public` 以及UTC会话；插入显式返回ID列，时间读写使用UTC。原MySQL／H2迁移文件及已应用校验和保留，不能直接修改旧V2文件后要求已有数据库接受新校验和。真实目标库结果与隔离H2／纯算法结果分别记录在下表。

## 数据保留与切换原则

原MySQL容器和命名卷作为本次源数据保留；切换不以删库、重建演示数据或关闭外键校验代替迁移。旧API停止写入后，迁移工具从源库一致性只读快照读取，按外键依赖顺序导入空的目标业务表，显式保留主键、关联关系、状态及历史事件。

迁移工具必须拒绝已有业务数据的目标库，不能静默覆盖或合并业务行。`schema_migration`保存目标方言脚本的实际校验和；`dispatch_revision`等初始化控制行需要显式处理，不能把源库的脚本校验和直接冒充为目标脚本校验和。

导入后比较逐表行数、主键、NULL与空字符串、布尔和毫秒时间的规范化摘要，报告只保存数量、结果和摘要，不保存口令、会话、学生资料或业务明文。自增序列至少恢复至源库下一值与目标最大ID加一的较大值；序列校正和API启用顺序以工具实际实现为准。

Java仍运行于开发机时，私有附件保留在原`backend/data/uploads`目录。数据库迁移复制附件元信息，核对有效文件是否存在及大小是否相符；已删除附件缺少实体文件不能被误判为迁移丢失。数据库与附件依旧需要配套备份和恢复。

本次迁移快照为22表、567行；21张数据表逐行类型化内容一致，另1张控制表使用目标PG脚本校验和。最终只读复核确认源MySQL各表行数仍与该快照一致，且没有后续UI新建的工单21。openGauss之后执行了验收并增加虚构业务记录，因此567是迁移时快照，不是当前目标库总行数。

## 本机配置与操作入口

SSH和数据库凭据只放入被Git忽略的本机环境文件。文档、脚本示例、日志及验收JSON不得写入真实密码、私钥或会话；不修改全局PATH，不关闭鉴权、证书验证或系统安全措施来通过连接测试。

将[openGauss模板](../deploy/opengauss.env.example)复制为 `deploy/.env.opengauss`，填写 `OG_HOST`、`OG_PORT`、`OG_DATABASE`、`OG_USER`、`OG_PASSWORD`。脚本仅加载应用所需变量，SSH管理员身份不会传给Java服务。AI配置也随所选数据库的环境文件加载；切换时要保留所需AI配置，不能假设仍读取旧 `deploy/.env`。

项目根目录PowerShell：

```powershell
# 只确认虚拟机数据库TCP端口可达，不代表SQL登录／兼容性通过
.\scripts\start-database.ps1

# 标准JDBC／H2隔离自测，不接触真实数据库
.\scripts\migrate-database.ps1 -SelfTest

# 仅检查源结构、附件、目标兼容模式和空库；不会初始化或导入目标
.\scripts\migrate-database.ps1 -CheckOnly
```

实际迁移前停止旧API写入并保留源库／附件备份；不能在两份数据库同时办理业务后直接来回切换。保持MySQL源容器可读，目标库必须为空或仅含工具允许的初始化控制行：

```powershell
.\scripts\migrate-database.ps1

# 默认-Database opengauss；打包和测试后启动8080
.\scripts\run-backend.ps1
```

迁移脚本从旧 `deploy/.env` 读取MYSQL变量、从 `deploy/.env.opengauss` 读取OG变量，报告默认写 `output/acceptance/opengauss-migration-results.json`；如需保留多次报告，使用 `-ReportPath` 指定新文件。工具只读源库、拒绝非空目标业务表，不自动停API，不删除或清空任何业务表。预检、失败及自测报告均不能冒称完成实际导入。

`run-backend.ps1 -BuildOnly`无需数据库环境文件；`-SkipBuild`复用已成功产物。显式旧MySQL方式为 `start-database.ps1 -Database mysql` 与 `run-backend.ps1 -Database mysql`；独立H2为 `run-backend.ps1 -Database local`。旧MySQL是保留源库，不是持续同步副本。

## 验证记录

| 检查 | 当前状态 | 证据与实际范围 |
| --- | --- | --- |
| SSH与运行环境 | 已核实 | 环境记录（本机生成：`output/acceptance/opengauss-environment.json`）：openEuler24.03 LTS-SP4、现有OpenGauss容器及上述版本／持久目录；SSH凭据仅保留本机 |
| 数据库与授权 | 完成 | 主库与四个测试库均为PG模式，应用角色权限及主机白名单见环境记录 |
| 后端自动化与打包 | 47通过，0失败／错误／跳过，package成功 | `output/acceptance/opengauss-backend-tests.json`：36项在虚拟机openGauss四个隔离PG库执行，3项H2 JDBC／DDL验证，8项纯Java算法；不把47项全部说成真实数据库测试 |
| 迁移工具隔离自测 | 通过，未验证真实openGauss | `output/acceptance/migration-tool-tests.json`：H2 JDBC验证非空目标拒绝与BIGINT／布尔／空串／NULL／DATE／UTC毫秒复制 |
| 真实迁移预检 | 通过、目标未改动 | `output/acceptance/opengauss-preflight-results.json`：源V2结构、PG目标空库和附件检查 |
| 数据保留迁移 | 通过 | `output/acceptance/opengauss-migration-results.json`：21张数据表typedContentMatched=true，另1张schema_migration保留目标PG脚本校验和，共22表；16个序列恢复，6个有效文件与1个DELETED元信息保留 |
| 重复导入保护 | 按预期拒绝 | `output/acceptance/opengauss-nonempty-rejection.json`：迁移后CheckOnly返回TARGET_NOT_EMPTY；报告passed=false表示拒绝重复覆盖，不是首次迁移失败 |
| 真实数据库并发 | 已包含于上述36项 | 独立concurrency库执行5项原有并发回归；工单认领／调度互斥另在dispatch隔离库验证，明细见后端报告 |
| 真实基础HTTP | 10组通过 | `output/acceptance/opengauss-api-results.json`：登录／授权、图片、报修闭环、容量、巡检、访客、统计、CSRF及撤销 |
| 真实调度HTTP | 复跑7组通过，测试单与设置收尾完成 | `output/acceptance/opengauss-dispatch-api-results.json`：测试单17–20均关闭，两位宿管settingsRestored=true；初轮报告（本机生成：`output/acceptance/opengauss-dispatch-first-results.json`）保留旧截止时刻已过期导致自动恢复失败的实际限制，不改写首次记录 |
| Web构建／自动化 | 12项与构建通过 | 本轮测试记录（本机生成：`output/acceptance/opengauss-web-tests.txt`）；不代表openGauss UI联调 |
| Web应用内浏览器 | 7组通过 | `output/acceptance/opengauss-web-ui-results.json`：保留数据／旧附件、新图文报修、接单处理结果、原生确认同步、路线与跨端设置；最后重载控制台warning／error为空 |
| 鸿蒙构建／自动化 | 18项与构建通过 | 本轮测试记录（本机生成：`output/acceptance/opengauss-harmony-tests.txt`）；不代表openGauss设备联调 |
| 鸿蒙模拟器 | 6组通过 | `output/acceptance/opengauss-harmony-ui-results.json`：Pura90／API24，DevEco官方hdc／uitest及系统PhotoViewPicker；学生图文新建、读Web进度与确认、宿管下一站、原生RCP共享设置 |
| 运行身份与保留数据 | 同源包和真实连接核对通过 | `output/acceptance/opengauss-runtime-results.json`：8080的profile为opengauss，health数据库UP，运行JAR与target哈希相同、ERROR0；新旧附件下载200且大小／JPEG一致，源MySQL快照仍完整 |

实际联合流程为：原生student1读取迁移保留的A302入住，通过系统图库选取项目许可的公开庭院夹具、上传故障图片9并新建工单21；IAB的manager1读取同单，接单、开始、上传结果图片10并完工；原生刷新查看处理进度后确认已解决，Web再次刷新显示COMPLETED及submit／accept／start／finish／confirm五条事件。夹具不代表真实学生故障照片。Web完成截图（本机生成：`output/acceptance/opengauss-web-completed.png`）、原生完成截图（本机生成：`output/acceptance/opengauss-harmony-completed.png`）及运行证明相互对应。

原生manager1的路线显示下一站工单10／A302，同账号Web与原生仍只计1在线／1参与；原生通过RCP将本次可用截止设为2026年11月11日23:00（北京时间），Web自动同步并在重载后保留。该时刻是本轮UI主动保存的新演示设置，不是恢复2026年11月7日已过期的截止时间；保留的B405工单11仍按其旧硬预约过期原因显示待安排。宿管路线截图（本机生成：`output/acceptance/opengauss-harmony-delivery.png`）与对应UI报告记录此状态。

本轮实际安装并操作的HAP为1,733,752字节，SHA256为 `7642730312ad1e379e4027a31a467ffd8e639290895dcef408b881da8cdabafb`；不沿用11月7日旧包哈希。Java运行包为33,823,109字节，SHA256为 `6c878dd2d49c9af7c5a5616eb3feee3d9cff56a87af227dda2dd36924f9da22c`，运行副本与target一致。以上是当次交付快照，后续构建或业务操作会产生新产物和记录。

## 复现检查

以下命令会增加标记的虚构业务记录。真实openGauss自动化先按[后端说明](../backend/README.md)配置四个独立PG测试库及 `OPENGAUSS_TEST_*` 环境变量，不能指向主库。HTTP显式指定报告路径，保留此前MySQL的 `api-results.json` 和 `dispatch-api-results.json`：

```powershell
.\backend\mvnw.cmd -f backend\pom.xml test
python scripts/acceptance-api.py --report output/acceptance/opengauss-api-results.json
python scripts/acceptance-dispatch.py --report output/acceptance/opengauss-dispatch-api-results.json
npm.cmd --prefix web test
npm.cmd --prefix web run build
node --test harmony/tests/date-format.test.mjs harmony/tests/submission-keys.test.mjs harmony/tests/pending-repairs.test.mjs harmony/tests/dispatch-heartbeat.test.mjs harmony/tests/dispatch-revision.test.mjs harmony/tests/dispatch-flight.test.mjs harmony/tests/dispatch-recovery.test.mjs
.\scripts\run-harmony.ps1 -BuildOnly
```

重复验收可指定新的 `--report` 或迁移 `-ReportPath` 文件名保留当次证据。编译、H2、原MySQL和HTTP结果不能扩大为未执行的设备分支通过；本轮IAB与模拟器也仅限上述实际操作，不覆盖所有前后台或弱网组合。

## 当前边界

本轮针对单校、单实例的本机开发与VMware环境。现阶段不声明生产部署、高可用、真实手机、全量故障恢复或目标规模性能通过；真实DeepSeek调用的验证仍取决于可用账号和本机密钥，与数据库替换分别记录。
