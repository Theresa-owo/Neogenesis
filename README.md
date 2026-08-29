# Neogenesis

基于 Minecraft 1.8.9 + OptiFine 的一体化客户端（Kotlin / Java 混合），Gradle 构建，运行于 LWJGL 3.4.3。

## 构建

需要 JDK 21（推荐 Amazon Corretto 21）。

```bash
./gradlew build        # 产物: build/libs/MeowClient-5.0.0.jar
./gradlew runClient    # 直接启动客户端（游戏目录 .minecraft/）
```

## 特性

- OptiFine 光影（shaders）管线完整编译并启用
- netty 4.2 / guava / LWJGL 3.4 全最新依赖栈适配
- 无边框全屏（切换零黑屏）
- 内置模块化客户端（缩放、自由视角、聊天增强、实体/粒子剔除等）

## 许可证

[GPL-3.0](LICENSE)
