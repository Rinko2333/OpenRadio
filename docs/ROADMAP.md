# OpenRadio 开发路线与进度

> 本文件是项目的**唯一事实来源**。压缩上下文后先读这里，再读代码。
> 最后更新：2026-10-22（M4 完成，v0.4.0；路线重排 + SSTV 方案重新设计）

---

## 0. 一句话定位

Minecraft 1.21.1 / NeoForge 的硬核无线电模组。核心是**真实的传播模型 + 操作手感**：
语音是主线，文本与数字模式是同一条链路的不同呈现，Simple Voice Chat 只当传输层。

**不做什么**：不做通用 VoIP（SVC 已经有了），不做 VoIP 的替代实现，不摘 SVC 的代码（ARR）。

---

## 1. 当前状态：M0–M4 全部完成并通过实机验证

| 阶段 | 内容 | 验收 |
|---|---|---|
| **M0 骨架** | 包名 `com.rinko.openradio`、注册器、数据组件、创造标签、配置 | `runClient` 可见物品，零警告 |
| **M1 最小闭环** | 手持台、频率组件、服务端权威链路、文本收发、面板、HUD | 同频可收发，超射程静默 |
| **M2 传播模型** | 成对定向 Q、DDA 体素遮挡、天气、频段表、tick 预算切片 | 隔山明显变差，频段差异可见 |
| **M3 表现层** | `SignalEffects` 映射、逐说话人链路协议、信号条、离散音效 | 不看数字也能判断信号好坏 |
| **M4 语音接管** | SVC 传输层集成、DSP 链、服务端静态包转发 | 见下方实测记录 |

### 实测记录（用户双客户端验证）

- 文本：30 格完整；200 格出现 `#@%` 乱码与掉字；512 格外完全收不到（短波射程 512）
- 传播：遮挡严重时噪声压过人声；频段切换效果符合预期；下雨有可见劣化
- 语音：近距离清晰；遮挡时噪声明显；**1500 格可通**；关掉电台后对方完全听不到
- 服务端日志证据：`[voice] 转发一帧语音给 1 人`，跑在 `VoiceChatPacketProcessingThread`
- `runData` 加载冒烟每次都是 0 个 ERROR/FATAL

---

## 2. 架构不变量（改代码前必读）

1. **服务端权威。** 客户端只发意图，所有判定（频率合法性、开关机、能否收发、Q）在服务端。
   客户端不得自行计算或伪造 Q。
2. **不做异步计算。** 全部主线程，靠 tick 预算 + Q 变化阈值控制开销。
3. **设备状态跟着设备走。** 手持机 = `ItemStack` 的 DataComponent；固定台 = `BlockEntity`。
   禁止持久化的全局设备注册表 —— 那是腐坏存档的根源。
4. **投递与计算分离。** `MessageSink` 负责"怎么送到接收者"，发送链路负责"谁该收到、质量多少"。
   文本 / 语音 / 将来的数据模式是不同 Sink，共用同一条链路。
5. **传播计算是纯函数。** `SignalPropagation.compute(...)` 只依赖参数与配置，不读 World。
6. **所有平衡参数进配置。** 不要硬编码距离/阈值/冷却。

### 线程模型（SVC 集成）

| 回调 | 线程 | 规则 |
|---|---|---|
| `VoiceDistanceEvent` | `VoiceChatPacketProcessingThread` | 只读不可变快照 |
| `MicrophonePacketEvent` | `VoiceChatPacketProcessingThread` | 只读不可变快照 |
| `ClientReceiveSoundEvent.*` | `AudioChannelThread-<通道UUID>` | 每个说话人一个线程，滤波器状态天然免锁 |

主线程每传播周期重建 `VoiceSnapshot` 并**整体替换 volatile 引用**，SVC 线程只读。不许原地修改。

---

## 3. 已确认的技术结论（别重复踩）

1. **SVC 的邻近语音依赖实体存在。** 客户端处理 `PlayerSoundPacket` 时先
   `level.getPlayerByUUID(sender)`，找不到就 `return` —— 连插件回调都不触发。
   实体只在视距内跟踪，所以远距离必然断。解法：`MicrophonePacketEvent` 拿 Opus 帧 →
   `toStaticSoundPacket()`（channelId = 说话者 UUID，客户端不查实体、非定位）→
   `sendStaticSoundPacketTo()` 逐人转发 → `event.cancel()` 掐掉原版广播。
   **症状特征：UI 正常、文字正常、语音没声。**
2. **SVC 是 All Rights Reserved。** 源码可见不可复制，只能依赖 `voicechat-api` 集成。
3. **API 版本必须 ≤ 运行时本体版本。** 编译用 `2.6.20`（maven 上没有 2.6.22 的 API），
   运行时本体 `2.6.22`（最新 stable）。
4. **`StreamCodec` 的字段类型是 netty `ByteBuf`**，没有 `writeVarInt`/`writeUUID`（那是 `FriendlyByteBuf`）。
5. **手持机界面不要缓存 `ItemStack` 引用。** 服务端改组件同步回来时原版会**整体替换**槽位里的
   ItemStack，旧引用永远是上一帧的对象。每次从玩家手上重新解析。
6. **`DeferredRegister.createDataComponents(String)` 已废弃** → 传 `Registries.DATA_COMPONENT_TYPE`。
7. **物品默认值不要进 `Item.Properties.component(...)`**，静态初始化时 `DeferredHolder` 未解析。
8. **Opus 与调制信号的关系（2026-10-22 修正，原结论过于绝对）。**
   - 坏消息不变：**RTTY 这类要求精确位定时的 FSK 不能走 Opus**。Opus 一帧 20ms ≈ RTTY 一个位
     （22ms），丢一帧就整位丢失，误码率不可控，无法做 FEC。
   - 好消息：SVC 的 API 里**有 `OpusEncoder`**，而且可以自己控制编码模式。SSTV 的信息在
     "瞬时频率的缓慢变化"上，对 Opus 的宽容度远高于 RTTY，**走音频通道可行**。
   - 前提：必须 `OpusEncoderMode.AUDIO`。VOIP 模式是为语音优化的，会削掉 1.5–2.3kHz ——
     那正是 SSTV 的副载波频段，用 VOIP 就废了。
   - 修正后的结论：**SSTV = 真实音频**（可被外部解码器解，是硬核卖点）；
     **RTTY / CW = 数据协议 + 本地合成音**（音调只是表演）。
9. **SVC 提供主动推送音频给指定玩家的公开 API（不需要 mixin）。**
   ```java
   StaticAudioChannel ch = serverApi.createStaticAudioChannel(channelId);
   ch.addTarget(connection);                       // 逐听众
   AudioPlayer p = serverApi.createAudioPlayer(ch, serverApi.createEncoder(OpusEncoderMode.AUDIO),
                                               () -> nextFrameOfPcm16());   // 供应商取帧
   p.startPlaying();                                // SVC 自己按 20ms 节奏取帧 + 编码
   ```
   静态通道是**非定位**音频、**不查实体**，多远都能收到（正好绕开结论 1 的实体制约）。
   我们自己只需要产出 `short[]`，Opus 编码与发送节奏交给 SVC。
   **待验证（M6 开工第一件事）**：静态音频通道的包在客户端是否触发
   `ClientReceiveSoundEvent.StaticSound`，`getId()` 返回通道 UUID 还是玩家 UUID。
   若返回通道 UUID，需要在 `VoiceSnapshot` 里维护 `通道UUID → 发送者` 的映射。

---

## 4. 代码地图

```
src/main/java/com/rinko/openradio/
├── OpenRadio.java              主类，注册总线与事件
├── Config.java                 全部配置（propagation / channel / effects / voice 四节）
├── registry/                   ModItems / ModDataComponents / ModCreativeTabs
├── item/HandheldRadioItem.java 手持台：频率/开关组件、右键开机、tooltip
├── radio/
│   ├── RadioConstants.java     频率范围 1000–99999，默认 3000
│   ├── RadioBand.java          频段参数（射程、穿墙倍率、天气敏感）
│   ├── RadioBands.java         频率 → 频段映射表
│   ├── SignalPropagation.java  纯函数：距离 × 遮挡 − 天气
│   ├── BlockOcclusion.java     DDA 体素遍历 + 逐方块覆盖表
│   ├── SignalEffects.java      Q → (可读度, 噪声底, 低通, 断音率)  ← 文本与语音的共同上游
│   ├── TextDegrader.java       SignalEffects 的文本消费者
│   ├── RadioManager.java       服务端权威：成对 Q、tick 预算、路由表、发射链路
│   ├── VoiceSnapshot.java      给 SVC 线程的不可变快照（半径表 + 路由表）
│   ├── RadioSounds.java        复用原版音效的离散反馈
│   └── delivery/               MessageSink / ChatMessageSink
├── network/                    C2SSetFrequency / C2SSetPowered / C2STransmit / S2CRadioState
├── client/
│   ├── ClientRadioState.java   客户端 Q 与逐说话人链表
│   ├── ClientEvents.java       按键、HUD
│   ├── VoiceClientHandlers.java 语音 DSP 入口（单独成类，服务端不加载）
│   └── screen/RadioScreen.java 调频面板
└── voice/
    ├── OpenRadioVoicePlugin.java SVC 插件：距离兜底 + 麦克风包转发
    ├── RadioVoiceProcessor.java  DSP 链：高通 → 低通 → 中断门 → 音量 → 噪声
    └── VoiceChannelState.java    逐说话人滤波器状态
```

关键数据流：`频率 + 位置` → `SignalPropagation` → `Q` → 分三路
①`S2CRadioState` 同步给客户端 UI/语音　②`TextDegrader` 降级文本　③`RadioVoiceProcessor` 处理音频

---

## 5. 后续路线

### M5 手持台打磨 —— 电池 / 功耗 / 天线匹配 / 静噪（**无插槽 UI**）

**2026-10-22 决定：取消"电池槽 + 天线槽"的容器 UI。** 这是本阶段最重要的决定，
它砍掉的不只是界面，还有一整片 bug 面（见下）。

- **电池不做物品**，能量直接存在电台自己的 DataComponent（int）。省掉
  `AbstractContainerMenu` + `MenuType` + 自定义 `Container` 的同步，同时**消灭
  "物品套物品"的复制漏洞面**（原计划里这是 M5 最大的风险）。
- **天线放副手**，发射/接收时检测 `InteractionHand.OFF_HAND`。将来"背包 / 胸甲自带天线"
  复用同一套判断，不必重写。
- **抽象先行**：`AntennaProvider` → `AntennaProfile(gain, bandMask)`。
  `SignalPropagation` 只接收 `AntennaProfile` 这个纯数据，**不关心它来自副手、护甲还是固定台**，
  这和"传播计算是纯函数"的不变量一致。

具体项：

- **能量**：NeoForge Energy（`Capability.EnergyStorage.ITEM`，`Item#initCapabilities`），
  单位 FE，UI 显示为 RF。通用发电机/充电器/储能设备直接兼容，零对接代码。
- **功耗**：待机 ≈ 0/tick；发射按频段功率扣电；**电量 < 10% 只能收不能发**，不做变砖。
  跨阈值时动作栏提示一次（不要每 tick 刷）。
- **语音功耗的计费点**：SVC `PlayerStateChangedEvent`（麦克风开合），**不是每帧音频**。
  每帧扣电会把 FE 变成毫秒级消耗，数值没法平衡。
- **补充电**：M5 提供"电池组"消耗品（右键电台补固定 FE，无 UI）。固定台在 M6 提供
  真正的充电与供电，那时才值得做发电机/太阳能。
- **天线**：三支窄带（短 / 中 / 长）+ 一支**宽带折衷**。乘法生效：
  `射程 = 频段射程 × 天线增益`。专用 +60%，宽带 +20%，裸机 −50%。
  - **两端都算**：Q 同时受发送端与接收端天线影响（互易性成立）。
    所以"听不到远处"有可能是自己天线不行，不只是对方。
  - 宽带天线存在的唯一理由：让"装错天线"从来不是死路，只是效率低。
- **装错波段必须有显式反馈**：HUD 显示"天线不匹配（当前长波）"，不能只让玩家觉得"信号差"。
  这是给信息，不是保姆。
- **静噪**：DataComponent 阈值 + 客户端播放策略（`voice.minVoiceGain` 升级成可调阈值），
  低于阈值整帧静音（含噪声），UI 标"静噪中"。服务端不参与判定。

**验收**：电量耗尽后只能收；换专用天线同频距离明显变远；装错波段有明确提示而不是"莫名其妙的差"；
静噪打开后弱信号完全静音且 UI 显示状态。

### M6 固定台 + 数字模式 —— 核心玩法

**目标**：这是"硬核无线电"真正的玩法层，也是第一个正式版的内容主体。

- **固定台**：`BlockEntity`，位置固定，外接天线，接电网（FE），带充电槽。
  **真正的 `Menu` 放在这里**（有槽位才值得做容器），手持台永远保持纯客户端 Screen。
- **模式框架**：统一 `RadioFrame`（模式 + 载荷 + 校验 + 可选加密），误码率由 Q 决定。
  三种表现层共用 `SignalEffects`，不各自藏一套阈值。
- **CW**：文本 → 摩尔斯。**音频本地合成**（不占带宽），接收端按 WPM 逐字符"打"出来
  （音效 + 文本流），误码表现为错字符 / 漏字符。
- **RTTY**：ITA2/Baudot + 校验，**纯数据协议**，接收端按 45.45 波特节奏呈现电传打字机质感。
  **不要用真实 FSK 音调**（结论 8）。
- **SSTV**：见下方独立设计。
- **不做 FT8/FT4**：需要 15 秒符号周期与精确时隙同步，实现难度与收益不成比例。

#### SSTV 设计（2026-10-22 重新设计，取代原"图像分块走数据通道"）

管线：`图像 →(可选加密)→ SSTV 调制 → Opus(AUDIO) → SVC 静态音频通道 → 接收端 DSP → 解调 →(可选解密)→ 显示`

1. **发送端选图**，不是选音频。玩家在固定台 UI 里选一张本地图片（客户端读成灰度），
   或用手持的"照片"物品。**不许上传任意音频文件**，理由见下方"为什么不按原方案"。
2. **加密在发送端做**，对像素加扰后再上传；**服务端只拿密文**，不知道密钥。
   无密钥时解出来的是一张纯噪声图，而不是"无图像"。
3. **服务端调制与推送**：`createStaticAudioChannel` + `createAudioPlayer`
   + `OpusEncoderMode.AUDIO`，按真实节奏（20ms 一帧）推给同频听众。
   **时钟在服务端**，客户端不许自己决定播放时刻，否则两台接收端会漂移。
4. **接收端**：现有 DSP（噪声 / 低通 / 断音）先处理信号，再把处理后的 `short[]`
   送进 `SstvDemodulator`。**图像质量因此自动跟随传播模型，不需要写任何额外的降级逻辑。**
   这一点是整条路最有价值的地方 —— 低 Q 时不是"加了点噪点"，而是真实的逐行撕裂与同步丢失。
5. **起止同步**：SSTV 的 VIS 头本身就是真正的起始标志，解调器从音频里同步。
   另发 `S2C TxStarted / TxEnded` **只用于 UI**（进度条、占用信道、计费）与终止逻辑，
   不能作为解调的同步源 —— 否则丢一个消息就音画错位。
6. **终止**：服务端停 `AudioPlayer` 并广播 `TxEnded`；发送者下线 / 死亡 / 换手也自动停。

**为什么不用"本地选音频文件上传"这个原方案**：
- 音频格式不可控。MC 只内建 Ogg Vorbis 解码，玩家给个 mp3/flac 就没法读。
- 上传大小无上限（36 秒 SSTV 音频 ≈ 3.4MB，压缩后仍几百 KB），是服务端滥用面。
- 预录音频不能加密、不能改模式、不能在设计上限定时长。
- 而且**播放一段预录音频本身没有玩法落点** —— 玩家要的是"收到一张图"。
  选图 → 本地调制，才能既保留真实音频（可被外部解码器解，硬核卖点），又有图像结果。
- 想广播录音是另一个功能（"音频电报"），等通道跑通后再加，成本很低。

**风险**：
- Opus 对 SSTV 的损伤程度必须先实测：拿一段测试音频在两端跑一遍再动手写解调。
- 低 Q 时低通可能把 1.5–2.3kHz 整段削没，导致"永远解不出来"。
  **数据模式需要独立的"最小带宽"参数**，不能直接沿用语音的低通曲线。
- 解调失败要有明确 UI（"同步失败 / 行同步丢失"），不能只给一块黑屏。
- 单张图上限（建议 320×240 灰度 = 76.8KB）+ 服务端限流 + 同信道同时只允许一个发射者。

**验收**：两台固定台之间能发一张可辨认的 320×240 图；低 Q 时图逐行撕裂；
无密钥时解出纯噪声图；按下终止后对方立刻停；发射中途发送者下线，接收端自动收尾。


### M7 发布 1.0

配置完善、手册、性能压测（20+ 电台）、`mods.toml` 里把 SVC 声明为 `required`、CurseForge/Modrinth 上架。

### M8 中继与网络（1.0 之后）

中继台、跳数限制、未加载区块简化服务、干扰器与反制。
届时 `VoiceSnapshot` 的扁平路由表需要扩成带路径的结构 —— 现在不动。

---

## 6. 需要拍板的决策

| 项 | 决定 | 状态 |
|---|---|---|
| 能量系统 | NeoForge Energy（FE），UI 显示为 RF | ✅ 已定 |
| 没电行为 | < 10% 只能收不能发，功耗极低，不做变砖 | ✅ 已定 |
| 电池形态 | **不做物品槽**，能量存电台 DataComponent；M5 用"电池组"消耗品补电 | ✅ 已定 |
| 天线形态 | **放副手**，检测 `OFF_HAND`；抽象成 `AntennaProvider` 以便将来上护甲/背包 | ✅ 已定 |
| 天线波段 | **按波段专用**（短/中/长）+ 一支宽带折衷兜底 | ✅ 已定 |
| 容器 UI | 手持台**永久不要 Menu**；只有固定台（M6）有真 `Menu` | ✅ 已定 |
| 天线对射程 | `射程 = 频段射程 × 天线增益`，收发两端都算 | ✅ 已定 |
| SSTV 载体 | **真实音频**（SSTV 调制 → Opus AUDIO → SVC 静态通道），不是数据分块 | ✅ 已定 |
| SSTV 输入 | 玩家**选图**，客户端本地调制；不接受任意音频文件上传 | ✅ 已定 |
| 加密位置 | 发送端对像素加扰，**服务端只拿密文、不知密钥** | ✅ 已定 |
| CW / RTTY 载体 | 数据协议 + 本地合成音，不做真实 FSK | ✅ 已定 |
| 中继 | 1.0 之后再做 | ✅ 已定 |

---

## 7. 开发环境

```
./gradlew runClient      主客户端
./gradlew runClient2     第二客户端（用户名 Dev2，独立 run2/ 目录）
./gradlew runServer      专用服务端（需要先接受 run/eula.txt）
./gradlew runData        最快的加载冒烟测试（注册/网络/配置全过一遍）
```

- **配置缓存必须关闭**（`org.gradle.configuration-cache=false`）：
  多个 `run*` 会同时保持 Gradle 进程存活，并发读写缓存目录会互相删文件。
- **SVC 本体**放在 `libs/voicechat-neoforge-1.21.1-2.6.22.jar`（不入库），
  经 `localRuntime` 自动进入所有 run 配置的 classpath。
- 两客户端要进同一个世界才能测：单人世界 → 对局域网开放 → 第二个客户端连 `localhost:端口`。
- 关键配置项：`propagation.*`（射程/遮挡/天气）、`effects.*`（Q→感官映射）、
  `voice.*`（`enabled` / `replaceVanillaBehavior` / `noiseGain` / `highpassHz` / `minVoiceGain`）。

## 8. 验证清单（每个阶段收尾都跑一遍）

1. `./gradlew build` —— 零错误零警告
2. `./gradlew runData` —— 日志有 `OpenRadio initialised`，0 个 ERROR/FATAL
3. 实机双端：文本收发、Q 显示、语音收发、关机电台后彻底静音
4. 语音排查顺序：看服务端 `[voice] 转发一帧语音给 N 人`
   - 没这行 → 服务端没走到（检查 `voice.enabled` / `replaceVanillaBehavior`）
   - `N=0` → 链路表空（频率不同 / 超射程 / 对方没开机）
   - `N>0` 但听不到 → 问题在客户端接收侧（`VoiceClientHandlers`）
