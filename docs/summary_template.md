## 9. 五大业务功能落地评估

从产品业务视角评估各功能的真实完成度。重点关注是否接入了真实数据/服务，而非仅从代码行数或 UI 完整性判断。

### 9.1 导航功能

**落地结论：仅完成地图静态展示，导航功能未实现。**

| 维度 | 状态 | 详情 |
|------|------|------|
| 高德 SDK 集成 | ✅ 已完成 | SDK 初始化在 MyApplication 中完成，AndroidManifest 配置了 API Key，权限声明完整（定位、网络、WiFi） |
| 地图渲染 | ✅ 已完成 | MapView 嵌入 `activity_main.xml`，生命周期（onCreate/onResume/onPause/onDestroy/onSaveInstanceState）完整处理 |
| 用户入口 | ✅ 已完成 | 主界面底部 Dock 栏有"导航"按钮，点击后 `mapView.setVisibility(VISIBLE)` 显示地图 |
| 路径规划 | ❌ 未实现 | 无 RouteSearch、无起终点设置、无任何路线规划 API 调用 |
| 实时导航 | ❌ 未实现 | 无导航引导、无语音播报、无 TBT（Turn-by-Turn）逻辑 |
| GPS 定位 | ❌ 未实现 | 虽声明了定位权限，但代码中未使用 AMap LocationClient 或任何定位逻辑 |
| 关闭/隐藏 | ✅ 已完成 | 点击`主页`按钮会执行 `mapView.setVisibility(GONE)` 隐藏地图 |

**总结**：地图仅用于静态展示，用户点击"导航"后看到的是高德默认的北京总部地图（MapView 默认位置），无法搜索目的地、无法规划路线、无法获取实时位置。要变成真正的车载导航，需要集成 AMap 的 Search SDK 和 Navigation SDK，并实现完整的搜索→选路→导航→引导流程。

---