# Neogenesis Vulkan 渲染器待办与演进路线 (Roadmap / TODO)

## 待实现特性与长期演进

### 1. 反向 Z 深度缓冲 (Reversed-Z Depth Buffer) ✅ 已完成（a198e62，2026-09-06）
- **实现状态**：已随 W5-1 收尾从调试 stash 恢复落地。
  - 深度清除值 `0.0f`；透视矩阵 near/far 互换（near→1.0，far→0.0）。
  - 深度比较采用**严格 `VK_COMPARE_OP_GREATER`**（首绘者胜出），而非原计划的 `GREATER_OR_EQUAL`：GEQUAL 是"末绘者胜出"，共面片元的胜负会随绘制顺序翻转（正是 W5 闪烁的病灶模式）；配合可见分块按坐标确定性排序，平局胜者与视角完全无关。
  - 半透明管线 depthWrite 恒为 true（与原版 1.8.9 半透明地形不关深度写一致）。
- **遗留收益空间**：TODO 3 的后处理（SSR/SSAO/深度重构）自此拥有全距离均匀精度深度图；`z_far = ∞` 无穷远裁切面优化未做，可另行迭代。

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

---

### 5. 编译警告清零 (Compiler Warning Cleanup) —— **最低优先级**
- **现状**（2026-09-06）：`./gradlew classes` 共 **66 个警告**，全部为历史遗留噪音，不影响功能。
- **构成**：
  - 使用或覆盖了已过时的 API（deprecation）——MC 1.8.9 反混淆源码大量 `I18n`/`ResourcePack`/GL 遗留调用，能修的加 `@Deprecated` 同款迁移，修不动的按文件 `@SuppressWarnings("deprecation")`；
  - 未经检查或不安全的操作（unchecked）——为泛型集合补全类型参数，或 `@SuppressWarnings("unchecked")` 局部化；
  - 重编译时建议追加 `-Xlint:deprecation,Xlint:unchecked` 输出明细，按包分批清理（`net.minecraft` 与 `net.theresa` 分开处理，`net.theresa` 自研代码优先零警告）。
- **注意**：纯清理类改动，严禁夹带任何行为变更；每批清理后必须 `./gradlew classes` + 游戏内冒烟。
