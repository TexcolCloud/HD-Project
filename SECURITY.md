# 安全策略

## 支持范围

安全修复以受保护的 `main` 分支为基线，请在报告中注明受影响的提交或构建版本。

## 私下报告漏洞

通过 TexcolCloud/HD-Project 的 GitHub Security Advisories 选择 **Report a vulnerability**，私下说明影响范围、所需权限及脱敏后的复现条件。请勿在公开 Issue、PR、截图或日志中披露可利用细节、真实凭据或学生身份信息。[提交私密安全报告](https://github.com/TexcolCloud/HD-Project/security/advisories/new)

若入口未开放，可在仓库 Issue 中仅请求维护者提供私下联系渠道。收到私密渠道后再发送报告。维护者可在仓库 Security Advisories 中建立私有草案，协作处理并协调披露。[仓库安全公告](https://github.com/TexcolCloud/HD-Project/security/advisories)

## 凭据与部署

`manager1`、`student1` 等课程演示账号及 `.env.example` 中的占位配置仅供隔离的本地开发和测试。生产部署应配置独立账号和密钥，启用 HTTPS、设置 `COOKIE_SECURE=true`，并将数据库限制在私有网络内。若凭据已披露，请立即撤销或轮换，并在私密报告中说明影响范围。[账号与恢复运行手册](docs/17-账号与恢复运行手册.md)
