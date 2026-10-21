# OpenRadio —— 长期项目约定

Minecraft 1.21.1 / NeoForge 21.1.262 / ModDevGradle 2.0.148 / Gradle 9.2.1 / Java 21
modid `openradio`，包 `com.rinko.openradio`。

## 不可违反的架构原则

1. **服务端权威。** 客户端只发"意图"包，所有判定（频率合法性、开关机、能否收发、Q 值）
   在服务端做。客户端不得自行计算或伪造 Q。
2. **不做异步计算。** 全部在主线程，靠 tick 预算与 Q 值变化阈值控制开销。
   快照阶段要读 World/做射线，本来就在主线程，异步只能省浮点算术，收益约等于零。
3. **设备状态跟着设备自己走。** 手持机 = ItemStack DataComponent；固定台 = BlockEntity。
   禁止搞持久化的全局设备注册表 —— 它是腐坏存档的根源。
4. **投递与计算分离。** `MessageSink` 负责"怎么把消息送到接收者"，发送链路负责
   "谁该收到、质量多少"。文本/语音是两种 Sink，共用同一条链路。
5. **传播计算是纯函数。** `SignalPropagation.quality(distance, ...)` 只依赖参数与配置，
   不读 World。想加缓存或挪线程时才不用重写。
6. **所有平衡参数进配置。** 不要硬编码距离/阈值/冷却，服务器管理员要能调。

## 版本/API 注意

- `DeferredRegister.createDataComponents(String)` 已废弃 →
  `createDataComponents(Registries.DATA_COMPONENT_TYPE, namespace)`
- `StreamCodec` 字段类型用 `io.netty.buffer.ByteBuf`，不能用 `FriendlyByteBuf`
- 事件总线一律显式 `addListener`，不用 `@EventBusSubscriber` 自动分派
- 物品默认值不要塞进 `Item.Properties.component(...)`，静态初始化时 DeferredHolder 未解析
- **配置缓存必须关闭**（`org.gradle.configuration-cache=false`）：多个 `run*` 任务
  会同时保持 Gradle 进程存活，并发读写 `.gradle/configuration-cache` 会把临时文件搞坏
- **不要缓存 ItemStack 引用**：服务端同步时原版是整个替换槽位里的 ItemStack，
  旧引用会变成"上一帧的对象"，读到的永远是旧值。要用时从玩家手上重新解析。
- SVC 是 **ARR 许可**，源码可见但不可复制。只能依赖 API 集成。
- **SVC 的邻近语音依赖实体存在**：`PlayerSoundPacket` 在客户端要先
  `level.getPlayerByUUID(sender)`，找不到就直接丢包，插件回调都不触发。
  实体只在视距内跟踪，所以远距离语音必须走 `MicrophonePacketEvent` → 转成
  `StaticSoundPacket` 由服务端逐人转发（静态包不查实体、非定位、全音量）。
  远距离"UI 正常、文字正常、语音没声"就是这个原因。

## 协作偏好

- 直接给结论和代码，不写客套话
- 有不同意见要明确说出来并给理由，不要顺从
- 每个阶段结束要做真实加载验证，不能只保证编译通过

## 路线与版本

- `docs/ROADMAP.md` 是项目唯一事实来源（进度/架构不变量/技术结论/后续路线）。
  上下文被压缩后先读它。
- 版本策略：0.x = 抢先体验，固定台 + 数字模式完成后才发 1.0.0。
- 阶段顺序：M5 手持台打磨 → M6 固定台与数字模式（CW/RTTY/加密/SSTV）→ M7 发 1.0 → M8 中继。
- **数字模式一律做成数据协议，音调在客户端本地合成**；绝不试图把调制信号塞进 SVC 的 Opus。
- 能量统一用 NeoForge Energy（FE，UI 可显示为 RF）；没电只限制发射，不做变砖。
