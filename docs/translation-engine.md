# 检测、翻译协议与配置

## 自然语言检测

`EnglishDetector` 消费同次捕获的输入：去除精确 `<xN>` 标签、以空格屏蔽 `<keepN/>`，再过滤 URL、反引号代码及自定义 emoji。必须有可提取 TEXT、至少 4 个 Unicode 字母，字母占非空白字符至少 0.5。不因存在代码块或 opaque/TEXT 节点数量比例否决混合正文。

目标文字体系命中阈值为字母的 0.2；中文目标还要求没有假名/韩文音节，日文目标遇假名即跳过。拉丁字母语言等共享文字体系无法可靠区分，纯汉字日文、短文和混合语言也存在误判。此处是脚本筛选，不是完整语言识别模型。

宿主已解析的 Annotated 代码、提及、时间戳等节点仅提供位置标记，不读取内部内容。纯保留节点没有自然语言，不触发翻译；Primitive 沿用已有反引号过滤，不保证识别所有原始 Markdown 代码形式。

## 协议与网络

| `provider_protocol` | 基础地址后追加端点 | 响应方式 |
|---|---|---|
| `openai-compatible`（默认） | `/chat/completions` | JSON `choices.0.message.content` 或 SSE delta |
| `openai-responses` | `/responses` | JSON message/output_text 或 SSE response.output_text.delta |
| `anthropic-messages` | `/messages` | JSON text 块或 SSE；请求 `max_tokens=8192` |

请求按所选协议构造，使用系统 `org.json` 和 `HttpURLConnection`。配置校验解析一次额外请求头，实际请求复用并追加协议认证头。默认提示词按目标语言生成；自定义提示词不随目标语言改写。协议层去除提示词首尾空白后，统一附加行段及保留标记规则。

每次请求只创建一个协议 decoder，不切换解析模式。`SseReader` 以空行提交完整事件，支持多行 data、event、注释和 CR/LF/CRLF。Compatible 必须收到 `[DONE]`，Responses 必须收到 `response.completed`，Anthropic 必须收到 `message_stop`；结束后不等待 EOF。缺结束事件、已识别的错误帧、异常停止原因、所读取文本字段类型错误会失败，不返回该请求已收集的部分译文。这些检查不构成完整响应 schema 校验。包装标签回显属于 `Segments` 译文防损坏校验：实际翻译及翻译测试拒绝，连接测试不执行该内容校验。

非流式 Chat 必须显式返回 `finish_reason:"stop"`，并拒绝非 null 的 `message.refusal`、`tool_calls` 和 `function_call`；Anthropic 必须返回字符串 `stop_reason:"end_turn"` 或 `"stop_sequence"`，只拼接 text 内容块，跳过非对象及非 text 项。

Chat 与 Anthropic SSE 必须收到各自结束事件，但不强制出现停止原因；原因缺失、null 或空字符串时不检查取值，非空时与 JSON 共用正常停止原因校验。Chat SSE 只提取 `delta.content`，当前不检查 `delta.refusal`、`tool_calls` 或 `function_call`，不能把结束事件当作这些字段均已校验的证明。

Responses 按 `output_index`、`content_index` 和 `item_id` 收集文本增量，校验递增事件序号，并逐段核对完成响应；按 output/content 顺序拼接全部 `message/output_text`，跳过 reasoning、工具等非 message output 项，拒绝 message 内容中的 refusal、流式 refusal 事件、failed、incomplete 和完成文本不一致。流式和非流式共用同一个完成文本提取操作，不用 `.done` 快照重复追加或补齐缺失增量。事件格式参见 [OpenAI 流式响应文档](https://developers.openai.com/api/docs/guides/streaming-responses)。

HTTP 累计响应最多 1 MiB 字节，decoder/SSE 累计最多 1 MiB UTF-16 单位，UTF-8 严格校验，禁止跟随重定向。非 2xx 读取有界 body 后按 HTTP 状态分类。普通请求在术语包未启用时保留正文给设置页测试结果和启用的项目日志。精翻批次与启用术语包的请求只保留错误类型和 HTTP 状态。引擎在配置校验和构造请求体前登记 `Http.Request`，取消与连接 attach 原子协调，finally 才释放。实际 worker 是否退出由生命周期另行确认。

精翻以同一频道或私信内的待译正文组成一次请求。供应商的用户输入字段承载 `messages` 数组，每项为 `id` 与 `source`，并可带匹配的 `glossary`。请求内编号如 `m1` 用于区分正文，响应按 `translations` 数组中的 `id` 配对 `translation`。完成供应商解码后再校验数量、编号、每条译文的结构和保留标记，并在对应原文仍有效时分别回填。缺项、重复编号、未知编号或无效译文会使整批失败。

## 调度、缓存与错误

等待队列、状态仓库、译文缓存各有独立的 512 上限。并发实际限制为 1–8，默认 4；满队列明确失败。请求和交付都核对任务所有权，旧结果不得覆盖新消息/编辑版本。失败后只有用户点击才重新请求，无自动重试。

缓存键含输入及影响结果的配置版本；API URL、模型、目标语言、协议、流式、系统提示词、凭据指纹变化使旧结果失效。日志开关不影响请求版本、缓存或任务状态。密钥及额外请求头仅以 SHA-256 指纹参与版本编码，不以原值进入指纹字段。

错误类别为 `CONFIG / NETWORK / TIMEOUT / HTTP / RATE_LIMIT / PARSE / EMPTY / CANCELLED / UNKNOWN`。未知异常仅保留类型，服务端停止理由使用固定描述。请求校验失败不缓存；原生重建拒绝时删除对应缓存、标记失败并保留宿主原树。

## 设置与持久化

| 键 | 默认值 | 含义 |
|---|---|---|
| `auto_translate_enabled` | true | 自动翻译开关 |
| `logging_enabled` | false | 项目日志开关，不参与翻译版本 |
| `translation_scope` | 默认翻译、无规则 | 单一 JSON 对象：`defaultTranslate`、`servers`、`channels`；规则含字符串 `id/label` 和布尔 `translate` |
| `context_enabled` | false | 精翻模式开关 |
| `context_characters` | 100 | 每批正文 Unicode 字符上限，允许 1–1000 |
| `api_url` | `https://api.openai.com/v1` | 基础地址，自动追加协议端点 |
| `api_key` / `extra_headers` | 空 | 密钥及每行 `Name: Value` 的额外头 |
| `api_model` | `gpt-4o-mini` | 模型 |
| `target_lang` | `简体中文` | 目标语言；旧列表外值继续保留 |
| `provider_protocol` | `openai-compatible` | 上表三种协议之一 |
| `streaming_enabled` | true | 三种协议共用的流式请求开关 |
| `system_prompt` | `Config.DEF_SYSTEM_PROMPT` | 规则式翻译提示词 |
| `timeout_ms` | 30000 | 正整数；HTTP 实际至少 1000 ms |
| `max_concurrency` | 4 | 正整数；调度器实际限制为 1–8 |

缺省键才使用默认值。显式错误类型、未知协议、必填空白或非正整数使快照无效；空密钥和空额外请求头有效。错误快照不能回填成可保存默认表单，只有用户确认重置才能删除已保存配置。

`ConfigurationSync` 是保存、重置、导入和导出的唯一策略。先持久化模块私有 SharedPreferences；本地尚未初始化时从 remote 导入，确认初始化后向 remote 导出。初始化标记随成功事务提交。`PreferenceCommit` 保存失败恢复原键快照并仍报告失败；恢复持久化也失败时本进程阻止读取/同步，直到显式重试成功，不自动更换权威来源。

设置页在既有持久化 executor 上读取和提交配置；包含 poison 检查的权威快照读取也在后台完成，页面重建不会在主线程等待另一个页面的提交锁。`SettingsPersistence` 统一处理读取及停止期间的延后交付；首次读取完成前禁用配置操作。页面弱订阅进程级服务连接，仅未保存草稿写入实例状态，异步完成不得覆盖已恢复草稿或后续编辑。Discord 不监听 remote preferences 变化，**保存并成功同步后重启 Discord 生效**。自动翻译、项目日志与流式请求统一使用原生 Switch；名称和描述反映当前草稿的开启/关闭状态，加载中或配置无效时显示对应状态，不将不可用占位值描述为已关闭。切换仅修改草稿，仍走原有保存/同步链；流式说明区分 SSE 与非流式 JSON，完整校验后才显示译文。

### 翻译范围

`TranslationScope` 是唯一规则模型：频道规则 > 服务器规则 > 默认策略。服务器/频道各最多 256 条规则；ID 只接受无前导零的正十进制 uint64，备注最多 80 UTF-16 单元且只用于显示。重复 ID、未知 JSON 键或错误类型使整份配置无效，不自动修复。未保存新键才默认全部翻译。

原生 `message.bind` 按 profile 捕获 `Message` 的频道 long 和可空 `GuildId` 包装对象（null 表示私信）。两版本 ID getter 的完整名称与返回类型均核实；`GuildId.toString` 返回十进制 ID。`HookBridge` 记录有界行位置；正文及 Embed/Poll 键均回到包含它的行，位置未知时拒绝请求和译文消费。全局开关继续阻止新请求；范围 gate 位于状态创建和缓存读取之前，重试及异步交付重新核对范围和所有权。匹配策略变化（默认动作、各分区 ID 或对应动作）清理状态、卡片及缓存、使旧代次失效；纯备注或规则顺序变化不影响匹配，保留译文、缓存及在途任务所有权。完整对象相等与序列化仍保留备注和顺序，范围不参与 API 请求版本。

设置页使用独立平铺规则卡片、默认策略及搜索/编辑弹窗，不使用树形三态列表。仅“应用到草稿”交给已有配置保存链；取消不保存，旋转/停止保留编辑器草稿和正在编辑的 ID/备注/动作，远程导入不得覆盖正在编辑的副本。添加规则只接受手动输入 ID；不采集最近目录、不持久化浏览位置、不 hook 真实名称。运行时位置仅保存在有界行映射中，用于统一规则判定。

### 设置页 API 测试与结果

三种测试使用当前表单快照，不要求先保存，也不要求 LSPosed 可用；草稿测试不会提交设置或启用日志；不受消息服务器/频道范围限制。离线检查复用 `JsonApiEngine.validateConfig`；两个网络测试通过唯一的 `ApiTester.newCall(cfg, TestKind, text)` 请求链路运行。固定探针只由 `ApiTester` 选择，设置页传入的测试文本不影响连接测试。页面停止时主动断开其 HTTP 请求并取消 Future，迟到结果不更新页面。

| 测试类型 | 成功的含义 | 结果中的译文 |
|---|---|---|
| `CONFIGURATION`（检查设置） | 离线配置校验通过；未请求网络，不能证明密钥、模型或连接可用 | 无 |
| `CONNECTION`（测试连接） | 固定探针请求成功，按所选协议及 SSE/JSON 模式完成响应解析；**HTTP 200 不够**，不能忽略无效结构、异常停止或缺少完成事件 | 无；不运行译文防损坏校验 |
| `TRANSLATION`（测试翻译） | 测试文本的请求、完整协议解析及 `Segments` 译文防损坏校验通过（非空、包装标签、旧分段标记、保留节点定位、占位符及长度比例）；不证明宿主节点重建的行段、样式或链接投影校验通过 | 仅成功时展示译文；失败不展示部分或损坏结果 |

`ApiTester.Result` 返回测试类型、成功标志、耗时和结构化 `Failure`。设置页由唯一的 `ApiTestResultFormatter` 通过 `getString` 解析本地化资源，区分三种成功概念；失败显示本地化建议、类别及固定类别码、固定原因码、已知 HTTP 状态和异常类型。耗时只用于页面显示，不进入日志。Failure 包含 `kind / reason / httpStatus / exceptionType / upstreamErrorBody`，异常类型取底层 cause 类型（若有）。普通测试请求的非 2xx 错误在术语包未启用时携带上游正文，其余失败的 `upstreamErrorBody` 为 null。不携带原异常对象或异常消息，UI 不调用 `Throwable.getMessage()` 或 `userMessage()`。

| 固定原因码 | 排查建议 |
|---|---|
| `CONFIG_INVALID` | URL 填基础地址，如 `https://api.openai.com/v1`，不带 `/chat/completions`、`/responses`、`/messages`；检查协议、模型、正整数超时/并发、`Name: Value` 请求头 |
| `NETWORK_FAILURE` | 检查网络、DNS、TLS、VPN 或代理，然后手动重试 |
| `REQUEST_TIMEOUT` | 连接或等待数据超时；检查服务可用性，增大超时后重试，与模型停止和译文校验失败不同 |
| `HTTP_ERROR` | 400：请求参数；401：认证；403：权限；404：端点或模型不存在（检查基础 URL 和协议）；5xx：服务异常；其余状态检查供应商文档 |
| `RATE_LIMITED` | 非 2xx 正文读取完成后，HTTP 429 统一归为 `RATE_LIMIT / RATE_LIMITED`；稍后重试、降低并发并检查配额 |
| `RESPONSE_INVALID` | 协议或格式不匹配；流式必须对应 SSE，非流式对应 JSON，不自动切换模式；检查基础路径是否重复包含端点 |
| `RESPONSE_INCOMPLETE` | 响应截断或缺少 SSE 完成事件；检查服务和代理以及 SSE/JSON 支持，不接受部分结果 |
| `GENERATION_STOPPED` | 非正常停止原因，或 Responses 生成失败/未完成；检查模型输出限制和提示词，增加网络超时不能修复。拒绝/工具字段等一般协议错误归 `RESPONSE_INVALID`，非正常停止原因才归此码 |
| `TRANSLATION_INVALID` | 完整响应违反译文保留规则；调整仅翻译提示词或模型，增加超时不能修复内容损坏 |
| `EMPTY_TEXT` | 输入或提取文本为空；先填写非空测试文本，再检查模型、提示词与协议 |
| `CANCELLED` | 请求已取消；保持页面打开并手动重新测试 |
| `UNKNOWN_FAILURE` | 根据异常类型排查；需先保存启用日志的开关，再手动重试，不输出异常消息或响应正文 |

## 日志

`ProjectLog` 是本项目的唯一日志出口，默认及无效配置均关闭；启动时先读取已保存开关，再记录日志。关闭时不写输出、不消耗 once 去重键，重新开启可重新记录诊断。关闭日志不改变业务失败处理，也不删除既有日志或控制框架自身日志。

- **设置页进程：logcat**，标签为 `DiscordAutoTranslate`。页面不挂载 LSPosed 框架输出；不需要 LSPosed 连接，也不需要重启页面。必须将“项目日志”开关设为开启并成功保存后生效，或加载/导入已确认本地保存的值；草稿切换、草稿测试和失败的本地提交都不能强行开启日志。保存成功先应用已确认本地快照，再输出保存结果；远程同步失败仍与本地保存成功分开记录。成功保存关闭开关或重置为默认配置后，连该成功结果日志也不输出。
- **Discord 进程：logcat + LSPosed 项目日志**，框架输出仅由模块入口挂载，使用 remote 配置。因此需保存并同步成功后重启 Discord，设置页的 logcat 诊断与 Discord 的 LSPosed 诊断不是同一进程/目的地。
- `JsonApiEngine` 在普通请求的术语包未启用时把非 2xx 上游正文交给设置页测试结果和启用的 `ProjectLog`。精翻批次和启用术语包的请求只输出固定错误类别与 HTTP 状态。HTTP 200 后的协议或译文错误使用固定描述和错误原因码。
