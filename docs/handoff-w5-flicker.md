# W5-① 贴图闪烁攻关交接清单（2026-09-06）

> 新对话开场白建议：「读取 docs/handoff-w5-flicker.md，按其中待办继续。」

## 一、当前仓库状态

- **HEAD = `9cd2373`** `fix(vulkan): resolve terrain quad flickering by enabling backface culling and hardening data pipeline`
  - 背面剔除（VK_CULL_MODE_BACK_BIT + CCW）消除共面零厚度面片深度平局
  - VulkanChunkStore 引入 LayerSnapshot 原子查询；ChunkRenderDispatcher 深拷贝网格；CUTOUT no-mip 与 lightmap binding 修正；上传不变量与陈旧 compile generation 拒绝
  - 复盘文档：`docs/vulkan-coplanar-quad-cullmode-flicker.md`
- **工作树干净**（仅未跟踪 `cp.txt`，classpath dump，可删）
- **stash@{0}** = "W5-1 debugging: reversed-z fixes + probes + sync"，内容：
  - VulkanRenderer：Reversed-Z（投影 near/far 互换 + 清零 0.0 + 严格 GREATER）+ 可见分块按坐标确定性排序
  - VulkanWorldBridge：VulkanValidate/VulkanRebuild 哈希探针 + uploadSeq/meshRing
  - ChunkRenderDispatcher：slice() 字节序修正 + 探针
  - RenderChunk：setPosition 清 store 条目钩子 + RebuildProbe/MarkProbe 探针
  - VulkanChunkStore：全入口 synchronized（9cd2373 用 LayerSnapshot 方案覆盖了此目的）
  - build.gradle：joinWorld 属性映射 + jvmExtra 透传 + findProperty 修正
- **`faf5893`** = A/B 判定基线（闪烁在该干净检查点上**存在**，已确认）

## 二、已验证事实（勿重复排查）

1. 根因：共面零厚度面片（十字草/花/梯子/雪层/树叶）+ cullMode NONE → 深度平局胜负随绘制顺序（随视角）翻转 → "下层建模冒泡/贴图换方向"。修复 = 背面剔除（9cd2373）。
2. 网格数据字节级干净（hex 验证）；重建间差异仅为 AO/light/pos 微差。
3. 非确定性重建 = 真实方块动态（基地海域水流+随机刻，~180 次/分钟）驱动的合法 AO 重烘焙，GL 同样存在；非 bug。
4. 已排除：mip 渗色（用户 mipmapLevels=0）、图集内容、上传竞争（同步 staging+fence）、FastRender、OptiFine 各开关、用户全部 neogenesis 模块（含 motion blur）、深度格式（D32_SFLOAT）、GL/Vulkan churn 差异（同量级）。
5. 抓到并修过的并发隐患：worker 线程 markLayerStates vs 客户端 upload/tickFrame 零同步竞态（双重销毁/HashMap 损坏）——9cd2373 的 LayerSnapshot 属同类加固。

## 三、待办

### 高优先级
1. **游戏内验证 9cd2373**：走动+快速旋转，确认草/梯子/花/雪闪烁彻底消除；同时确认背面剔除没有引入新问题（树叶/植物背面消失是否可接受——原版 1.8.9 对这些本就有剔除约定）。
2. **处置 stash@{0}**：
   - 与 9cd2373 甄别重叠后选择性恢复：**有候选价值**的仅剩 Reversed-Z 相关（严格 GREATER、确定性排序、清零 0.0）——但注意 9cd2373 改了同一批文件，直接 `git stash pop` 会冲突，建议 `git stash show -p` 逐块挑；
   - 探针类改动**不要**恢复（调试用）；
   - build.gradle 的 joinWorld 映射**需要**恢复（9cd2373 只改了 3 行 build.gradle，需确认是否已含）；
   - 处置完 `git stash drop`。

### 中优先级
3. **确认探针不残留**：grep `RebuildProbe\|MarkProbe\|VulkanValidate\|VulkanRebuild` 在 HEAD 应无命中（随 stash 移出）。
4. **ofFastRender**：当前 options.txt 为 false（调试期关闭），是否恢复 true 由用户定。
5. **lightmap 采样完整化**（W5-① 色准正主）：着色器消费 loc3（R16G16_USCALED 光照坐标，当前报 "attribute at location 3 not consumed"）+ 每帧 lightmap 纹理 updateFromGL + 描述符绑定；9cd2373 已修 binding 一半。完成后 AO 重烘焙可见性降到 GL 水平。

### 路线图
6. **W5 剩余**：GUI 管线 → ③实体 ④半透明排序 ⑤天空云 ⑥水动画 ⑦加载性能优化。
7. **W6+**：Vulkan 迁移后续阶段（见最早五阶段迁移计划）。

## 四、关键命令

```bash
# 环境（Git Bash，必做）
export JAVA_HOME="/d/Environment/Amazon Corretto/coretto21"

# 编译验证
./gradlew classes

# 启动 Vulkan（自动进世界；检查点旧版本需 JAVA_TOOL_OPTIONS 方式传 joinWorld）
./gradlew runClient -Prenderer=vulkan -PjoinWorld=FIRST

# GL 对照
./gradlew runClient -PjoinWorld=FIRST

# 清理孤儿游戏进程（杀 gradle 不杀 JavaExec 子进程，必须手动清）
powershell -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\" | Where-Object { \$_.CommandLine -match 'neogenesis|main.Main' } | ForEach-Object { taskkill /PID \$_.ProcessId /F }"
```

## 五、踩过的坑（新对话注意）

1. **gradle 子进程孤儿**：TaskStop/杀 gradle 后 JavaExec 游戏子进程常存活，会锁存档导致新实例起不来；重启前先按上面命令清理，且**绝不**同时开两个实例。
2. **`providers.gradleProperty()` 在 Gradle 9.7 的 GString 插值失效**：传给 JVM 的是字面量 `or(provider(?),...`，必须用 `project.findProperty() ?: '默认值'`。
3. **`ByteBuffer.slice()` 不继承字节序**（默认 BIG_ENDIAN）：对 slice 做 typed 读前要 `.order(source.order())`，或像探针那样逐字节手工解码。
4. **游戏日志在 gradle stdout**：`> Temp/runclient.log 2>&1` 重定向后可 grep `[VulkanDiag]`（每 120 帧：visible/hooks/uploads/store/eye）、`[VulkanValidate]`（上传校验）、`[VulkanRebuild]`（重建哈希对比）——注意这些探针在 stash 里，HEAD 没有。
5. **诊断线故障史**：JOML 数值验证脚本在 `Temp/RevZCheck.java`（Reversed-Z 数学已验证正确）；GL 静止重建基线 ≈15-25 次/分钟。
6. 用户默认设置：`mipmapLevels:0`、无光影包、ofFastRender 曾为 true。

## 六、关键文件速查

| 文件 | 内容 |
|---|---|
| `src/net/theresa/render/vulkan/VulkanRenderer.java` | 管线/深度状态/drawChunks/push 常量/清屏 |
| `src/net/theresa/render/vulkan/VulkanChunkStore.java` | 分块缓冲生命周期/LayerSnapshot/retire 队列 |
| `src/net/theresa/render/vulkan/VulkanWorldBridge.java` | MC↔Vulkan 镜像钩子（uploadChunk/markLayerStates/hasFreshMesh） |
| `src/net/minecraft/client/renderer/chunk/ChunkRenderDispatcher.java` | uploadVertexBuffer 镜像捕获点（quadsToTriangles + slice） |
| `src/net/minecraft/client/renderer/chunk/RenderChunk.java` | setPosition/deleteGlResources 钩子、池化复用 |
| `shaders_vk/terrain.vert` `terrain.frag` | 热重载着色器（F9 重载；无 lightmap 采样） |
| `docs/vulkan-coplanar-quad-cullmode-flicker.md` | 本次闪烁复盘 |
| `.minecraft/options.txt` | 用户游戏设置 |
