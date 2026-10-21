# OpenRadio 开发路线与进度

> 本文件是项目的**唯一事实来源**。压缩上下文后先读这里，再读代码。
> 最后更新：2026-10-21（M4 完成，v0.4.0）

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
8. **Opus 不能承载调制信号（重要，M6 的前置约束）。** SVC 用 Opus VOIP 模式，是有损且为语音
   优化的编码器，会抹平频移、打乱相位。RTTY 的 170Hz 频移、SSTV 的扫频副载波经它编解码后
   不可能正确解调。**数字模式必须做成数据协议，音调只在客户端本地合成。**

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

### M5 手持台打磨 —— 电池 / 功耗 / 天线 / 静噪 / 插槽 UI

**目标**：让手持台从"能用的原型"变成"有取舍的设备"。

- **电池**：电池物品带能量，装进电台后电台从它取电。用 **NeoForge Energy**
  （`IEnergyStorage` 能力 / 单位 FE，UI 上可以显示为 RF），不自造能量系统，
  这样通用发电机和充电器直接兼容。
- **功耗**：待机极低消耗 + 发射按频段功率扣电。**没电 = 只能收不能发**，
  不做"完全变砖"，体验更顺也更像真机。
- **天线**：插槽物品，提供增益/方向性。**射程 = 频段基础射程 × 天线增益**，
  这会把"选频段"从常量变成设备能力，玩法深度立刻上来。
- **静噪**：每台设备一个阈值（DataComponent），客户端播放策略，服务端不参与。
  低于阈值整帧静音（含噪声），默认 0 = 关闭。
- **插槽 UI**：需要引入真正的 `AbstractContainerMenu` + `MenuType`，
  自定义 `Container` 直接读写 ItemStack 的 DataComponent。

**风险与防护**：物品套物品是 MC 最经典的复制漏洞来源。必须处理
"界面打开时电台被丢弃 / 死亡 / 换手 / 被其他玩家拿走"——菜单里记录电台所在槽位，
每次同步前校验那一格还是同一个电台，否则强制关闭界面。

**验收**：电池耗尽后无法发射但能接收；换高增益天线后同频距离明显变远；
静噪打开后弱信号完全静音且 UI 显示"静噪中"。

### M6 固定台 + 数字模式 —— 核心玩法

**目标**：这是"硬核无线电"真正的玩法层，也是第一个正式版的内容主体。

- **固定台**：`BlockEntity`，位置固定，更大的电源与天线接口，可外接电网。
- **模式框架**：统一的 `RadioFrame`（模式 + 载荷 + 校验 + 可选加密），
  走一条独立的 S2C 数据通道，误码率由 Q 决定。模式 = 编解码 + 呈现，不是新的传输。
- **CW**：文本 → 摩尔斯。接收端按 WPM 逐字符"打"出来（音效 + 文本流），误码表现为错字符。
- **RTTY**：ITA2/Baudot + 校验，45.45 baud 的节奏呈现（电传打字机质感）。
- **加密**：数据帧层面加密，无密钥 → 乱码；语音 → Q 压到 0.15 纯噪声。
  **不要试图加密音频流**，在 SVC 里做不到，也不该做。
- **SSTV**：图像 → 分块 → 逐行扫描呈现（低分辨率灰度即可）。**必须走数据通道**，
  绝不能走语音通道（见结论 8）。
- **不做 FT8/FT4**：需要精确时隙同步与弱信号 DSP，实现难度与收益不成比例。

**验收**：两台固定台之间能发一张可辨认的图；无密钥时听到的只是噪声。

### M7 发布 1.0

配置完善、手册、性能压测（20+ 电台）、`mods.toml` 里把 SVC 声明为 `required`、CurseForge/Modrinth 上架。

### M8 中继与网络（1.0 之后）

中继台、跳数限制、未加载区块简化服务、干扰器与反制。
届时 `VoiceSnapshot` 的扁平路由表需要扩成带路径的结构 —— 现在不动。

---

## 6. 需要拍板的决策

| 项 | 建议 | 状态 |
|---|---|---|
| 能量系统 | NeoForge Energy（FE），UI 显示为 RF | 待确认 |
| 没电行为 | 只能收不能发 | 待确认 |
| 插槽实现 | 真 `Menu`（拖拽/Shift 点击走原版），不是假槽位 | 待确认 |
| 数字模式 | 数据协议 + 本地合成音调，不做真实调制解调 | 待确认 |
| 天线对射程 | `射程 = 频段射程 × 天线增益` | 待确认 |
| 中继 | 1.0 之后再做 | 已确认 |

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
