# 来源与第三方组件

本工程针对以下项目的 Windows v7 行为重新实现 Android 手机运行端（0.2.0 起使用 Shizuku）：

- [h0ll0w-AkuZr0guY/tmall-miao-double11](https://github.com/h0ll0w-AkuZr0guY/tmall-miao-double11)，标签 `v20261019-double11.7`，提交 `50a3fc5efeab5d6d45786cbfd2c26ed3aa34db5a`。
- 上述项目基于 [MonsterNone/tmall-miao](https://github.com/MonsterNone/tmall-miao) 的 `next` 分支，来源基准 `9318d3d138d5fa53cff00601740de012da831c65`。

`TaskRules.java` 的任务行几何配对、分类、任务身份及部分测试场景参考并改编自 v7 的 `modules/task_model.py`、`modules/campaign.py` 和 `tests/test_campaign.py`。其余 Android 界面、原无障碍执行端、现 Shizuku 接入、截图、控制和日志代码为本次移植新增。感谢原作者和贡献者。

所检查的 v7 仓库未提供 LICENSE。其 `ATTRIBUTION.md` 也指出所检查上游未提供 LICENSE；本工程保留来源及权利归属，不擅自为原项目代码声明 MIT、Apache 等许可。再分发相关代码时仍需确认原作者授权。

APK 包含 Google ML Kit 中文文字识别 `com.google.mlkit:text-recognition-chinese:16.0.1` 及其传递依赖；模型内置，识别在设备上完成。

- [ML Kit Android 文档](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [ML Kit 条款](https://developers.google.com/ml-kit/terms)
- 各依赖原有的许可和通知保留于其 AAR/JAR 及 APK 中的 `META-INF` 内容（以组件实际提供为准）。

构建工具：Android Gradle Plugin 8.7.3、Gradle 8.9、Android SDK 35、JDK 17。它们不打入 APK。

## Shizuku 版新增组件

- `dev.rikka.shizuku:api:13.1.5` 与 `dev.rikka.shizuku:provider:13.1.5`，来自 [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API)，MIT License，Copyright (c) 2021 RikkaW。完整许可随 APK 放在 `assets/licenses/Shizuku-API-MIT.txt`。
- 用户服务 / AIDL 生命周期按 [官方 API 文档](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md) 接入。0.2.0～0.2.1 使用过 InputManager 反射，0.2.2 已移除。当前命令参数依据 [AOSP Android 13 InputShellCommand](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-13.0.0_r1/services/core/java/com/android/server/input/InputShellCommand.java)、[Android 16 InputShellCommand](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/core/java/com/android/server/input/InputShellCommand.java) 与 [Android 11 Input](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-11.0.0_r1/cmds/input/src/com/android/commands/input/Input.java) 核对。助手未打包系统输入实现或引入隐藏 API 访问工具。
- 本 APK 不包含 Shizuku 管理器，用户需从官方来源另行安装并授权。

## 0.2.4 图标与入口

卡通金币由 imagegen 生成，当前资源说明见 docs/ICON.md；桌面单色图形为本项目资源。固定淘金币入口指向淘宝官方页面，未抓取账户 Cookie 或调用奖励接口。

## 0.2.7 中文输入与验证检测

临时中文输入按 [Android 输入法开发文档](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method) 使用 InputMethodService、InputConnection.commitText 与编辑器搜索动作。切换命令与用户参数对照 [AOSP Android 16 InputMethodManagerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/core/java/com/android/server/inputmethod/InputMethodManagerService.java) 及 [SettingsService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/packages/SettingsProvider/src/com/android/providers/settings/SettingsService.java) 核对，只调用系统命令，未复制或打包这些系统实现。

验证检测只读取离线 OCR 的页面文字后暂停，不包含图片题目求解、自动拖放作答或提交逻辑。

## 0.2.9 识别负载优化

浏览图像尺寸和处理频率的调整参考 [ML Kit Android 输入图像与性能建议](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)。可修改截图使用 Android BitmapFactory.Options.inMutable 与 ARGB_8888，未加入新的识别依赖、权限或温控暂停组件。
