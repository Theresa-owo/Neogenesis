# Vulkan 地形渲染闪烁问题根因复盘与技术规范

## 1. 现象与特征
- **受影响图元**：草（Tall Grass）、花（Flowers）、梯子（Ladders）、单层雪（Snow Layer）、树叶内部（Leaves Interior）等带有透空或贴面特征的方块。
- **不受影响图元**：石头、泥土、木头等常规六面实心方块（SOLID）。
- **动态表现**：当玩家静止不动时可能看不出异常；一旦移动鼠标旋转视角或走动，受影响方块表面发生高频交替闪烁，视觉特征表现为“贴图被随机换成了图集里的其他 Tile”或“深度层级互相交错撕裂”。

---

## 2. 核心根因技术分析

### (1) Minecraft 方块模型的构建机制
在 Minecraft 原版模型系统（如 `cross.json` 用于十字交叉植物、`ladder.json` 等）中：
- 草与花由两片互相垂直交叉的 3D 平面构成。
- 为了在开启单面渲染时从正反两面都能看到植物，原版模型生成器在**完全重合的同一个零厚度几何平面上同时烘焙了 Front Quad 与 Back Quad**（即两个方向相反的面，顶点绕序相反，分别具有各自独立的法线、UV 映射与着色计算）。
- 树叶内部、单层雪贴地面以及梯子贴墙面同样存在重叠/共面 Quad。

### (2) 原版 OpenGL 的运行状态
在原版渲染管线（`EntityRenderer.java:1323`）中：
- 渲染世界地形前全局调用 `GlStateManager.enableCull()` 开启硬件背面剔除（`GL_CULL_FACE`, `GL_BACK`）。
- 仅在绘制半透明水面（`TRANSLUCENT`）时调用 `disableCull()`。
- **效果**：对于草等双面模型，硬件光栅化器（Rasterizer）在图元组装阶段直接丢弃背面 Quad，**同一像素位置永远只有朝向相机的单层 Quad 被光栅化并执行像素着色和深度测试**。

### (3) Vulkan 移植中的破坏机制
在 Vulkan 管线早期配置（`VulkanRenderer.java`）中，错误地将剔除模式设为：
```java
.cullMode(VK10.VK_CULL_MODE_NONE)
```
- **引发的故障链**：
  1. 背面剔除被彻底关闭后，草等模型的 Front Quad 和 Back Quad **同时被 GPU 光栅化**。
  2. 两个 Quad 的三维顶点完全重合，理论深度值完全相等。
  3. 当视角发生微小的旋转或位移时，投影变换与光栅化浮点精度的末位微小扰动会导致两个共面 Quad 的屏幕空间深度值发生交替领先（Tie-breaking 竞争）。
  4. 深度测试每一帧交替让正面或反面像素胜出并覆盖深度缓冲。由于正反面的 UV / 光照朝向不同，直接在屏幕上呈现为**剧烈的贴图高频跳变与闪烁**。

---

## 3. 修复方案与绕序对齐规范

### (1) 启用硬件背面剔除
在地形管线创建中（`VulkanRenderer.java` -> `buildTerrainPipeline`）：
```java
rasterizationState
    .cullMode(VK10.VK_CULL_MODE_BACK_BIT)
    .frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE);
```

### (2) 绕序对齐细节（FrontFace）
- Minecraft 原版烘焙的 Quad 顶点顺序为逆时针（CCW）。
- 在 Vulkan 投影矩阵中，由于 Vulkan 屏幕空间 Y 轴向下，管线通常采用 `proj[1][1] *= -1` 反转 Y 轴。
- 结合三角形拆分规则（`0,1,2,0,2,3`），最终应将 `frontFace` 设为 `VK_FRONT_FACE_COUNTER_CLOCKWISE`。
- **验证对照**：
  - 若误设为 `VK_FRONT_FACE_CLOCKWISE`：实心方块的正面会被剔除，导致方块“内部朝外/渲染成背面”；而草因为有反面替补所以草能正常显示。
  - 设为 `VK_FRONT_FACE_COUNTER_CLOCKWISE`：实心方块正面正常显示，草等双面模型的反面被正确剔除，全场景渲染完全恢复正常且无任何闪烁。

---

## 4. 配套加固成果
在排查与修复期间，对底层网格与数据传输链完成了全链路防御性加固：
1. **数据深度隔离**：`ChunkRenderDispatcher` 使用独立的直接缓冲区深拷贝，彻底消除异步/多线程下与 `WorldRenderer` 共享原生内存的并发别名隐患。
2. **绘制原子快照**：`VulkanChunkStore` 引入 `LayerSnapshot`，单次原子读取 `buffer`、`vertexCount`、`bufferSize` 与 `generation`，杜绝绘制越界和代际状态撕裂。
3. **Descriptor 分离**：修复 `VkWriteDescriptorSet.pImageInfo` 的切片指针，实现 CUTOUT 层独立无 Mipmap 采样器配置。
4. **编译与上传代际守卫**：全链路引入 generation 标识，严格拒绝陈旧的重编网格覆盖较新的图元数据。
