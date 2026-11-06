# 本机开发与交付

三个工程分别位于backend、web、harmony。依赖版本由各工程构建文件和锁文件管理，不用全局安装替代工程构建。

Web使用npm单一锁文件。后端使用Maven Wrapper，编译基线Java21。鸿蒙使用本机DevEco配套Node、SDK、Hvigor和ohpm，避免系统Node覆盖工具链。

MySQL使用deploy/compose.yaml，凭据来自deploy/.env；启动脚本只加载本项目环境，不修改全局PATH。容器端口限本机回环地址，数据存入独立命名卷。

验收证据写入output/acceptance，区分自动化测试、应用内浏览器观察和模拟器实跑。测试数据写入本地开发环境。只报告实际执行的结果，AI无配置时明确提示并保留手工流程。

新工具、构建错误或操作限制必须先定位实际原因；不能通过禁用鉴权、信任全部证书或删除数据规避验证。
