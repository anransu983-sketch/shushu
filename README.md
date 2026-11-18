# 薯薯 Shushu

一只点阵仓鼠陪你看应用在干什么：无 Root 也能看权限访问和网络活动。

借鉴 [ybblab/Jiumi](https://github.com/ybblab/Jiumi) v0.2.3 的架构重做，UI 全换琳式（玻璃面板＋悬浮底栏＋点阵拼豆仓鼠图标）。只观察、不干预：不注入应用、不拦截或修改调用、不代理网络流量；数据只在本机。

- 监测：无线调试 / Root / Shizuku 三路接入，shell 身份探针读 AppOps、连接表、Perfetto、事件日志
- 时间线：按天折叠，全部 / 仅后台筛选
- 需要注意：后台读敏感数据、后台发送、频繁访问、反复被拒
- 应用列表与详情：行为、目的地、系统历史、唤醒次数
- 应用管理：权限开关、单应用断网、停用 / 卸载 / 恢复（备份只保 APK、不保数据）

域名记录不做：它要占系统唯一 VPN 位，会顶掉代理。

## 构建

```sh
cd code && ./gradlew :app:lintDebug :app:assembleDebug
```

JDK 17，compileSdk / targetSdk 35，minSdk 30。

## 许可

Apache License 2.0，见 `LICENSE` 与 `NOTICE`。上游版权归 ybblab，改动见各文件 SPDX 头。
