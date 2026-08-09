# MyRoutin

MyRoutin 是一个 Android 订阅额度查询工具，用于集中查看 Routin `plan-` API Key 对应的套餐、周期额度、资源包余额、到期时间和可用模型信息。

## 功能

- 支持添加、命名、排序和删除多个订阅 Key
- 支持一键刷新全部已保存的 Key
- 展示套餐名称、状态、开始与到期时间
- 展示日/周周期额度、已用额度、剩余额度及窗口结束时间
- 展示资源包 Token 用量、可用模型和分组倍率
- 在刷新失败或离线时保留最近一次成功查询的缓存结果
- 页面只展示脱敏后的 Key

## 下载与安装

前往 [Releases](https://github.com/huangssh/MyRoutin/releases/latest) 下载最新版 APK。

系统要求：Android 7.0（API 24）及以上。

首次从 GitHub 安装时，系统可能要求允许浏览器或文件管理器安装未知来源应用。若设备中已安装旧的 debug 测试包，请先卸载后再安装首个正式版。

## 更新模型雷达内置快照

APK 会内置一份模型雷达聚合快照，供无法访问 CodexRadar 且没有历史缓存的用户离线查看。发布新版本前，在已安装 debug 包的设备上打开模型雷达并手动刷新成功，然后执行：

```bash
python3 tools/update_model_radar_asset.py
```

脚本只导出 App 已聚合的页面字段，校验推荐、模型、档位和近 24 小时指标后更新 `app/src/main/assets/model_radar_snapshot.json`，不会把 CodexRadar 原始任务明细打入 APK。

## 反馈与贡献

欢迎通过 [Issues](https://github.com/huangssh/MyRoutin/issues) 提交功能建议和问题反馈。

涉及 API Key、签名私钥或其他敏感信息的安全问题，请不要在公开 Issue 中提交。

## 开源许可证

本项目采用 [Apache License 2.0](LICENSE) 发布。

Copyright 2026 huangssh
