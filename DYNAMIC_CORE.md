# NekoBox 动态内核

此分支基于 MatsuriDayo/NekoBoxForAndroid 的 PR #1213 集成改动，将内核固定为官方 sing-box 1.14.4，加上仓库中可审查的 Neko 兼容补丁。

安装包使用独立的 applicationId `io.github.yueziji.nekobox.dynamic.debug`，不会覆盖原 NekoBox。现有节点可通过原版导出后导入。签名密钥只通过 GitHub Actions Secret 注入进程内存，不生成私钥文件。APK 与内核包均使用持久的签名身份。当前为保留调试和 CI 验证入口的预览版。

## 使用

1. 安装 `NekoBox-Dynamic-arm64-v8a.apk`。
2. 在“关于”页面打开“内核更新与回退”。
3. 在线获取 `yueziji/NekoBoxForAndroid` 的 `dynamic-core-*` Releases，或导入签名的 `nekobox-core-arm64-v8a.zip`。
4. 更新前先断开代理，下载并校验完成后点击“重启并应用”，然后重新连接。
5. 遇到运行问题，可选择“恢复内置内核”，重启后恢复。内核初始化未完成导致的进程崩溃会在下次启动时安排回退。

首次发布的动态包与内置包来自同一次构建，因此版本号相同。仍可用来验证从 APK 内置库切换到私有目录的动态库；页面会明确显示“内置”或“动态”。

## 更新契约和限制

- App 内置固定的 gomobile Java 绑定；更新包只有 `core.json`、`core.sig`、`libgojni.so`，不包含 DEX，也不执行下载的脚本。
- `core.sig` 是对原始 `core.json` 字节的 SHA256withRSA 签名。清单绑定共享库 SHA-256、Java 绑定指纹、CPU 架构、最低 Android 版本、清单格式和显示版本。
- Java 绑定指纹来自固定 gomobile 生成的全部 `.class` 的名字与内容，忽略 ZIP 时间戳。签名、绑定、架构或文件摘要不符时拒绝安装。
- 当前配置生成器已适配 1.14。后续发布者必须验证配置契约；Go 导出接口变化会使绑定指纹变化，此时需要同步升级 App。当前实现没有承诺任意未来 sing-box 大版本都可直接下载替换。
- 不支持官方任意 CLI 二进制或从其他 APK 提取的 `.so`。更新包必须由此分支对应构建流程生成并签名。
- 每个进程只加载一个版本；切换时重启主界面和后台进程，不执行 Go 运行时卸载。当前的辅助功能也使用 libcore，因此首版采用完整 App 重启。
- 核心文件保存在 `no_backup` 私有目录并设为只读；失败的下载/旧版本保留，便于检查。恢复内置内核不改变节点数据库。
- 自动回退用于初始化失败；加载成功不代表所有协议/设备都已验证。网络或配置问题可手动恢复内置版本。

## 构建

工作流 `.github/workflows/dynamic-core.yml` 在 `feature/dynamic-core` 推送时运行；本地不需要安装 Android SDK/NDK。

- 依赖源与提交固定在 `buildScript/lib/core/get_source_env.sh`。
- `buildScript/dynamic/build-core.sh` 在新的源码目录中构建并测试 Go 桥接，生成 arm64 和 x86_64 AAR。
- `prepare.py` 修改 gomobile 的库加载入口，产生绑定指纹、签名更新包和供 APK 签名使用的公有证书。
- `SignApk.java` 使用进程环境中的签名私钥完成 APK 签名，再验证产物。私钥不写入文件或命令参数。
- 仓库 Secret 为 `DYNAMIC_CORE_SIGNING_KEY`（PKCS#8 DER 的 Base64）；公钥为 `app/src/main/assets/dynamic-core/public-key.txt`。二者必须匹配。

## 验证范围

本地 JUnit 校验签名、篡改、接口、架构、Android 版本和 ZIP 路径。CI 编译 Go/AAR/APK，并在单独的 Android 模拟器中验证内置内核启动、SOCKS TCP 数据通路、签名包导入、重启后动态加载和恢复内置内核。自动更新还验证旧 APK 导入并加载候选内核，以及 GUI 配置生成器生成的 SOCKS、Shadowsocks、VMess、VLESS、Trojan、Hysteria2 配置（分别关闭/开启 FakeDNS）；配置构造检查不等于真实节点连接测试。

模拟器只在 CI 创建，禁止对用户正在运行的模拟器执行此测试。arm64 手机 VPN、实际代理节点、IPv6 和 Wi-Fi/蜂窝切换需要另行验收；具体已完成项目以交付记录和 CI 结果为准。

## 自动跟进官方稳定版

默认分支 `main` 的 `.github/workflows/core-watch.yml` 是调度入口，默认每天北京时间 **10:17** 检查一次，复用 `feature/dynamic-core` 的构建工作流。默认分支原有 App 代码不参与动态内核构建。

1. 从官方 Releases 的 `latest` 接口取得稳定版，排除 alpha / beta / RC；将 tag 解析为不可变源码提交。
2. 与已有动态内核 Releases 比较，已发布或更旧的版本跳过构建。没有新版时只运行轻量检测。
3. 自动应用四个兼容补丁，版本号由 linker 注入；源码提交和版本共同进入原生缓存键，防止误用旧缓存。
4. 固定检查 `compatibility.json` 中已发布 App 的 Java 绑定指纹。原先的 x86_64 APK 作为 CI 基线保存在首版 Release，并校验固定 SHA-256；它仅供 CI 使用。
5. 所有编译、单元测试、旧 APK 加载、配置兼容及模拟器运行测试通过后，发布 `dynamic-core-版本-auto.1`，包含两个架构的签名内核包、源码记录和摘要文件。App 沿用现有更新入口，无需为此调度功能重装。
6. 发布先创建草稿，上传完整后才公开。补丁冲突、接口指纹变化、测试失败或上传失败均停止发布，保持当前可用 Release。不覆盖已有 Release；残留草稿需人工检查。

在 GitHub Actions 的 **Follow sing-box stable → Run workflow** 可手动检查。勾选 `verify_current` 会强制构建当前官方稳定版并完成全部检查及发布材料生成，但不会公开新 Release，适合演练。失败原因见对应 Actions 运行和 GitHub 的工作流失败通知设置；本流程不发送额外消息。

官方版本不是任意未来版本都保证兼容，自动化只能在通过上述检查后发布。测试失败需要维护者调整兼容补丁或升级 App，不能通过跳过检查来继续发布。

GitHub 定时任务可能延迟，公开仓库连续 60 天无仓库活动可能被平台自动暂停；遇到此情况需在 Actions 重新启用该工作流。[GitHub 调度规则](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)。
