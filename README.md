# 微信 AI 机器人(Luciano)

基于 Spring Boot + 阿里云百炼(通义千问)+ 微信 iLink Bot 协议的智能微信机器人。
支持文本问答、语音收发、图片识别与生成、天气查询、发邮件、联网搜索、多步工具链调用与多轮上下文记忆。

---

## 目录

- [功能总览](#功能总览)
- [环境要求](#环境要求)
- [获取 API Key](#获取-api-key)
- [项目配置](#项目配置)
- [运行项目](#运行项目)
- [扫码登录](#扫码登录)
- [功能使用说明](#功能使用说明)
- [REST 调试接口](#rest-调试接口)
- [架构说明](#架构说明)
- [常见问题](#常见问题)

---

## 功能总览

| 功能 | 说明 | 触发方式示例 |
|---|---|---|
| 文本问答 | 接入通义千问 qwen-plus,多轮上下文对话 | 直接发文字 |
| 意图识别 | 自动判断文字/语音/图片/天气意图并分发 | 自动 |
| 联网搜索 | qwen 内置联网,回答实时信息 | "帮我搜索/查一下 XX" |
| 天气查询 | 和风天气增强(温度/湿度/空气质量/预报/日出日落),未覆盖城市自动降级心知 | "北京天气" |
| 文生图 | 根据文字生成图片 | "画一只橘猫" |
| 图片识别 | 理解图片内容 | 发送图片 |
| 图文合并 | 图片+文字两条消息合并理解 | 发图后 3 分钟内补文字 |
| 语音回复 | TTS 合成 mp3 语音文件回复 | "用语音回复我" |
| 语音识别 | 收到语音转文字后回复 | 发送语音 |
| 发邮件 | 发送邮件到指定邮箱 | "发封邮件到 xx@qq.com" |
| 工具链调用 | 多工具按序调用,后一步依赖前一步结果 | "查天气并画应景图" |
| 上下文记忆 | 按用户隔离多轮记忆,持久化到本地,重启保留 | 自动 |

---

## 环境要求

- **JDK 17+**
- **Maven 3.8+**
- **Windows / macOS / Linux**(以下示例以 Windows 为准)
- 网络可访问阿里云百炼、心知天气、和风天气、微信 iLink 服务

---

## 获取 API Key

本项目使用以下第三方服务,需提前申请:

### 1. 阿里云百炼(必配,核心能力)
- 地址:https://bailian.console.aliyun.com/
- 注册后进入「API-KEY」页面创建,得到 `sk-` 开头的 Key
- 需开通模型:qwen-plus(对话)、qwen-vl-max(识图)、wanx(生图)、cosyvoice(语音合成)

### 2. 心知天气(必配,天气兜底)
- 地址:https://www.seniverse.com/
- 注册后创建 API,得到 Key(免费版够用)

### 3. 和风天气(可选,天气增强)
- 地址:https://dev.qweather.com/
- 注册后在「控制台-设置」查看专属 **API Host**,在「项目管理」创建 **API Key**
- 配置后天气返回更详细(空气质量/多天预报/日出日落);不配则自动用心知

### 4. QQ 邮箱授权码(可选,发邮件功能)
- QQ 邮箱 → 设置 → 账号 → 开启「POP3/SMTP 服务」
- 按提示完成短信验证后生成 **16 位授权码**(非 QQ 密码)

---

## 项目配置

### 1. 创建本地私有配置

项目根目录创建 `src/main/resources/application-local.properties`(已加入 .gitignore,不会提交),内容:

```properties
# ===== 阿里云百炼(必配)=====
llm.api-key=你的百炼APIKey

# ===== 心知天气(必配)=====
weather.api-key=你的心知天气Key

# ===== 和风天气(可选,增强)=====
weather.qweather-key=你的和风APIKey
weather.qweather-host=https://你的专属host.qweatherapi.com

# ===== QQ 邮箱(可选,发邮件)=====
mail.from=你的QQ邮箱@qq.com
mail.auth-code=你的16位授权码

# ===== 调试接口鉴权 token(建议修改)=====
security.api-token=换成你自己的token
```

> **⚠️ 安全提醒:** `application-local.properties` 含所有密钥,已被 .gitignore 排除,**绝不提交到 git**。

### 2. 常用配置项(application.properties)

| 配置 | 默认值 | 说明 |
|---|---|---|
| `llm.model` | qwen-plus | 对话模型 |
| `llm.tts-model` | cosyvoice-v1 | 语音合成模型 |
| `llm.tts-voice` | longxiaochun | 语音音色 |
| `llm.image-model` | wanx2.1-t2i-turbo | 文生图模型 |
| `llm.vision-model` | qwen-vl-max | 识图模型 |
| `llm.search-enabled` | true | 联网搜索开关 |
| `weather.default-location` | beijing | 天气默认城市 |
| `mail.host` / `mail.port` | smtp.qq.com / 465 | SMTP 配置 |

---

## 运行项目

```bash
# 进入项目目录
cd F:\Project\luciano

# 启动(默认已激活 local profile,自动加载 API Key)
mvn spring-boot:run
```

首次启动会自动下载依赖,耐心等待。启动成功看到类似日志:

```
Tomcat initialized with port 8080 (http)
已注册工具: send_email - ...
已注册工具: generate_image - ...
已注册工具: get_weather - ...
请用微信扫描以下二维码登录机器人:
```

---

## 扫码登录

启动后有两种方式查看二维码:

### 方式一:Web 页面(推荐)
浏览器访问(需带鉴权 token):
```
http://localhost:8080/wechat/qrcode
```
请求头加 `X-Auth-Token: 你的token`,浏览器直接显示二维码内容。

> 二维码内容是 `https://liteapp.weixin.qq.com/q/...` 链接,可用二维码生成工具(如 cli.im)转成二维码图片,用手机微信扫码。

### 方式二:控制台
复制控制台输出的二维码链接,用二维码生成工具渲染后微信扫码。

### 登录状态
- 登录成功后,`wechat-login.json` 保存登录凭证,**重启后自动免扫码恢复**
- 登录状态可访问 `http://localhost:8080/wechat/status` 查看

---

## 功能使用说明

### 1. 文本问答
直接发送文字,机器人会带上下文记忆回复。
```
> 你好
> 介绍一下你自己
```

### 2. 联网搜索
```
> 帮我搜索一下2026年最新的xxx
> 查一下今天有什么新闻
```

### 3. 天气查询
```
> 北京天气
> 上海明天天气怎么样
> 现在几度
```
- 支持城市名(中文或拼音)
- 未指定城市时查询默认城市(可配置)

### 4. 生成图片
```
> 画一只在草地上奔跑的橘猫
> 帮我生成一张海边日落的插画
```

### 5. 图片识别 / 图文合并
- **单独识图**:直接发送图片,机器人自动识别内容
- **图文合并**:先发图片,3 分钟内再发文字描述,机器人结合图片+文字理解
```
(发一张图片)
> 这是什么图片
```

### 6. 语音回复
```
> 用语音回复我
> 语音说:今天天气怎么样
```
机器人会 TTS 合成 mp3 语音文件回复。

### 7. 语音消息
直接发送语音,机器人自动转文字后回复。

### 8. 发送邮件
```
> 发一封邮件到 xxx@qq.com,主题是"测试",内容是"你好"
```

### 9. 工具链调用(多步)
```
> 查一下上海天气,然后根据天气画一张应景的图
```
机器人会依次调用:天气工具 → 生图工具(把天气写入图片描述),并展示工具调用轨迹。

---

## REST 调试接口

所有接口需在请求头带 `X-Auth-Token`(未配置 token 时接口拒绝访问)。

| 接口 | 方法 | 说明 |
|---|---|---|
| `/wechat/qrcode` | GET | 获取登录二维码内容 |
| `/wechat/status` | GET | 查询登录状态 |

示例:
```bash
curl -H "X-Auth-Token: 你的token" http://localhost:8080/wechat/status
```

---

## 架构说明

```
微信用户 → iLink 服务 ← WechatBotRunner(核心消息入口)
                ├── IntentService      意图识别(文字/语音/图片/天气)
                ├── LlmService         通义千问对话 + Function Calling 工具循环
                ├── ImageService       文生图 + 多模态识图
                ├── TtsService         语音合成(mp3)
                ├── WeatherService     天气(和风增强 → 心知兜底)
                ├── MailService        QQ 邮箱 SMTP 发信
                ├── ToolRegistry       工具注册中心(天气/生图/邮件)
                ├── ToolExecutionGuard 工具超时与限流
                ├── ConversationService 多轮记忆(滑动窗口+摘要+落盘)
                ├── LoginStateStore    登录态持久化
                └── WechatController   REST 接口(带鉴权)
```

### 工具列表(LLM 可调用)

| 工具 | 参数 | 说明 |
|---|---|---|
| `get_weather` | location(城市) | 查询实时天气 |
| `generate_image` | prompt(描述) | 文生图 |
| `send_email` | to/subject/content | 发送邮件 |

---

## 常见问题

### Q: 启动后登录失败/超时?
登录轮询是长轮询接口,若频繁失败:
- 检查网络能否访问 `ilinkai.weixin.qq.com`
- 确认配置中 `readTimeoutMs` 已设为 60000(项目已默认)
- 二维码过期后重启项目重新扫码

### Q: 天气查不到某些城市?
和风天气内置了常用城市(北京/上海/广州等),未覆盖的城市会自动降级到心知天气查询。

### Q: 意图识别不准?
意图识别由 LLM 判断,偶尔会误判(如"你会画画吗"触发生图)。可通过补充描述让意图更明确。

### Q: 邮件发送失败?
- 确认 QQ 邮箱已开启 SMTP 且授权码正确
- 发件人 `mail.from` 必须与授权码对应账号一致
- 检查 SMTP 端口 465 是否被网络拦截

### Q: 重新启动后还要扫码吗?
不需要。登录态已保存到 `wechat-login.json`,自动免扫码恢复。

### Q: 密钥泄漏到 git 了怎么办?
检查 `.gitignore` 是否包含 `application-local.properties`。若已误提交,需立即从仓库移除并轮换所有 Key。

---

## 许可证 / 说明

本说明面向项目内开发者。所有 API Key 均为本地私有配置,请勿公开或提交到代码库。
