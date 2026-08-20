# 微信 AI 机器人

基于 Spring Boot 的微信个人号机器人,接入微信 iLink Bot 协议与通义千问大模型,实现扫码登录、自动回复、图片理解、图片生成、实时天气查询与语音识别。

## 功能特性

- **扫码登录** — 浏览器扫码,登录态持久化,重启后免扫码恢复
- **文本自动回复** — 接入通义千问(qwen-plus),对话式回复
- **图片理解** — 收到图片用视觉模型(qwen-vl-max)描述内容
- **图片生成** — 发送「画一张 xx」自动生成图片(qwen-image-plus)
- **实时天气** — 发送「北京天气」查询实时天气 + 空气质量(AQI)+ 日出日落 + 紫外线 + 生活指数 + 3 天预报(和风天气)
- **语音识别** — 收到语音消息后转文字(优先微信自带转写,兜底 DashScope ASR 语音识别模型)
- **语音回复** — 说「用语音回复 xx」或直接发语音消息,自动用 TTS(cosyvoice)合成语音回复
- **意图识别** — 自动判断消息意图:文字回复 / 语音回复 / 生成图片 / 查天气,关键词快路径 + LLM 结构化判定双保险
- **函数调用(Function Calling)** — 对话时模型可调用工具获取实时数据:查天气、查当前时间、精确计算、单位换算,文字回复附上工具调用轨迹;支持多步链式调用(后一步参数来自前一步结果)
- **对话记忆** — 按用户记住最近 20 条对话,重启后保留,支持多轮上下文
- **REST 调试接口** — 二维码、登录状态、收发消息

## 技术栈

| 组件 | 说明 |
|------|------|
| Spring Boot | 4.1.1-SNAPSHOT |
| Java | 21 |
| wechat-ilink-sdk | 2.3.3(微信 iLink Bot 协议) |
| 通义千问 / DashScope | 对话、视觉、图像生成 |
| 和风天气 QWeather | 实时天气与预报 |

## 架构

```
微信用户 ⇄ iLink 服务器 ⇄ WechatBotService(核心服务)
                            ├── 意图识别      关键词快路径 + LLM 结构化判定(chat/voice/image/weather)
                            ├── DashScopeClient  通义千问(对话/看图/画图/TTS 语音合成/ASR 语音识别)
                            ├── AudioCodec       mp3/SILK/WAV 转码(语音收发)
                            ├── WeatherClient    和风天气(实时/预报)
                            ├── LoginStateStore  登录态持久化
                            ├── MemoryStore      对话记忆持久化
                            └── WechatController REST 接口
```

消息处理流程:后台每 2 秒轮询 `getUpdates()` 拉取新消息 → 异步线程池按消息类型分发(图片/语音/文本)→ 意图识别(关键词快路径 + LLM 结构化 JSON,判定 文字回复 / 语音回复 / 生成图片 / 查天气)→ 调用对应能力 → 回复。

## 快速开始

### 1. 环境要求

- JDK 21
- Maven 3.8+

### 2. 配置密钥

在 `src/main/resources/` 下创建 `secret.properties`(已被 `.gitignore` 忽略,不会提交):

```properties
# 通义千问(DashScope)API Key
dashscope.api-key=sk-你的key

# 和风天气 API Key
qweather.api-key=你的key
```

- DashScope Key 获取:https://dashscope.console.aliyun.com/
- 和风天气 Key 获取:https://console.qweather.com/

> 非敏感配置(模型名、地址)在 `application.properties` 中,按需修改。

### 3. 启动

```bash
mvn spring-boot:run
```

### 4. 扫码登录

浏览器打开 http://localhost:8080/wechat/qrcode 扫码登录。登录成功后登录态会保存到本地,重启无需重复扫码。

## 使用说明

给机器人发送以下内容即可触发对应能力:

| 发送内容 | 效果 |
|----------|------|
| 任意文字 | 通义千问自动回复(可调用 天气/时间/计算 工具) |
| 图片 | 描述图片内容 |
| 画一张 蓝天白云 | 生成图片 |
| 北京天气 | 查询北京实时天气 + 3 天预报 |
| 现在几点了 | 调用 get_current_time 工具并返回当前时间 |
| 算一下 123*456 | 调用 calculate 工具精确计算 |
| 100华氏度等于多少摄氏度 | 调用 unit_convert 工具换算 |
| 北京气温多少,换算成华氏度 | 链式调用:get_weather → unit_convert(多步工具流程) |
| 语音 | 转文字后回复(默认用语音回复) |
| 用语音回复 明天天气 | 查天气并用语音回复 |
| 用语音回复 讲个笑话 | 用语音讲笑话 |

## REST 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/wechat/qrcode` | 获取登录二维码 |
| GET | `/wechat/status` | 查询登录状态 |
| GET | `/wechat/messages` | 取出积压消息 |
| POST | `/wechat/send` | 主动发送文本,body `{"toUserId":"xxx","text":"内容"}` |

## 项目结构

```
src/main/java/com/luciano/wechat/
├── WechatBotService.java   核心服务(登录、轮询、消息分发、自动回复)
├── DashScopeClient.java    通义千问封装(对话/看图/画图)
├── WeatherClient.java      和风天气封装(实时/预报)
├── MemoryStore.java        对话记忆持久化(按用户,最近 20 条)
├── WechatController.java   REST 接口
└── LoginStateStore.java    登录态持久化
src/main/resources/
├── application.properties  非敏感配置
└── secret.properties       API Key(需自行创建,不提交)
```

## 注意事项

- **API Key 与登录态不入库**:`secret.properties`、`wechat-login.json`、`wechat-memory.json` 均已加入 `.gitignore`,请勿强制提交。
- **不能主动发起对话**:iLink 协议限制,机器人需用户先发消息才能回复。
- **连接有效期**:iLink 连接约 24 小时,可能需重新登录。
- **语音消息时长上限**:微信语音约 60 秒上限,语音回复超过 `voice.max-chars`(默认 120 字)会自动截断播报并补发完整文字,避免超长语音被拒收。
- 通义千问、和风天气均有免费额度限制,请勿高频调用。
