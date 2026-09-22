# BandwidthLimiter

精确控制每位玩家的网络带宽上限，保护服务器免受带宽滥用，确保所有玩家的流畅体验。

> 基于 Netty ChannelHandler 实现的真实出站流量限制 · 支持每玩家独立配置 · Folia 原生兼容

---

## ✨ 核心功能

| 功能 | 说明 |
|------|------|
| 🔒 **每玩家带宽限制** | 为每位玩家独立设置带宽上限 (KB/s)，使用 Netty 的 `ChannelTrafficShapingHandler` 实现真实的出站流量限制 |
| 🌿 **Folia/Paper 兼容** | 使用 Paper/Folia 调度 API；Paper 26.3 已完成编译适配，运行时需使用对应的 Paper/Folia 构建 |
| ⚡ **实时热更新** | 修改配置或使用命令后即时生效，无需重启服务器。支持运行时动态调整每位玩家的带宽限制 |
| 📊 **带宽监控** | 实时查看每位玩家当前的出站带宽使用情况，通过命令随时监控服务器网络状态 |
| 🛡️ **权限系统** | 完善的权限节点设计，支持绕过带宽限制、管理员命令等多级权限控制 |
| 📁 **灵活配置** | YAML 配置文件支持全局默认值和每玩家独立限制，支持配置热重载 |

---

## 🔧 工作原理

插件通过**反射**获取每个玩家底层的 Netty `Channel`，然后在 `ChannelPipeline` 中注入 Netty 内置的 `ChannelTrafficShapingHandler`。该 Handler 通过延迟写操作来精确控制**出站带宽**（服务器→客户端），实现真实的字节级流量整形，比基于数据包计数的限制方案更加精确。

```
Player Connection Pipeline:
  ... → Encoder → [BandwidthLimiter Handler] → ... → Network
                       ↑
            ChannelTrafficShapingHandler
            (限制出站字节速率)
```

---

## 📋 命令

所有命令需要 `bandwidthlimiter.admin` 权限（默认 OP）。

| 命令 | 说明 |
|------|------|
| `/bwl set <玩家> <KB/s>` | 为指定玩家设置带宽上限 |
| `/bwl remove <玩家>` | 移除玩家的独立限制，恢复使用默认值 |
| `/bwl info [玩家]` | 查看玩家的带宽信息和实时使用状态 |
| `/bwl default [KB/s]` | 设置或查看全局默认带宽限制 |
| `/bwl list` | 列出所有在线玩家的带宽状态 |
| `/bwl reload` | 重新加载配置文件 |

> **别名:** `/bwl`、`/bandwidth`、`/bandwidthlimiter`

---

## 🛡️ 权限节点

| 权限节点 | 说明 | 默认 |
|----------|------|------|
| `bandwidthlimiter.admin` | 管理命令权限 | OP |
| `bandwidthlimiter.bypass` | 绕过带宽限制 | 无 |
| `bandwidthlimiter.info` | 查看自身带宽信息 | 所有人 |

---

## 📁 配置文件

插件首次启动时会自动生成 `plugins/BandwidthLimiter/config.yml`：

```yaml
# 默认带宽限制 (KB/s)
# 推荐值:
#   256  - 低带宽服务器
#   512  - 标准服务器
#   1024 - 高性能服务器
#   2048 - 大型服务器
default-limit-kbps: 512

# 每个玩家的独立带宽限制 (KB/s)
player-limits:
  # "069a79f4-44e9-4726-a5be-fca90e38aaf5": 1024
  # "PlayerName": 256
```

修改配置后使用 `/bwl reload` 即可热更新，无需重启服务器。

---

## 📦 安装指南

### 环境要求

- **Java** 25 或更高版本
- **服务端** Paper 26.3（`paper-26.3-32`）或与 API 兼容的 Paper/Folia 构建
- **无前置插件**依赖（不需要 ProtocolLib 等）

### 从源码构建

```bash
git clone https://github.com/ColdTearsYY/BandwidthLimiter.git
cd BandwidthLimiter
mvn clean package
```

编译产物位于 `target/BandwidthLimiter-1.1.0.jar`。

### 安装到服务器

1. 将 `BandwidthLimiter-1.1.0.jar` 放入服务器的 `plugins/` 目录
2. 启动（或重启）服务器，插件会自动生成默认配置文件
3. 根据需要修改 `plugins/BandwidthLimiter/config.yml`，使用 `/bwl reload` 热更新

---

## 🏗️ 项目结构

```
BandwidthLimiter/
├── pom.xml                          # Maven 构建配置
├── README.md
└── src/main/
    ├── java/com/bandwidthlimiter/
    │   ├── BandwidthLimiterPlugin.java   # 插件主类，生命周期管理
    │   ├── BandwidthManager.java         # 带宽管理器，反射注入 Netty Handler
    │   ├── PlayerBandwidthHandler.java   # 基于 ChannelTrafficShapingHandler 的流量整形
    │   ├── BandwidthCommand.java         # 命令处理器与 Tab 补全
    │   ├── PlayerListener.java           # 玩家加入/退出事件监听
    │   └── FoliaUtil.java                # Folia/Paper/Spigot 调度器兼容层
    └── resources/
        ├── plugin.yml                    # Bukkit 插件描述文件
        └── config.yml                    # 默认配置文件
```

---

## ⚠️ 注意事项

- 带宽限制不宜设置过低（建议不低于 **64 KB/s**），否则会严重影响玩家游戏体验
- 插件使用反射访问 NMS（`net.minecraft.server`），服务端大版本更新后可能需要适配
- 拥有 `bandwidthlimiter.bypass` 权限的玩家不受带宽限制
- 插件仅限制**出站带宽**（服务器→客户端），不限制入站流量

---

## 📄 License

本项目开源，欢迎贡献代码和提交 Issue。
