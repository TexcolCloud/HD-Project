# 本机开发与交付

三个工程分别位于backend、web、harmony。依赖版本由各工程构建文件和锁文件管理，不用全局安装替代工程构建。

Web使用npm单一锁文件。后端使用Maven Wrapper，编译基线Java21。鸿蒙使用本机DevEco配套Node、SDK、Hvigor和ohpm，避免系统Node覆盖工具链。

后端启动脚本默认连接VMware／openEuler上的openGauss，配置来自被忽略的deploy/.env.opengauss，模板为deploy/opengauss.env.example；SSH管理凭据不传给应用进程。使用项目独立PG兼容库、独立DDL及迁移校验和。旧MySQL的deploy/compose.yaml、deploy/.env与命名卷保留为迁移源，只有显式-Database mysql才使用旧库。脚本只加载本项目进程环境，不修改全局PATH。

数据库替换先保留源库和附件、停止源API写入，再向空目标业务表迁移；不删库、不禁用外键、不用种子重建替代保留历史。目标已有业务行须拒绝覆盖。报告只保存行数、摘要和验证结果，不包含凭据或业务明文；H2与旧MySQL结果不能冒称真实openGauss通过。

验收证据写入output/acceptance，区分自动化测试、应用内浏览器观察和模拟器实跑。测试数据写入本地开发环境。只报告实际执行的结果，AI无配置时明确提示并保留手工流程。

新工具、构建错误或操作限制必须先定位实际原因；不能通过禁用鉴权、信任全部证书或删除数据规避验证。
