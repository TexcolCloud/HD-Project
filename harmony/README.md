# 寓见鸿蒙应用

原生ArkTS / ArkUI Stage工程，一个entry HAP按登录账号返回的角色展示学生或宿管界面。没有WebView、内存假接口或AI模拟回复。设计沿用已批准的暖白／暖黑方案。

## 构建与运行

在项目根目录执行：

```powershell
./scripts/run-harmony.ps1 -BuildOnly
./scripts/run-harmony.ps1
```

脚本使用本机`C:\Program Files\Huawei\DevEco Studio`配套Node、SDK、Hvigor，先从共享OpenAPI生成ArkTS接口，再构建HAP。当前锁定modelVersion 6.1.1、SDK `6.1.1(24)`；模拟器验收目标为已安装的Pura 90。首次设备使用需先启动模拟器。

- Bundle：`com.dormlink.residence`。
- Ability：`EntryAbility`。
- 产物：`entry/build/default/outputs/default/entry-default-unsigned.hap`。
- 本机模拟器可安装该调试未签名包；真机和正式发布需要独立签名配置。

后端应先运行。模拟器通过`hdc rport tcp:8080 tcp:8080`连接宿主机服务，默认API地址`http://127.0.0.1:8080/api/v1`；登录页的「服务连接设置」可改为局域网或HTTPS服务地址。修改服务地址后应退出当前会话重新登录。

开发种子账号为`student1`／`student2`／`student3`和`manager1`／`manager2`，密码见根目录交付说明与`contracts/API.md`。账号角色、本人数据和楼栋范围均由后端决定。

## 已接入业务

动态调度、学生可上门时段与管理AI的新模块划分、生命周期及约束见[FEATURES.md](FEATURES.md)。宿管首页「本次路线」进入独立路线页；首页「值班简报」和工单「AI研判」为只读辅助。

- 学生：当前宿舍与入住历史、图文报修、紧急原因、联系时段、分类与优先级、AI建议主动调用及采纳、本人工单筛选／分页／时间线、取消／确认／未解决反馈、本人入住期间卫生记录、资料与改密。
- 宿管：真实工作摘要、访客登记／筛选／分页／离访／按需查看联系信息、现场四项卫生评分与图片、可补录的实际检查日期和北京时间、巡检记录与关联复查、工单查询／接单／开始处理／追加说明／完工凭证／驳回／返修。
- 附件：系统PhotoViewPicker仅访问主动选择的照片；解码缩放并重编码为JPEG以移除EXIF，逐张上传，显示成功或失败状态；失败图片保留本地预览，可重试或移除，也可删除未提交附件。后端附件通过Bearer鉴权读取，不使用公开图片地址。
- 会话：MOBILE登录返回Bearer。令牌只保留于内存，不写偏好设置或日志；退出、401及改密清除界面数据。服务地址和主题可保存在应用私有偏好设置中。
- 表单：同次未决提交网络重试复用按业务范围保存的幂等键，内容变化重新生成，成功后释放，下一次相同内容也作为新的操作；操作中禁用重复提交；主题切换与网络失败保留当前表单内容。正式业务提交需要网络连接。

卫生记录的带原因更正和作废在Web管理端完成；移动端聚焦现场录入和复查。访客到访时间默认由保存时服务端记录。

AI依赖后端配置的真实供应商。未配置、超限、超时或失败会显示可读原因并允许手动提交，不返回假建议；客户端不含模型密钥。

## 契约、资产与验证

`generate-models.py`从`../contracts/openapi.json`生成`entry/src/main/ets/models/ApiModels.ets`。改变API后重新生成，不手工改生成文件。ArkTS不支持字符串字面量联合类型，因此枚举字段生成string并由共享契约及后端校验取值。

中文展示字体来自已许可Noto Serif SC子集，内部family为Residence Editorial；为原生字体注册转为TTF，未修改版权及许可。`entry/src/main/resources/rawfile/OFL-Noto-Serif-SC.txt`保留OFL。庭院摄影署名在学生首页与「图片来源」页面可读，完整来源随包保存在`IMAGE-SOURCES.md`。照片不代表本平台服务学校的真实宿舍。

使用系统Node 24可执行`node --test harmony/tests/date-format.test.mjs harmony/tests/submission-keys.test.mjs harmony/tests/pending-repairs.test.mjs`，回归检查直接读取纯ArkTS日期函数，确认UTC设备也按学校时区显示、补录时刻携带+08:00，以及成功后换键／失败重试保留、旧未结工单不被新完成记录挤出首页；该检查不代替模拟器测试。

ArkUI的`@Builder`默认按值传参，不能依赖替换后的State刷新旧参数。表单与记录片段使用单对象字面量`$$`按引用参数；上传区和工单详情直接读取当前State。API重新加载的记录使用ID和version组成列表键。相关规则依据[华为@Builder说明](https://developer.huawei.com/consumer/en/doc/harmonyos-guides/arkts-builder/)，响应性仍须在设备验证。

当前开发配置允许HTTP用于本机联调，发布环境应使用HTTPS。模拟器测试证据和业务验收结果统一记录在根目录交付文档，构建成功不能代替设备交互验证。
