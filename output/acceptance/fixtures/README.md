# 鸿蒙图库上传验收夹具

此图片是项目已许可的公开庭院示意图副本，不含学生资料，不代表真实故障。

- 原始项目文件：`design/assets/images/campus-courtyard.jpg`。
- 许可与摄影者：Nick Allen，CC BY-SA 3.0；完整记录见`design/assets/images/SOURCES.md`。
- 文件大小：280991 bytes。
- SHA-256：`d2132fc7c2670bf58ba8ee285554c3ef4e6537e3127944ebe9c275a2211caedf`。
- 模拟器：Pura 90，目标`127.0.0.1:5555`，API24。

按照华为官方[mediatool说明](https://developer.huawei.com/consumer/en/doc/harmonyos-guides/mediatool/)的「Importing Media Library Files」使用API21起支持的HDC媒体路径导入：

```powershell
$hdc = 'C:\Program Files\Huawei\DevEco Studio\sdk\default\openharmony\toolchains\hdc.exe'
& $hdc -t 127.0.0.1:5555 file send 'D:\CodingProjects\HD-Project\output\acceptance\fixtures\dormlink-fixture-courtyard-20261107.jpg' /mnt/data/100/media_fuse/Photo/
& $hdc -t 127.0.0.1:5555 shell mediatool query dormlink-fixture-courtyard-20261107.jpg -u
```

本次返回`FileTransfer finish, Size:280991, File count = 1`，查询从0条变为1条，URI为`file://media/Photo/1/IMG_1793991295_000/dormlink-fixture-courtyard-20261107.jpg`。设备重置后URI会变化，以实际查询为准。

此导入使用系统支持的媒体服务路径，没有修改媒体数据库、文件权限或隐私授权，也没有切换前台应用。后续验收由用户实际选择PhotoViewPicker中的这张图片，不能把导入成功等同于上传流程通过。
