# Function Calling / Tool Use 学习笔记

> 配套代码:`DashScopeClient.chatWithTools`、`FunctionCallService`、`WechatController./function-calling`
> 演示:`GET /wechat/function-calling?q=北京明天天气怎么样`(需要先启动机器人)

---

## 1. 概念:为什么需要 Function Calling

大模型(LLM)只能"说话",不能"做事"。它训练数据有截止日期、无法实时查询、也不会算数、更没有你的业务数据。**Function Calling(函数调用 / 工具调用)**让模型在回答之前,先请求应用帮你调用一个真实函数,再把函数返回的结果组织成最终回答。

典型场景:

| 场景 | 模型自己做不到 | 用工具解决 |
|------|--------------|-----------|
| 查天气 | 不知道实时天气 | 调用 `get_weather(city, time)` |
| 查时间 | 不知道当前时间 | 调用 `get_current_time()` |
| 查订单/库存 | 没有你的数据库 | 调用 `query_order(id)` |
| 精确计算 | 容易算错 | 调用 `calculate(expression)` |
| 执行动作 | 不能操作外部系统 | 调用 `send_email(to, subject)` |

核心思想:**参数由模型生成,执行由你的代码完成,结果回传给模型组织语言**。模型只负责"决策",不负责"执行"。

---

## 2. 工作流程(工具调用循环)

```
用户问题
   │
   ▼
① LLM(带 tools 工具清单) ──→ 模型判断:直接能答? ──→ 返回 content → 结束
   │                              │
   │                              └─ 需要实时数据?
   │                              ▼
② 模型返回 tool_calls: [ {id, function: {name: "get_weather", arguments: "{\"city\":\"北京\",...}"}} ]
   │
   ▼
③ 你的代码执行真实函数(解析 arguments → 调 WeatherClient → 得到结果文本)
   │
   ▼
④ 把结果作为 role="tool" 消息回填(必须带 tool_call_id)
   │
   ▼
⑤ 再次调用 LLM(带上 ①~④ 的全部对话)──→ 模型综合工具结果生成最终回答 → 结束
```

**关键细节**:

- 一次响应里模型可能发起**多个**工具调用(`tool_calls` 是数组),要逐个执行、逐个回填;
- 工具结果回填后**必须再问一次模型**才能得到最终回答;
- 助手发起调用后,那条带 `tool_calls` 的 assistant 消息也要**回填进对话**,否则模型不知道自己发起了调用;
- 要设置**最大轮数**(如 4),防止模型反复调用陷入死循环;
- 对话消息的类型(`role`)有三种:普通 `user`/`assistant`、助手带 `tool_calls`、工具结果 `tool`。

对应本项目代码 `FunctionCallService.run()`:

```java
for (int round = 0; round < MAX_ROUNDS; round++) {
    ChatResult result = dashScopeClient.chatWithTools(systemHint, messages, tools, null);
    if (!result.hasToolCalls()) {
        return new RunResult(question, steps, result.content());   // ① 直接回答 → 结束
    }
    messages.add(HistoryMessage.assistantWithToolCalls(result.toolCalls()));  // ② 助手调用入对话
    for (ToolCall call : result.toolCalls()) {
        String r = executeTool(call.name(), call.arguments());                 // ③ 执行真实函数
        messages.add(HistoryMessage.toolResult(call.id(), r));                 // ④ 结果回填
    }
    // ⑤ 回到循环顶部,再问一次
}
```

---

## 3. JSON Schema 描述函数签名

Function Calling 的 API 是 **OpenAI 兼容格式**(DashScope 的 compatible-mode 同样支持)。一个工具是:

```json
{
  "type": "function",
  "function": {
    "name": "get_weather",
    "description": "查询城市实时天气、空气质量、日出日落、紫外线与生活指数,或明天/未来几天的预报",
    "parameters": {
      "type": "object",
      "properties": {
        "city": {
          "type": "string",
          "description": "城市名,例如:北京、上海、南京、广州、深圳",
          "minLength": 2,
          "maxLength": 6
        },
        "time": {
          "type": "string",
          "description": "查询的时间范围",
          "enum": ["现在", "今天", "明天", "几天"],
          "default": "现在"
        }
      },
      "required": ["city"],
      "additionalProperties": false
    }
  }
}
```

### 3.1 顶层字段

| 字段 | 必填 | 说明 |
|------|------|------|
| `type` | 是 | 固定为 `"function"` |
| `function.name` | 是 | 函数名。**必须与执行端代码一致**,且只允许 `a-zA-Z0-9_-`,长度建议 ≤ 64 |
| `function.description` | 是 | 函数作用说明。**写清楚"什么时候该用、返回什么"能大幅提升模型调用准确率** |
| `function.parameters` | 是 | JSON Schema 对象,描述入参结构 |

### 3.2 parameters(JSON Schema)常用字段

| 字段 | 说明 |
|------|------|
| `type` | 顶层必须是 `"object"`(入参是一个对象) |
| `properties` | 对象,声明每个入参的 Schema |
| `required` | 字符串数组,列出必填参数。**没列出的参数模型可以不传** |
| `additionalProperties` | `false` 时禁止传入未声明的参数,推荐开启 |
| `$ref` / `definitions` | 复用公共 Schema 片段(复杂场景) |

### 3.3 properties 里单个参数的常用约束

| 字段 | 适用类型 | 说明 |
|------|---------|------|
| `type` | 全部 | `string` / `number` / `integer` / `boolean` / `array` / `object` |
| `description` | 全部 | **必写**。告诉模型这个参数填什么,示例值最有效 |
| `enum` | 全部 | 枚举取值,模型只能从中选(适合固定选项) |
| `default` | 全部 | 默认值,参数缺省时用它 |
| `minLength` / `maxLength` | string | 长度约束,防模型传整句话进来 |
| `pattern` | string | 正则约束(如 `^1[3-9]\d{9}$` 校验手机号) |
| `minimum` / `maximum` / `exclusiveMinimum` | number/integer | 数值范围 |
| `items` | array | 数组元素类型:`{"type":"array","items":{"type":"string"}}` |
| `format` | string | 语义格式提示(如 `date-time`、`email`),多数模型只是参考 |

### 3.4 常见套路

- **无参工具**:`properties: {}`、`required: []`(见本项目 `get_current_time`);
- **必填与可选混用**:必填进 `required`,可选的写 `default`;
- **参数越少越好**:参数多会增加模型传错的概率,能用枚举就枚举;
- **description 是"给模型的说明书"**:`"city": "城市名,例如:北京、上海、南京"` 比 `"city": "城市"` 效果好得多。

---

## 4. 对应本项目代码走读

| 代码 | 作用 |
|------|------|
| `DashScopeClient.FunctionTool` | 工具定义(携带 JSON Schema 的 parameters) |
| `DashScopeClient.chatWithTools(...)` | 带 `tools` 数组调用模型,解析出 `content` 或 `tool_calls` |
| `DashScopeClient.HistoryMessage` | 扩展支持 `tool_calls` 与 `tool_call_id` 两种特殊消息 |
| `DashScopeClient.ToolCall` / `ChatResult` | 模型发起的调用 / 一次对话结果 |
| `FunctionCallService.Tool` | 工具接口:name / description / parameters(JSON Schema)/ execute(真实执行) |
| `FunctionCallService.registerTool()` | 工具注册表(Map),可随时扩展或测试替换 |
| `FunctionCallService.buildTools()` | 遍历注册表输出 tools 数组 |
| `FunctionCallService.run()` | 工具调用循环(最多 5 轮),支持多步链式与单轮并行 |
| `FunctionCallService.executeTool()` | 注册表分发;未知工具/执行异常都转为工具结果回填,不中断循环 |
| `WechatController./function-calling` | 浏览器可直接观察完整调用链 |

内置工具:`get_current_time`(时间)、`get_weather`(天气,复用 WeatherClient)、`calculate`(表达式求值)、`unit_convert`(单位换算:温度℃/℉/K、长度m/km/cm/mm/尺、重量kg/g/斤/两/磅)。

### 试一下

启动机器人后访问(无需登录微信也能测,`chatWithTools` 不依赖登录):

```
http://localhost:8080/wechat/function-calling?q=北京明天天气怎么样
http://localhost:8080/wechat/function-calling?q=现在几点了
http://localhost:8080/wechat/function-calling?q=算一下 123*456
http://localhost:8080/wechat/function-calling?q=100华氏度等于多少摄氏度
```

返回 JSON 里 `steps` 是工具调用轨迹(哪个工具、什么参数、返回什么),`finalAnswer` 是最终回答:
- 问天气 → 模型调用 `get_weather`,参数里自动带上 `city=北京`、`time=明天`;
- 问时间 → 调用 `get_current_time`;
- 问计算 → 调用 `calculate`;
- 问笑话 → 模型觉得不需要工具,直接回答,`steps` 为空。

---

## 5. 多步链式调用(已验证)

当"第 2 步的参数依赖第 1 步的工具返回值"时,模型会在**下一轮**基于已回填的真实结果发起新调用:

```
问题:北京现在气温多少?帮我换算成华氏度
第1轮: 模型 → tool_calls [get_temperature({"city":"北京"})]
       ↓ 执行 → 北京当前气温:24℃ → 回填
第2轮: 模型(看到 24℃)→ tool_calls [unit_convert({"value":24,"from":"celsius","to":"fahrenheit"})]
       ↓ 执行 → 75.2 → 回填
第3轮: 模型 → 北京当前气温24摄氏度,约75.2华氏度(最终回答)
```

关键实现点:
- 每轮把「助手 tool_calls + 各工具结果」追加进对话,第 N+1 轮的模型输入已包含第 N 轮的**真实**返回;
- 系统提示明确要求:"后一步的参数必须使用前一步工具返回的真实结果,禁止猜测或编造中间值";
- 单轮内多个**相互独立**的调用可并行发起(一个 tool_calls 数组),逐个执行回填。

### 验证结果(离线桩测试,5 个场景全部 PASS)

| 场景 | 验证点 | 结果 |
|------|--------|------|
| 链式调用 | 第 2 步 `unit_convert` 的 `value=24` 来自第 1 步 `get_temperature` 返回的气温,结果 75.2℉ 正确 | ✅ |
| 工具抛异常 | 异常被捕获转为「工具执行失败:…」回填,循环不中断 | ✅ |
| 未知工具 | 模型调用未注册工具,返回友好提示(含可用工具清单),循环不中断 | ✅ |
| 死循环保护 | 每轮都调用工具,超过最大轮数(5)后终止并抛异常 | ✅ |
| 单轮并行 | 同一轮并行发起 2 个独立调用(3.5km→3500m、2斤→1000g)全部正确执行 | ✅ |

---

## 6. 微信 Bot 集成(已实现)

`WechatBotService` 已把 Function Calling 接入真实对话链路:

- 普通聊天消息、语音回复内容都会先走 `FunctionCallService.run(question, concise)`;
- 模型判定需要工具时自动调用(get_weather / get_current_time / calculate),执行结果回填后生成最终回答;
- **文字回复会附上工具调用轨迹**,直接看到执行结果与参数:

  ```
  现在是2026年8月18日 星期二,22:30。

  ⚙️ 本次调用工具:
  • get_current_time({})
  ```

- 工具调用失败(网络/额度/超轮数)时自动回退普通对话,保证用户永远能得到回答;
- 对话记忆照常生效(工具化回答也会写入 MemoryStore)。

在微信里直接测试:

| 发送 | 预期 |
|------|------|
| 现在几点了 | 调用 get_current_time |
| 算一下 123*456 | 调用 calculate,回复 56088 |
| 北京明天天气怎么样 | 意图识别已拦截,直接查天气(更快);其他天气说法可能走 get_weather 工具 |
| 讲个笑话 | 无需工具,直接回答 |

## 7. 进阶与常见问题

- **tool_choice 强制调用**:`tool_choice: {"type":"function","function":{"name":"get_weather"}}` 可强制模型必须调用指定工具(适合"识别用户意图后必须查天气"的场景);
- **多工具 + 依赖**:模型可一次发起多个独立调用;若第二个工具依赖第一个的结果,需要分两轮(第一轮结果回填后,模型下一轮才会发起第二个);
- **参数校验**:模型可能给出非法参数(如城市不存在),执行端要 try-catch 并把错误信息作为 tool 结果回填,让模型自己修正;
- **成本控制**:每轮都是完整上下文重发,工具调用越多 tokens 越多,注意设置最大轮数与上下文裁剪;
- **模型选择**:qwen-plus 及以上(含 qwen-max、qwen3 系列)均支持 Function Calling;小模型能力弱,建议用 description 写示例。

### 练习建议

1. ~~新增一个 `calculate` 工具(表达式求值),让模型做精确计算~~ ✅ 已实现(`FunctionCallService` 内自写递归下降解析器,支持 + - * / % ^ 与括号);
2. ~~把 `executeTool` 改成可注册的 `Map<String, Tool>` 工具注册表(工厂模式),而不是 switch~~ ✅ 已实现(`Tool` 接口 + `registerTool()` 注册表,未知工具/异常统一兜底);
3. 用 `tool_choice` 强制天气场景必须调用 `get_weather`;
4. 给 `get_weather` 加上参数校验,把"查不到该城市"作为工具结果回填给模型,观察模型如何应对;
5. 新增一个业务工具(如查订单/换算汇率),接入微信对话观察完整调用链;
6. 设计一个 3 步以上的链式任务(如:查天气 → 取温度 → 换算单位 → 计算温差),验证更深的多轮依赖。
