# KsuWebUI Plus

[KernelSU WebUI](https://kernelsu.org/guide/module-webui.html) 的独立 App，基于
[5ec1cff/KsuWebUIStandalone](https://github.com/5ec1cff/KsuWebUIStandalone) 二次开发，
增加了模块桌面快捷方式、图标自定义与 One UI 风格界面。

适用于 **Magisk / KernelSU / APatch**。

> KsuWebUI Plus is a standalone WebUI client for KernelSU / Magisk / APatch modules,
> forked from [KsuWebUIStandalone](https://github.com/5ec1cff/KsuWebUIStandalone).
> It adds per-module home screen shortcuts, custom icons and names, and a One UI
> inspired interface. Licensed under GPL-3.0.

## 下载

已签名 APK 见 [Releases](https://github.com/fdxqn/KsuWebUI-Plus/releases)，文件名形如
`KsuWebUI-Plus-1.0-1.apk`。

- 与官方 KsuWebUI 包名相同、签名不同，安装前请先卸载官方版本，否则会提示签名冲突
- 首次启动需要授予 Root 权限
- 安装后应用名为 **KsuWebUI Plus**

## 功能

- 读取已安装模块，直接打开模块 WebUI；支持启用/禁用、模块信息查看
- **桌面快捷方式**：长按模块卡片或点 ⋮ → 添加到桌面，点击桌面图标直接进入该模块 WebUI，不再经过应用主界面
- **图标与名称自定义**
  - 图标来源：模块图标 / 自选图片 / 纯色
  - 图标文字：自动缩放，不溢出图标
  - 背景色：预设色板或十六进制输入
  - 图片裁切：双指缩放、拖动定位，圆角裁切框带细描边
- **与桌面显示一致**：按启动器自适应图标规则（画布中央 72/108 安全区）合成，桌面图标即编辑器预览
- **桌面快捷方式管理页**：编辑、从桌面移除、重新添加
- **One UI 风格界面**：圆角卡片、分段控件、悬浮菜单与设置卡，深浅色跟随系统
- 模块 WebUI 背景延伸到状态栏，正文保持在状态栏下方；状态栏图标明暗随背景切换
- 模块列表图标缓存，进入应用不闪图标

## 构建

需要 JDK 21 与 Android SDK 35：

```bash
./gradlew assembleDebug
```

发布签名放在 `keystore.properties`（已在 `.gitignore` 中，不随仓库分发）：

```properties
storeFile=/path/to/key.jks
storePassword=******
keyAlias=******
keyPassword=******
```

不配置时 release 会使用 debug 签名。

## 兼容性

- Android 8.0（API 26）及以上
- 桌面快捷方式依赖启动器的 pin 快捷方式支持；部分定制 ROM 需要额外授权或手动确认
- 应用包名与上游一致（`io.github.a13e300.ksuwebui`），自行构建的包无法覆盖上游签名版本安装

## 目录

```
app/                         应用模块（Kotlin + ViewBinding）
app/src/main/java/.../shortcut   快捷方式模型、存储、图标渲染与 pin 控制
app/src/main/java/.../ui         编辑器、快捷方式管理页、菜单与设置
app/src/main/res/layout          页面与卡片布局
```

## 致谢

- 上游项目：[5ec1cff/KsuWebUIStandalone](https://github.com/5ec1cff/KsuWebUIStandalone)
- KernelSU WebUI 文档：<https://kernelsu.org/guide/module-webui.html>
- 本分支维护：酷安 @l奋斗的小青年

## 许可

GPL-3.0，见 [LICENSE](LICENSE)。二次分发请保留原作者版权声明与许可证。
