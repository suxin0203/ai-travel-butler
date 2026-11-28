# API 契约

> 本文件是前后端唯一接口依据。后端改接口必须先改本文件再改代码；前端只允许调用本文档中存在的接口。
> 里程碑推进时，在对应章节补充该里程碑的接口明细。

## 1. 全局约定

### 1.1 基础信息

| 项 | 约定 |
|---|---|
| Base URL | `http://localhost:8080`（开发）/ 宝塔域名（部署后） |
| 协议 | HTTP/1.1，字符集 UTF-8 |
| Content-Type | `application/json`（SSE 接口除外，为 `text/event-stream`） |

### 1.2 统一响应结构

所有非 SSE 接口返回：

```json
{ "code": 200, "message": "成功", "data": {} }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| code | int | 见错误码表 |
| message | string | 人读的描述；失败时说明原因 |
| data | object/array/null | 业务数据；失败时为 null |

### 1.3 错误码表

| code | 含义 | 触发场景 |
|---|---|---|
| 200 | 成功 | |
| 400 | 参数校验失败 / 业务规则不满足 | @Valid 失败、用户名已存在、旧密码错误等，message 说明 |
| 401 | 未认证 | 未带 token / token 无效或过期 / 被强制下线（Redis 登录态被删） |
| 404 | 资源不存在或不属于当前用户 | 统一返回 404，不区分"不存在"与"非本人"（防存在性探测） |
| 429 | 限流 | ① 每用户每日 AI 调用配额用尽（作用于 chat 与 travel 接口）；② 发送邮箱验证码触发频控（§2.5a：同邮箱 60s 冷却 / 同邮箱每日 ≤10 次 / 同用户每日 ≤10 次，message 区分） |
| 500 | 服务器内部错误 | 兜底异常 |

### 1.4 认证方式

- 请求头：`Authorization: Bearer <token>`，token 为注册/登录返回的 JWT（HS256，168 小时有效）。
- 下表各接口标注了是否需要登录；需要登录的接口缺失/无效 token 一律返回 401（HTTP 状态码与 body.code 均为该值）。
- 前端收到 401 时清除本地 token 并跳转登录页。

### 1.5 时间格式

- 所有 JSON 中的时间字段格式统一为 `yyyy-MM-dd HH:mm:ss`，时区 Asia/Shanghai（JVM 与 MySQL 连接均已固定该时区）。
- 提醒相关的提前量以"分钟"整数表示。

### 1.6 SSE 事件格式

Content-Type: `text/event-stream`。事件以 `data:` 行承载 JSON，事件间以空行分隔。

**对话流（POST /api/chat，M2）：**

```
: ping                                        ← 自连接建立起每 15 秒一次的心跳注释行（覆盖首 token 等待与工具轮次静默期），前端忽略
data: {"type":"start","conversationId":123}    ← 流的第一个事件，携带本次对话所属会话 id（新建会话时即返回新 id）
data: {"type":"stage","name":"tool","tool":"create_schedule"}  ← 每个工具调用执行前各发一条（tool=工具名；供前端进度时间线）
data: {"type":"chunk","content":"增量文本"}     ← N 条增量，打字机渲染
data: {"type":"done","conversationId":123}     ← 正常结束的唯一标志（回答已落库），其后连接关闭且不再有任何 data 事件
data: {"type":"error","message":"失败原因"}     ← 异常结束（回答未落库），之后连接关闭
data: {"type":"notice","message":"会话数已达上限，已自动清理最旧会话"}  ← 仅会话触发自动清理时出现，至多一条，位于 start 之后
```

stage 事件说明：仅 Function Calling 对话会发出（`name:"tool"`，每次进入工具轮发一条）；纯聊天无工具轮时无 stage 事件，前端时间线按"首条 chunk 到达"推进即可。

- 所有事件的判别字段统一为 `type`
- 连接在收到 `done` 之前关闭或读取出错，一律视为**异常中断**：前端展示失败提示，并以 GET /api/conversations/{id}/messages 重新拉取历史对账，不得以本地已渲染内容为准
- **例外——用户主动停止**（前端 abort 断连，如停止按钮/切换页面）：不视为异常中断，前端静默收场、**不做对账**；服务端断连即取消上游，已落库部分保留在历史中

**提醒流（GET /api/notify/stream，M3）：**

```
: ping                                         ← 每 30 秒心跳注释行，前端忽略
data: {"type":"reminder","scheduleId":123,"title":"日程标题","content":"提醒内容"}
```

前端统一用 fetch + ReadableStream 封装解析（**不使用 EventSource**：它无法携带 Authorization 头）。

---

## 2. M1 接口（认证 + 行程推荐治理）

### 2.1 注册

- `POST /api/auth/register`　认证：否

请求体：

| 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|
| username | string | 是 | 3~20 位，字母/数字/下划线 |
| password | string | 是 | 6~32 位 |
| email | string | 否 | 邮箱格式；trim 后转小写归一存储（归一规则见 §2.5），≤100 字符 |

响应 `data`：

```json
{ "userId": 1, "username": "suxin" }
```

错误：400（参数不合法 / 用户名已存在，message 区分）。

### 2.2 登录

- `POST /api/auth/login`　认证：否

请求体：`{ "username": "suxin", "password": "******" }`

响应 `data`：

```json
{ "token": "eyJhbGciOi...", "expiresInHours": 168 }
```

错误：400（用户名或密码错误——统一文案，不暴露存在性）。

- **登录防爆破（v2.10）**：以 IP+用户名 维度限流——从首次失败起算的 10 分钟固定窗口内累计 5 次登录失败后，该 IP+用户名的后续登录尝试返回 429（文案「尝试次数过多，请 10 分钟后再试」），与每日 AI 配额同走 §1.3 限流形态；登录成功即清零计数。

### 2.3 登出

- `POST /api/auth/logout`　认证：是

删除该 token 对应的 Redis 登录态。注意：白名单模型下，第一次登出后该 token 即整体失效，**重复登出会返回 401**——前端收到 401 时按"已登出"处理即可（清 token 跳登录页），不要把重复登出当异常。响应 `data`: null。

### 2.4 当前用户信息

- `GET /api/auth/me`　认证：是

响应 `data`：

```json
{
  "userId": 1,
  "username": "suxin",
  "email": null,
  "wechatBound": false,
  "notify": { "mail": true, "wechat": true },
  "quota": { "used": 3, "limit": 50 }
}
```

`quota.used` 为今日已用 AI 调用次数（Redis 计数），`limit` 为 `llm.daily-limit` 配置值。`notify` 为推送渠道偏好（§2.5b，新用户默认双开）。

### 2.5 修改邮箱（设置页"邮箱绑定"，v2.4 起需验证码）

- `PUT /api/auth/me`　认证：是

**email 归一规则**：trim 后整体转小写；**空串/纯空白归一为 null（=「未填」）**——注册时视为未提供 email（不校验格式、不存值），改资料时等同「缺省 = 不修改」。主流邮箱服务商本地部分与域名均按不区分大小写处理，与 MySQL `uk_email` 的大小写不敏感排序规则一致。归一后的值统一用于：§2.5a 的全部 Redis 键、本节的验证码键与比对、唯一性查询、DB 存储，以及「email 与当前绑定是否相同」的判断（`User@QQ.com` 与 `user@qq.com` 视为未变更，走免验证码幂等）。归一后长度 >100 → 按「邮箱格式不正确」400。

请求体：`{ "email": "a@b.com", "emailCode": "123456" }`
- `email`：可选。缺省或空 = 不修改邮箱（只改其他资料时用）；填写时必须邮箱格式。
- `emailCode`：当 `email` 归一后与当前绑定**不同**（新绑定或换绑）时**必填**，须先经 §2.5a 获取；缺失或空 → 400「请先获取邮箱验证码」。email 未提供或归一后与当前相同（未变更）→ 不校验 emailCode（携带即忽略），幂等成功。

绑定校验顺序（任一不过即 400，后续步骤不执行）：
1. email 格式校验——「邮箱格式不正确」；
2. 验证码比对（键 `email:bindcode:{归一 email}`，见 §2.5a）：
   - 键不存在（未获取/已过期/已作废）→「验证码错误或已过期」，**不计失败次数**（过期键已删，往失败键 +1 等于给未来新码预埋障碍）；
   - 存在但不匹配 → 失败计数 +1（键 `email:bindcode:fail:{归一 email}`，TTL 600s，与该验证码同生命周期；重新获取验证码时随旧码一并删除）——同一验证码累计错 **5 次**即删除验证码键与失败键（防暴力猜码，需重新获取）；
   - 刻意**不做**发送前锁定预检（与 §4.5 微信「先查锁」刻意不同：邮箱码按邮箱为键，预检锁定会让攻击者用 5 次故意错猜阻断受害者本人正常绑定）；
3. 全库唯一（忽略大小写、忽略自身）——冲突报「该邮箱已被其他账号绑定」，**同时作废本次验证码与失败键**（防拿到一个验证码去试绑多个目标）；
4. 全部通过 → 删除验证码键与失败键，更新 `email`（存归一后的值）。

响应 `data`：`{ "email": "<归一后的当前绑定邮箱>" }`（幂等路径同样返回当前绑定邮箱）。

### 2.5a 发送邮箱绑定验证码（防邮箱轰炸）

- `POST /api/auth/email/code`　认证：是

请求体：`{ "email": "a@b.com" }`（必填，邮箱格式）。

行为：`email` 按 §2.5 归一规则处理后使用（全部 Redis 键以归一值命名）。SecureRandom 生成 6 位数字验证码 → 存 Redis `email:bindcode:{email}`（TTL 600 秒）→ 向该邮箱发送验证码邮件（模板见 §4.6a）。响应**不回显**验证码。归一后长度 >100 → 按「邮箱格式不正确」400。重复获取**覆盖旧码**，旧码（连同其失败计数键）立即失效；同邮箱在途旧码被新码顶替属预期行为。

频控（防轰炸三道闸，全部返回 429，message 注明原因）：
| 闸门 | 键 | 限制 |
|---|---|---|
| 同邮箱冷却 | `email:code:cd:{email}`（SET NX，TTL 60s） | 60 秒内只能发一次 |
| 同邮箱每日 | `email:code:cnt:{email}:{yyyyMMdd}`（INCR + EXPIRE NX 86400s） | ≤10 次/天 |
| 同用户每日 | `email:code:ucnt:{uid}:{yyyyMMdd}`（同上） | ≤10 次/天 |

判定按序执行，任一不过即 429 且后续步骤不执行：① 同邮箱冷却（SET NX）→ ② 同用户每日（INCR）→ ③ 同邮箱每日（INCR）→ ④ 发送邮件。②排前的原因：攻击者自身配额耗尽时不再消耗目标邮箱的当日额度。②③已 INCR 的计数在 429 时**不回滚**（失败尝试也计入配额，防刷，与 §8 限流器「超限不回滚」语义一致）；①成功而②③拒绝时 60s 冷却同时生效。④ SMTP 发送异常返回 500 兜底，已产生的冷却与计数不回滚。邮件渠道未启用（`channels.mail=false` 或 SMTP 未配置）时返回 400「邮件服务未启用」。

响应 `data`：`{ "expireSeconds": 600 }`。错误：400（邮箱格式错误 / 邮件服务未启用）/ 429（三类频控）。
说明：发送环节**不做**「邮箱是否已被他人绑定」校验（避免被用来枚举注册账号），唯一性在 §2.5 绑定环节校验并作废验证码。

### 2.5b 设置推送渠道偏好（v2.6）

- `PUT /api/auth/notify-channels`　认证：是

请求体：`{ "mail": true, "wechat": true }`（两字段均必填布尔）。控制**邮件/微信两个离线推送渠道**的用户偏好；SSE 在线实时提示不受偏好影响、始终发送（页面在线时天然即时，无打扰语义）。

行为：写入 user 表 `notify_mail`/`notify_wechat`（新用户默认双开）。本接口**不校验**邮箱/微信绑定状态——偏好开启但未绑定时，该渠道继续按「未绑定」规则不可用（§4.6），设置页的灰显/提示留给前端，不作接口约束。并发修改按最后写入胜出（无版本控制），响应为落库后的最终值，前端以响应或 GET /api/auth/me 为准。**v2.9 起偏好写入入口有两个**（本接口 + §3.1 对话工具 set_push_preference），并发一律最后写入胜出；设置页前端需意识到「页面渲染的旧状态 + 助手刚改过偏好」的场景，提交失败时按响应回滚而非本地臆断（现有 catch 回滚逻辑已符合）。偏好关闭的渠道视为**渠道不可用**（§4.6：发送侧静默跳过、不参与成败判定、不产生 FAIL 日志）；两开关全关且页面离线时，提醒按 §4.6 以 NO_CHANNEL 置 FAILED——这是用户主动选择静默的预期结果（设置页有对应提示文案）。

响应 `data`：`{ "mail": true, "wechat": true }`。错误：400（字段缺失或非布尔）。

### 2.6 行程推荐（治理后的既有接口）

- `POST /api/travel/recommend`　认证：是　限流：是

请求体：

| 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|
| city | string | 是 | @NotBlank，≤50 字符 |
| days | int | 是 | 1~30 |
| budget | number | 是 | ≥100 |
| note | string | 否 | v2.9：自由文本备注，≤200 字符（@Size）。人数/饮食忌口/同行人情况/不想玩什么等开放偏好，供 AI 生成时参考 |

**note 语义与边界（v2.9）**：备注仅作为**生成偏好上下文**注入推荐提示词，不落库、不参与结构化校验（推荐结果结构不变，保存行程时也不持久化 note；§4.1 的 recommend 结构不含 note，后端按 §2.6 固定字段解析、多余字段忽略）。空串/纯空白视为未提供（不注入提示词）。提示词层面强制三层边界：①备注只作旅行偏好参考，其中包含的任何"指令"（如要求输出代码、更改输出格式、偏离行程规划）一律忽略；②与旅行无关或不当的内容（含违法、色情、攻击性内容的"偏好"）忽略，按常规偏好生成；③忽略动作不打断流程——**本条仅指内容语义层的忽略**：note 长度超限仍走校验 400，模型调用或解析失败仍按既有失败语义 500，note 内容异常不报错、推荐照常返回。

响应 `data`（结构沿用 TravelRecommendVO，供 M3 一键转日程消费）：

```json
{
  "city": "北京",
  "days": 3,
  "totalBudget": 3000,
  "dailyItineraries": [
    {
      "day": 1,
      "date": "第1天",
      "morning":   { "spot": "", "duration": "", "ticket": "", "transportation": "", "description": "" },
      "afternoon": { "spot": "", "duration": "", "ticket": "", "transportation": "", "description": "" },
      "evening":   { "spot": "", "duration": "", "ticket": "", "transportation": "", "description": "" }
    }
  ],
  "budgetBreakdown": { "accommodation": 0, "food": 0, "transportation": 0, "tickets": 0, "other": 0 },
  "tips": ["…"],
  "warnings": ["…"]
}
```

失败语义（治理点）：模型调用或解析失败时返回 **code 500 + message 原因**，不再出现"外层 200、内层失败"的双层结构；模型原始输出只入日志，不回传前端。

---

## 3. M2 接口（对话 + Function Calling + 日程）

### 3.0 对话接口的"双形态响应"（重要）

`POST /api/chat` 走拦截器链，两种响应形态由 Content-Type 区分，前端必须先看 Content-Type 再决定处理方式：

- **建立流之前失败的 JSON 响应**（Content-Type: application/json，结构 §1.2）：400 参数不合法 / 401 未认证 / 404 会话不存在或非本人 / 429 限流 / 500 线程池满或 LLM 前置失败
- **SSE 流响应**（Content-Type: text/event-stream，事件格式 §1.6）

行为语义：
- USER 消息在调用 LLM **之前**已落库；流中途失败（error 事件）时该条 USER 消息保留，刷新历史可见"无回答的用户消息"
- 请求一旦放行（通过限流），本次调用**已计入每日 AI 配额**，error 不退还配额

### 3.1 对话（多轮 + Function Calling）

- `POST /api/chat`　认证：是　限流：是（计入每日 AI 配额）

请求体：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| conversationId | long | 否 | null 或缺省 = 新建会话（title 取消息前 20 字） |
| message | string | 是 | 1~2000 字符 |

SSE 事件序列（判别字段统一为 type，完整格式见 §1.6）：

```
data: {"type":"start","conversationId":123}
data: {"type":"chunk","content":"…"}          ← N 条增量
data: {"type":"done","conversationId":123}    ← 正常结束（回答已落库）
data: {"type":"error","message":"原因"}        ← 中途异常（回答未落库；含线程池排队拒绝场景，message="当前对话人数较多，请稍后重试"）
```

行为说明：
- **会话上限**（`chat.max-conversations`，默认 10）：新建会话时若已达上限，自动删除最旧会话及其全部消息，并在流中发一条 `data: {"type":"notice","message":"会话数已达上限，已自动清理最旧会话"}`（前端 toast）
- **单会话轮数上限**（`chat.max-rounds-per-conversation`，默认 20）：指定 conversationId 对话时，若该会话 USER 消息数已达上限，返回 400，message="本会话已达 20 轮上限，请新建会话"；不做后台压缩
- 服务端保存 USER 消息与最终 ASSISTANT 回答；工具调用中间轨迹存 tool_calls_json，不回传前端
- 多轮上下文：回放最近 10 条 USER/最终 ASSISTANT 消息
- Function Calling：模型可调用 **5 个工具**操作当前登录用户的数据：
  - 日程类（v2.5 起）：`create_schedule` / `delete_schedule` / `query_schedules`
  - 推送偏好类（**v2.9 新增**）：
    - `set_push_preference`：`{"channel": "mail"|"wechat", "enabled": true|false}`——设置**单渠道**推送偏好。channel/enabled 均必填；channel 仅接受 mail|wechat 两枚举值，缺失或越界、enabled 非布尔时返回 `{"ok":false,"error":"…"}`（不打断对话循环，模型自行纠偏）。写入语义：**工具层**读当前值合并写入、只动目标渠道（区别于 §2.5b HTTP 层的两字段全量必填；底层同为 user.notify_mail/notify_wechat 两列）；不校验绑定状态，未绑定时开启仍按「未绑定」不可用。结果 `{"ok":true,"message":"…","mail":…,"wechat":…,"mailBound":…,"wechatBound":…}`——**绑定状态随结果回传**（mailBound=user.email 非空，wechatBound=user.wechat_openid 非空）：开启未绑定渠道属于"开关已开但收不到"的静默失败，对话场景没有设置页灰显文案承担告知，由工具结果把绑定事实交给模型转达
    - `get_push_preference`：无参——返回 `{"ok":true,"mail":…,"wechat":…,"mailBound":…,"wechatBound":…}`（绑定判定同上：mailBound=user.email 非空，wechatBound=user.wechat_openid 非空；供模型回答「为什么收不到微信推送」类问题）
- 会话归属校验：conversationId 不属于当前用户返回 404
- **回答范围与系统能力告知（v2.9 系统提示词固化，评审修订）**：以旅行/出行场景为核心的请求（行程规划、日程操作、提醒，以及旅行衍生的文案与介绍协助）均应协助；仅与旅行及本系统功能均无关的话题（写代码、时事闲聊、医疗/法律/投资建议、角色扮演等）礼貌说明业务边界并引导回旅行场景，不编造回答。助手应能回答系统基本事实——推送渠道三件：页面在线实时提示（免绑定免设置、始终发送）与邮件/微信推送（**后两者**可在设置页绑定并开关，也可由助手经 set_push_preference 代为开关；未绑定的渠道开启开关也收不到，需先绑定）；会话容量为**最多保留 10 个会话（超出自动删除最旧）**、单会话 20 轮（非"每日 10 个"限额）；另有每日 AI 配额（**对话与行程推荐共用同一配额池**，行程推荐同样消耗），用尽当日不可再发起，余量见个人页；行程推荐入口在「行程」页（可保存为行程自动生成日程）。提示词中的容量/配额数字由配置项动态注入（chat.max-conversations 等），不硬编码

### 3.2 会话列表

- `GET /api/conversations?limit=50`　认证：是

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| limit | int | 否 | 默认 50，按 updatedAt 倒序取前 N 条；M2 不做游标分页 |

响应 `data`：

```json
[ { "id": 1, "title": "帮我安排北京三日游", "updatedAt": "2026-11-25 12:00:00" } ]
```

### 3.2a 删除会话（v2.5，历史会话列表手动删除）

- `DELETE /api/conversations/{id}`　认证：是

行为：物理删除该会话及其全部消息（与 §3.1 上限自动清理同一实现）。仅允许删除本人的会话：不存在或属于他人一律 404（全局防探测语义，不区分两种情况）；重复删除已删除的会话同样 404。

响应 `data`：`{ "deleted": true }`。

联动说明：
- 手动删除不触发 §3.1 的上限 notice（那是自动清理专有提示）。
- 删除**正在对话中**的会话：前端必须**先 abort 对话流连接（用户主动停止，静默收场不做对账）待本轮收尾，再调用本接口**——服务端断连即取消上游。
- 删除**当前打开**的会话（conversationId 指向它，无论是否正在流式）成功后：前端须将 conversationId 置 null、清空消息区，回到新对话态。
- 前端对本接口的 404 一律按「已不存在」处理（静默移除/刷新列表，**不报错弹窗**，双击竞态下第二个 DELETE 得 404 属预期）。与 §3.7 日程删除幂等 200 的差异是语义必然：会话是**物理删**（行已消失，「已删除」与「从未存在」不可区分，回 200 会击穿 §1.3 防探测不变量），日程是逻辑删。
- 即使严格遵循先停流时序，在途工具轮事务仍可能在删除提交后落库孤儿行——属预期（消息表无外键、读路径经会话归属校验不可见），无需告警或补偿。
- 被自动清理（§3.1）的会话若仍被其他端打开，后续对其请求返回 404 同属预期；notice 后前端以 §3.2 重新拉取列表为准（回合结束已有统一刷新）。

### 3.3 会话历史消息

- `GET /api/conversations/{id}/messages`　认证：是

M2 返回该会话全部可回放消息，不分页（单条 content 受输入 2000 字符与模型输出长度约束）。

响应 `data`（按时间正序；仅含 USER 与最终 ASSISTANT，工具轨迹不返回）：

```json
[
  { "id": 1, "role": "USER", "content": "你好", "createdAt": "2026-11-25 12:00:00" },
  { "id": 2, "role": "ASSISTANT", "content": "你好！想去哪儿玩？", "createdAt": "2026-11-25 12:00:05" }
]
```

错误：404（会话不存在或非本人）。

### 3.4 日程查询

- `GET /api/schedules?from=2026-11-25&to=2026-12-01&status=PENDING`　认证：是

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| from | date | 否 | yyyy-MM-dd，按 start_time 过滤下界（含当天 00:00:00） |
| to | date | 否 | yyyy-MM-dd，上界（含当天 23:59:59） |
| status | string | 否 | 不传 = 默认排除 CANCELLED；传具体值则只查该状态（仅接受下方枚举） |

**枚举表**（前端状态标签/筛选下拉的依据）：

| status | 含义 | 终态? |
|---|---|---|
| PENDING | 待提醒/进行中 | 否 |
| DISPATCHED | 已投递到提醒链路 | 否 |
| SENT | 提醒已发送，日程未完成 | 否 |
| FAILED | 提醒发送失败，或已错过未投递（remind_at 已过） | 否 |
| CANCELLED | 已取消 | 是 |
| DONE | 已完成 | 是（可经 §3.9 reopen 回退为 PENDING） |

source 枚举：`AI`（对话创建）/ `MANUAL`（页面创建）/ `TRIP`（行程生成）。

响应 `data`（按 startTime 正序）：

```json
[
  {
    "id": 10, "tripId": null, "title": "故宫博物院", "type": "SPOT",
    "startTime": "2026-11-26 09:30:00", "endTime": null,
    "location": "北京市东城区景山前街4号", "note": "提前网上购票",
    "remindMinutesBefore": 60, "remindAt": "2026-11-26 08:30:00",
    "status": "PENDING", "source": "AI"
  }
]
```

### 3.4a 一键清理过期日程

- `POST /api/schedules/cleanup`　认证：是

仅将 **FAILED** 且 start_time 早于今日 00:00 的日程批量置为 CANCELLED（逻辑删），返回清理数量。**DONE 是受保护历史，不参与清理**（误操作可经 §3.9 恢复），其视觉清理由前端"历史日程折叠"承担。响应 `data`：`{ "cleaned": 12 }`。

### 3.5 新建日程

- `POST /api/schedules`　认证：是

请求体：

| 字段 | 类型 | 必填 | 校验/默认 |
|---|---|---|---|
| title | string | 是 | 1~100 字符 |
| type | string | 否 | SPOT/FOOD/TRANSPORT/TODO/DEPART，默认 TODO |
| startTime | datetime | 是 | yyyy-MM-dd HH:mm:ss |
| endTime | datetime | 否 | 若提供，必须**晚于** startTime；等于或早于 startTime 返回 400 |
| location | string | 否 | ≤200 |
| note | string | 否 | ≤500 |
| remindMinutesBefore | int | 否 | 0~1440，默认 30 |
| ~~remindEnabled~~ | — | v2.10 **删除**：该字段自 M3 起从未被扫描器读取（「关单项提醒」实际不生效，属误导性死字段），DTO/Entity/VO/schema 同步移除，DB 列 remind_enabled DROP。关闭提醒的正确方式=删除日程或改期 |

响应 `data`：完整日程对象（同 §3.4，status=PENDING，remindAt=start_time−remindMinutesBefore，source=MANUAL）。

### 3.6 修改日程

- `PUT /api/schedules/{id}`　认证：是

**部分更新语义**：请求体字段全部可选；**缺省（不提供）= 保持原值**；`location`/`note` 传空串 `""` 表示清空；`title` 不能清空（传空串返回 400）；`endTime`/`remindMinutesBefore` 不支持清空，只能更新为合法值。

联动规则：修改 startTime 或 remindMinutesBefore 时重算 remindAt；原状态为 **PENDING / DISPATCHED / SENT / FAILED** 时一律重置回 PENDING（新时间需要重新提醒）；终态 CANCELLED / DONE 修改返回 400。
错误：404（非本人/不存在）、400（校验失败或终态）。

### 3.7 删除日程（逻辑删）

- `DELETE /api/schedules/{id}`　认证：是

置 status=CANCELLED，响应 data: null。**幂等**：对已 CANCELLED 的日程重复删除仍返回 200（双击删除安全）。错误：404（非本人/不存在）。**v2.8 起放开 DONE 可删除**（用户拍板：完成态的历史不再是删不掉的垃圾，页面清爽优先；历史回看由「历史日程」折叠承担，用户可自行清理全部不想要的日程）。

### 3.8 完成日程

- `POST /api/schedules/{id}/complete`　认证：是

置 status=DONE（PENDING/DISPATCHED/SENT/FAILED 均可转入），响应 data: null。错误：404（非本人/不存在）、400（已 CANCELLED 或已 DONE，message 区分）。

### 3.9 恢复日程（撤销"完成"）

- `POST /api/schedules/{id}/reopen`　认证：是

仅 DONE → PENDING（误操作恢复场景）；remind_at 不变，后续行为由到期时刻决定：
- remind_at 在未来 → 到点照常提醒（与新建日程一致）
- remind_at 已过（超过 2 分钟宽限窗）→ 下一轮扫描置 FAILED（detail=MISSED），**不补发**，前端确认框应提示"提醒时间已过，恢复后不再提醒"
响应 data: null。错误：404（非本人/不存在）、400（非 DONE 状态）。

## 4. M3 接口（行程落库 + 提醒链路 + 微信回调）

### 4.1 保存行程（推荐结果一键转日程）

- `POST /api/trips`　认证：是　限流：否（不调 LLM）

请求体：

| 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|
| city | string | 是 | ≤50 |
| days | int | 是 | 1~30 |
| budget | number | 是 | ≥100 |
| startDate | date | 是 | yyyy-MM-dd，出发日期，**不得早于当日**（服务器时区 Asia/Shanghai），违反 400 |
| recommend | object | 是 | POST /api/travel/recommend 的 `data` 原样回传（结构见 §2.6） |

行为与生成规则：
- recommend 内层 city/days/budget 必须与外层字段一致，不一致 400
- 日程仅按 recommend.dailyItineraries **数组顺序**生成（忽略其 day 字段取值）：第 i 个元素（0 起）映射日期 startDate+i，三时段固定映射 09:30 / 14:00 / 19:00
- 时段 spot **trim 后非空**才生成（type=SPOT，note=description，提前 30 分钟提醒，source=TRIP）
- dailyItineraries 为空或没有任何可生成时段 → 400，message="推荐结果不含可生成日程"
响应 `data`：

```json
{ "tripId": 1, "scheduleCount": 9, "trip": { "id": 1, "city": "北京", "days": 3, "budget": 3000, "startDate": "2026-11-28", "status": "DRAFT" } }
```

错误：400（校验失败）。

### 4.2 行程列表 / 详情

- `GET /api/trips`　认证：是　响应 `data`：`[{ "id":1, "city":"北京", "days":3, "budget":3000, "startDate":"2026-11-28", "status":"DRAFT", "createdAt":"…" }]`（按 createdAt 倒序）
- `GET /api/trips/{id}`　认证：是　响应 `data`：`{ trip: {...同上}, schedules: [ §3.4 日程对象 ] }`（schedules 按 startTime 正序）
错误：404（非本人/不存在）。

**行程状态枚举**：DRAFT（可确认）/ CONFIRMED（可删除）/ CANCELLED（已取消，列表与详情默认排除）/ FINISHED（预留，本期不可达）。
M3 不提供行程修改与分页，列表全量返回；行程可删除（见 §4.3a），删除后可重新生成新行程。

### 4.3 确认行程

- `POST /api/trips/{id}/confirm`　认证：是

行为：status DRAFT → CONFIRMED；服务端以 trip.status 条件更新（CAS）保证并发双击下仅生成一条行前提醒，非 DRAFT（含并发落败方）一律 400，message="行程已确认"。同时生成一条 DEPART 类型行前提醒：start_time=startDate 00:00，remindMinutesBefore=240（即出发前一天 20:00 触发）；若该时刻已过去（出发日就是今天），直接以 status=FAILED 落库不补发。DEPART 日程被用户删除后不可经 confirm 重建，如需提醒请通过 POST /api/schedules 手工创建 type=DEPART 日程。
响应 `data`：`{ "tripId": 1, "status": "CONFIRMED", "departReminderId": 10 }`。
错误：404 / 400。

### 4.3a 删除/取消行程

- `DELETE /api/trips/{id}`　认证：是

DRAFT / CONFIRMED 均可删除：trip CAS 置 CANCELLED（终态），同时**单条 UPDATE** 把该行程下 status ∈ {PENDING, DISPATCHED} 的日程批量置 CANCELLED（未触发的提醒一并取消，避免与扫描器 CAS 交错；已发送/已完成的保留为历史）。幂等：已 CANCELLED 的重复删除仍返回 200（cancelledSchedules=0）。删除后 GET /api/trips/{id} 返回 404（与日程逻辑删语义一致）；FINISHED 行程删除返回 400。响应 `data`：`{ "tripId": 1, "cancelledSchedules": 6 }`。错误：404（非本人/不存在）、400（FINISHED）。

### 4.4 提醒长连接

- `GET /api/notify/stream`　认证：是（Authorization 头，前端用 fetch 封装，不用 EventSource）

Content-Type: text/event-stream。每 30 秒一次 `: ping` 心跳注释行。到期提醒推送：

```
data: {"type":"reminder","scheduleId":10,"title":"去故宫","content":"您 09:00 有安排：去故宫（提前 60 分钟提醒）"}
```

连接语义：断开后前端自动重连（带 token），指数退避 1s 起上限 30s 加随机抖动；连续 90s 未收到任何字节（含心跳）视为断线。**断线期间到期的提醒不补推**（SSE 写入成功即计 SENT，无回执接口）；前端重连成功后须调用 GET /api/schedules?from=<今天> 做状态对账，把 remindAt 已过且 status∈{SENT,FAILED} 的日程补提示给用户。未在线时提醒走邮件/微信渠道，SSE 渠道跳过。

### 4.5 微信测试号回调（免登录，微信签名校验）

- `GET /api/wechat/callback?signature=&timestamp=&nonce=&echostr=`　微信服务器验证：token、timestamp、nonce 三值**字典序排序后拼接**，sha1 后与 signature（小写）比对，通过则 HTTP 200 纯文本返回 echostr
- `POST /api/wechat/callback`　微信事件/消息推送（XML，明文模式；涉及字段：ToUserName/FromUserName/CreateTime/MsgType/Event/Content）。须在 5s 内响应且处理幂等（微信超时会重试 3 次），处理不了的场合回复文本 "success"

行为：关注事件（subscribe）→ 回复绑定指引文本；文本消息匹配绑定格式 → 用户与码校验通过后把 FromUserName(openid) 写入该用户（重复绑定=覆盖），回复绑定成功/失败文本；其余回复 "success"。渠道未配置（wechat.token 为空）时一律返回 "channel disabled"。

**绑定消息格式（v2.7 起主格式为纯验证码，去除空格分隔的手输负担）**：
- **主格式**：直接发送 6 位验证码（如 `368451`）——服务端按在途验证码反查归属用户后绑定；设置页提供一键复制按钮
- **换绑抢占**：该 openid 已绑定在其他账号时，自动解除旧账号的持有并写入新账号（「一个微信只绑一个账号，后绑覆盖先绑」的延伸语义）；并发抢绑残余冲突回 success 由用户重试
- **兼容格式**：`绑定 {username} {验证码}`（v2.6 及之前的历史格式，仍接受）
- 归属反查实现（v2.10 改造）：Redis `wx:bindcode:{code} → 用户id` 反查键直接 GET（O(1)；旧 KEYS 全库扫描为 O(N) 阻塞命令已废除）。命中 → 校验归属后绑定；零命中/归属不符 → 失败计数 +1（按 openid，超 5 次锁定至码过期）并回复统一失败文案。重新生成码即作废旧码（旧码键立即删除，泄露止损）；生成时撞上在途码自动换号重试
- 失败计数对两种格式共用（同一 openid），文案统一防用户名枚举

**绑定码接口**（防劫持：任何人都能给测试号发消息，因此绑定必须凭本人设置页生成的限时码）：

- `POST /api/wechat/bindcode`　认证：是　响应 `data`：`{ "code": "864213", "expireMinutes": 10 }`
- 绑定码存 Redis 10 分钟有效，每次生成覆盖旧码；未使用的码过期自动失效
- **已绑定用户也可生成**（生成动作无绑定状态限制）：码被任一微信消费即按「换绑抢占」覆盖 openid（§4.5）；前端在已绑定态引导先解绑再生成，避免误绑其他微信
- `POST /api/wechat/unbind`　认证：是　**解绑**：清空当前用户 wechat_openid 恢复未绑定态（解绑后原微信收不到推送，可重新生成绑定码绑定任意微信）；响应 `data`：`{ "unbound": true }`

### 4.6 提醒渠道说明（非接口，前端/运维需知）

| 渠道 | 启用条件 | 未启用时行为 |
|---|---|---|
| SSE | 页面在线即生效（始终可用，**不受 §2.5b 偏好影响**） | 离线则本渠道跳过 |
| 邮件 | `channels.mail=true` 且邮箱已配置且用户绑定了 email 且用户偏好开启（§2.5b） | 静默跳过 |
| 微信 | `channels.wechat=true` 且测试号参数齐全且用户已绑定 openid 且用户偏好开启（§2.5b） | 静默跳过 |

渠道判定规则（状态机闭环）：
- 渠道 disabled、用户未绑定**或用户偏好关闭（§2.5b）**→ 视为**渠道不可用**，不参与成败判定
- 全部渠道不可用 → 消费端直接置 status=FAILED 并写 reminder_log(detail="NO_CHANNEL")，不投递重试
- 有渠道可用且全部发送失败 → MQ 进程内重试 3 次 → 耗尽后回写 status=FAILED，消息进死信队列 `travel.reminder.dlq`（RabbitMQ 控制台 http://localhost:15672 可查）
- 任一可用渠道发送成功 → status=SENT
- DLQ 人工重放前必须先把该日程 status CAS 重置为 PENDING，再走正常链路
- **DISPATCHED 卡死兜底扫描（v2.10）**：扫描器每轮先做一步救援——`status=DISPATCHED 且 updated_at 早于应用时钟 now−5 分钟` 的记录重置回 PENDING 并写 reminder_log(channel="NONE", status="FAIL", detail="DISPATCHED_TIMEOUT")。关闭的丢失窗口：CAS 抢占后进程崩溃 / broker confirm 丢失导致永久滞留 DISPATCHED。5 分钟阈值大于消费端重试总时长（3×2s + 渠道耗时 <30s），在途消息不会被误伤；重置后 updated_at 刷新，天然进入下一轮正常扫描

### 4.6a 邮件内容模板（v2.4）

**微信模板消息占位符规范**：字段 first=「⏰ 行程提醒」、keyword1=事项标题、keyword2=开始时间（MM月dd日 HH:mm）、remark=提前量与署名；**每个占位符独占一行**、**模板标题不支持动态字段**

**提醒邮件**（MailNotifyChannel）：
- 主题与正文中的时间一律为**日程开始时间 start_time**（提前量由正文「提前 n 分钟提醒」表达，不使用 remind_at——收件人关心的是日程何时开始，不是提醒何时发出）
- 主题：`AI 旅行管家提醒｜MM月dd日 HH:mm {事项标题}`，标题超 20 字符截断追加「…」（邮件客户端可视区普遍仅 30~50 字，关键的时间前缀不能被挤出）；例：`AI 旅行管家提醒｜11月26日 09:30 故宫博物院`（start_time 09:30、提前 60 分钟）
- 正文（纯文本，标题保留完整不截断）：
  ```
  您有一个安排即将开始：

  时间：yyyy-MM-dd HH:mm（24 小时制，Asia/Shanghai）
  事项：{标题}（提前 {n} 分钟提醒）

  —— AI 旅行管家
  ```
- 时间一律格式化为 `yyyy-MM-dd HH:mm`，禁止 `LocalDateTime.toString()` 的 `T` 形式直接输出

**验证码邮件**（§2.5a）：
- 主题：`AI 旅行管家｜邮箱绑定验证码`
- 正文：
  ```
  您的邮箱绑定验证码：{6 位数字}

  验证码 10 分钟内有效，请勿透露给他人。
  如非本人操作，请忽略本邮件。

  —— AI 旅行管家
  ```

## 5. 修改记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-11-28 | v2.10 | 稳健性轮：§2.2 登录防爆破（IP+用户名 10 分钟窗口 5 次失败锁 429，成功清零）；§3.1 error 场景补线程池排队拒绝文案；§4.6 补 DISPATCHED 卡死兜底扫描（updated_at < now−5min 重置 PENDING + DISPATCHED_TIMEOUT 日志）；§3.4/3.5/3.6 删除 remindEnabled 死字段（从未被扫描器读取，DB 列 DROP）；§4.5 绑定码存储改 code→uid 反查键（废除 KEYS 全库扫描，用户名格式改为码反查后校验归属） |
| 2026-11-26 | v2.9 | 用户反馈十二（F15）：§2.6 行程推荐加可选 note 备注（≤200 字，仅作生成偏好上下文、不落库，含提示词三层边界：无关指令忽略/违规内容忽略/忽略不报错照常返回）；§3.1 Function Calling 3→5 工具——新增 set_push_preference（单渠道偏好开关，语义等价设置页单开关：读旧值合并写入、不校验绑定）与 get_push_preference（含绑定状态，支撑"为什么收不到推送"类问答）；系统提示词固化回答范围（仅旅行与本系统功能，无关话题礼貌拒绝）与系统能力告知（三渠道/绑定方式/配额/入口） |
| 2026-11-26 | v2.8.1 | 用户反馈十一：§4.5 新增解绑端点（POST /api/wechat/unbind，清 openid 恢复未绑定态）；绑定码补「已绑定也可生成」语义（码被消费即按换绑抢占覆盖 openid，前端引导先解绑再生成）；微信模板字段含义与逐行规范补入 §4.6a（标题不支持动态字段）；Bark/PushPlus 列入开发计划（待办 §2a） |
| 2026-11-26 | v2.8 | 用户反馈十：§3.7 删除日程放开 DONE 可删（原 400 保护按用户拍板移除，页面清爽优先；逻辑删语义不变，历史折叠仍承担回看） |
| 2026-11-26 | v2.7 | 用户反馈九：§4.5 绑定消息主格式改为**直接发送 6 位验证码**（去除「绑定 用户名 码」的空格分隔手输负担，服务端按 wx:bindcode:* 扫描反查归属；零匹配计失败、多匹配不计失败提示重新生成）；兼容格式保留；设置页加一键复制与关注测试号二维码 |
| 2026-11-26 | v2.6.1 | 契约评审修订（1×P1+4×P2 全采纳）：§4.6 渠道判定规则补「用户偏好关闭（§2.5b）→渠道不可用」（消除矩阵与规范文字脱节）；§4.6 SSE 行标注不受偏好影响；§2.5b 补「不校验绑定状态（未绑定按不可用规则）」与「并发最后写入胜出」；设置页需含双关后果提示文案（前端任务） |
| 2026-11-26 | v2.6 | 用户反馈八：新增 §2.5b 推送渠道偏好（PUT /api/auth/notify-channels，user 表加 notify_mail/notify_wechat 列默认双开，偏好关闭=渠道不可用静默跳过不产 FAIL、双关+离线按 NO_CHANNEL FAILED 属预期）；§2.4 me 响应加 notify 字段；§4.6 邮件/微信可用判定追加用户偏好条件（SSE 不受偏好影响） |
| 2026-11-26 | v2.5.1 | 契约评审修订（1×P1+4×P2 全采纳）：§1.6 补「用户主动停止不视为异常中断、静默收场不对账」豁免（此前停止语义只存在于实现、契约缺失）；§3.2a 联动说明写实（先 abort 待收尾再删/空闲态删除后 conversationId 置空回新对话态/前端对 404 静默处理与 §3.7 差异源于物理删/孤儿行预期声明/跨端自动清理 404 属预期） |
| 2026-11-26 | v2.5 | 用户反馈七：新增 §3.2a 删除会话（DELETE /api/conversations/{id}，物理删除会话及消息、归属 404 防探测、重复删除 404、手动删除不发 notice、删除当前会话须先停止对话流的前端联动约定） |
| 2026-11-26 | v2.4.1 | 契约评审修订（1×P0+4×P1+4×P2 全采纳）：新增 email 归一规则（trim+转小写，统一用于全部 Redis 键/验证码比对/唯一性/存储/未变更判定，§2.1 注册同步归一，修 P0-1 大小写分裂破坏主流程与频控闸）；§2.5 失败计数器落地（`email:bindcode:fail:{email}` TTL 600s 与码同生命周期、键不存在不计次、刻意不做发送前锁定预检防 grief）；§1.3 全局 429 行补邮箱频控；§2.5a 频控判定顺序与副作用写死（①冷却→②同用户→③同邮箱→④发送，②③不回滚）；§4.6a 时间口径钉死为 start_time、主题标题超 20 字截断；email ≤100 字符、渠道未启用 400、重发覆盖旧码、幂等路径响应体等 P2 补全 |
| 2026-11-26 | v2.4 | 用户反馈六（邮箱渠道启用后实测反馈）：新增 §2.5a 发送邮箱绑定验证码（POST /api/auth/email/code，同邮箱 60s 冷却 + 同邮箱/同用户每日 ≤10 次三道频控防轰炸，SecureRandom 6 位 10 分钟有效）；§2.5 绑定改为需验证码（未变更免验证码幂等、错误 5 次作废、唯一性冲突作废验证码防跨目标重放、校验顺序固定）；新增 §4.6a 邮件内容模板（提醒邮件带「日期 时间 事项」主题，修 LocalDateTime.toString() 的 T 格式；验证码邮件模板） |
| 2026-11-25 | v1 | 初版：全局约定 + M1 接口 |
| 2026-11-25 | v1.1 | §2.3 登出幂等措辞修正（重复登出 401 属预期）；§2.6 示例移除 success 字段（双层 success 治理）；错误码补充说明：HTTP 状态码与 body.code 一致 |
| 2026-11-25 | v2.3.1 | 评审修订：§1.6 补 notice 事件行（start 之后至多一条）；§3.4a 清理范围收窄为仅 FAILED（DONE 受保护）；§4.3a 补 CANCELLED 详情 404/FINISHED 400/级联单条 UPDATE/CAS 写法 |
| 2026-11-25 | v2.3 | 用户反馈二：§3.1 会话上限自动清理 + notice 事件、单会话轮数上限 400；新增 §3.4a 一键清理过期日程、§4.3a 删除/取消行程（级联取消未触发日程、行程枚举加 CANCELLED） |
| 2026-11-25 | v2.2.1 | 评审修订：扫描器落实"错过即 FAILED"（2 分钟宽限窗，MISSED 日志）；§3.4 DONE 脚注；§3.9 语义按恢复时刻区分；stage 事件改为每个工具调用各发一条 |
| 2026-11-25 | v2.2 | 用户验收反馈：§1.6/§3.1 新增 stage 事件（Function Calling 进度时间线）；新增 §3.9 reopen 恢复日程（DONE→PENDING，误操作撤销） |
| 2026-11-25 | v2.1 | M3 契约评审修订（2×P0+4×P1+2×P2）：notify 重连退避与对账规则、reminder 事件加 eventId/remindAt、渠道矩阵闭环（不可用/全失败/SENT/DLQ 出口）、转日程按数组序映射+内外一致校验+空结果 400、startDate 不得早于当日、行程状态枚举、confirm CAS、微信签名/绑定码防劫持、FAILED 语义合并、1 分钟扫描延迟声明 |
| 2026-11-25 | v2 | M2 契约评审修订（1×P0+8×P1+1×P2）：SSE 增加 start 事件并统一 type 判别字段、done 携带 conversationId 且为唯一正常结束标志；对话流增加 15s 心跳；§3.0 失败路径与配额/落库语义补全；§3.2 增加 limit 参数；§3.4 增加状态/来源枚举表；§3.5 endTime 校验文案修正；§3.6 部分更新语义（空串清空/缺省保原值）与 SENT/FAILED 重置规则；§3.7 删除幂等；§3.8 终态 400 |
