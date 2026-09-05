# Neogenesis Vulkan 渲染器待办与演进路线 (Roadmap / TODO)

## 待实现特性与长期演进

### 1. 反向 Z 深度缓冲 (Reversed-Z Depth Buffer)
- **目标场景**：超远视距渲染、现代光影管线、极小近裁切面无穿模支持。
- **技术要点**：
  - 深度清除值设为 `0.0f`（原为 `1.0f`）。
  - 管线深度比较操作设为 `VK_COMPARE_OP_GREATER_OR_EQUAL`（原为 `LESS_OR_EQUAL`）。
  - 调整透视投影矩阵参数：近平面映射至深度 1.0，远平面映射至深度 0.0（支持无穷远远裁切面 $z_{far} = \infty$）。
- **预期收益**：
  - 解决千米级别远景地形接缝与水体边缘的 Z-fighting。
  - 为屏幕空间反射 (SSR)、环境光遮蔽 (SSAO)、深度重构世界坐标等后处理提供全距离均匀精度的深度图。

---

### 2. 超远视距支持 (Extended / Infinite Render Distance & LOD)
- **目标场景**：支持 32 ~ 128+ Chunks 视距、大尺度地形宏观展现（类似 Distant Horizons / Sodium Extra）。
- **技术要点**：
  - 构建低多边形远景分块 (LOD Chunks) 网格生成系统。
  - 结合 Reversed-Z 消除超大场景远景深度精度崩塌。
  - 引入基于 Compute Shader 或 Vulkan 间接绘制 (`vkCmdDrawIndexedIndirect` / Multi-Draw Indirect) 的视锥与遮挡剔除 (GPU Driven Culling)。

---

### 3. 光影与现代着色器管线 (Modern Shader & Post-Processing Pipeline)
- **目标场景**：现代实时光影、PBR 材质、动态全局光照与大气渲染。
- **技术要点**：
  - **延迟/前向增强渲染架构**：G-Buffer 布局（Albedo, Normal, Material/Roughness/Metallic, Depth）。
  - **动态阴影**：级联阴影贴图 (Cascaded Shadow Maps, CSM) 或光线追踪阴影 (Ray Tracing Shadows / VK_KHR_ray_tracing)。
  - **基于深度的后处理**：
    - 屏幕空间环境光遮蔽 (SSAO/GTAO)。
    - 屏幕空间反射 (SSR)。
    - 大气散射与基于物理的体积雾 (Volumetric Fog / Skybox Atmosphere)。
    - HDR 色调映射 (Tone Mapping) 与泛光 (Bloom)。

---

### 4. 动态图集与动画纹理同步 (Animated Atlas Synchronization)
- **目标场景**：水流、岩浆、火焰等动态方块贴图在 Vulkan 端的实时帧刷新。
- **技术要点**：
  - 监听 `TextureMap` 的 tick 动画更新事件。
  - 使用 Staging Buffer 增量上传更新至 Vulkan Atlas Image（结合 `vkCmdCopyBufferToImage` 针对脏子区域进行轻量拷贝）。
