# Phase 1 官方实现基线参考

更新时间：2026-03-08

## Micronaut
- 官方指南：<https://docs.micronaut.io/latest/guide/>
- 关注章节：
  - `4.1 The Environment`：Micronaut 支持通过环境名加载 `application-{environment}` 配置文件，适合本项目的 `application-local.yml`。
  - `15.4 Micronaut for GraalVM`：Micronaut 对 GraalVM Native Image 有专门支持，适合作为 `app` 模块 native 打包入口的基线。
- 在本项目中的落点：
  - 使用 Micronaut environment profile 管理本地依赖与 TLS 自签名配置。
  - 保持 `@Factory` + `@Requires` 风格的装配方式，避免把运行时依赖硬编码到业务层。

## Netty io_uring
- 官方仓库：<https://github.com/netty/netty-incubator-transport-io_uring>
- 关键信息：仓库已归档，README 明确说明 io_uring 支持已并入 Netty `4.2` 分支。
- 在本项目中的落点：
  - 现阶段仍保留当前 `4.1.x` + incubator artifact 的实现方式。
  - 运行时继续采用“优先 io_uring，失败后回退 epoll/NIO”的反射探测方案，避免把启动路径绑定到单一 native transport。
  - 后续若升级 Netty `4.2`，应优先移除独立 incubator 依赖并重新验证 transport 初始化逻辑。

## RocketMQ
- 官方 Java SDK 文档：<https://rocketmq.apache.org/docs/sdk/02java/>
- 关键信息：官方 5.0 Java SDK 页面说明其示例基于 gRPC 协议，并要求服务端至少升级到 5.0 且启用 gRPC Proxy；如果使用 Remoting SDK，建议参考 4.x 时代示例。
- 在本项目中的落点：
  - 当前项目依赖 `org.apache.rocketmq:rocketmq-client`，仍属于经典 Remoting 客户端使用方式。
  - Phase 1 继续沿用现有同步刷盘、有序发送、消费后事务提交的 Remoting 路径，不在本轮切换到 gRPC Proxy 架构。
  - 若后续需要切换到官方 5.x gRPC SDK，应先补 Proxy 部署与端到端联调方案。

## GraalVM Native Image
- 官方文档：<https://www.graalvm.org/latest/reference-manual/native-image/>
- 关键信息：
  - 官方 Gradle 入口命令是 `./gradlew nativeCompile`。
  - 生成物默认位于 `app/build/native/nativeCompile/`。
  - 构建依赖本机 `native-image` 工具与系统 C toolchain。
- 在本项目中的落点：
  - `app/build.gradle.kts` 继续使用 `org.graalvm.buildtools.native` 插件。
  - 当前构建脚本在缺少 `native-image` 时会跳过 `nativeCompile`，这保证了常规 CI/本地 JVM 开发不被阻塞。
  - 真正关闭 OpenSpec `8.6` 前，仍需在具备 GraalVM toolchain 的环境里跑一次未跳过的 `:app:nativeCompile`。

## 结论
- 本轮实现基线选择为：Micronaut environment profile + Netty io_uring fallback + RocketMQ Remoting producer/consumer + GraalVM Gradle plugin。
- 这些参考足以支撑当前仓库里的本地开发、运行时装配和后续 native/transport 升级判断。
