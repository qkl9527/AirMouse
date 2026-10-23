# AirMouse Agent：工具与模型配置

## 可调用操作

| 工具 | 能力 | 确认时显示 |
| --- | --- | --- |
| `airmouse_key` | 返回、主页、方向、确认、音量、静音、播放/暂停、删除、文字光标、空格、回车 | 按键、次数（一次）、目标应用或全局操作 |
| `airmouse_text` | 当前输入框覆盖 / 插入，可随后回车 | 完整文字、模式、目标应用、是否提交 |
| `airmouse_open_app` | 打开已安装且可启动的应用 | 应用名与包名 |
| `airmouse_run_scene` | 手机保存的打开应用 → 搜索 → 可选播放步骤 | 场景名称、全部步骤、搜索文字 |
| `airmouse_pointer` | 当前指针处单击、双击、相对移动、滚动、拖动 | 操作、坐标增量、目标应用 |
| `airmouse_cursor_style` | 箭头、薄荷圆环、晴空方框、暖阳角标 | 目标样式 |
| `airmouse_party` | 发起抢答 / 投票、以本手机身份抢答 / 投票、主持人结束活动 | 问题、选项或本次选择 |

服务端读取真实安装应用；手机在每次发起对话时发送本浏览器保存的最多 30 个场景和四个快捷键。服务端校验并复制该快照，放入 system prompt。应用名、场景名和搜索词只作为数据，不能提升为指令。浏览器场景本身仍由网页保存；不会被其他手机的配置覆盖。

## 工具调用过程

1. 按提供商协议转换工具定义与结果，支持 Chat Completions、Responses、Anthropic Messages 的普通及 SSE 响应。只有启用工具能力的模型才发送工具定义；流式参数收齐并完整结束之前不执行。
2. 服务端验证工具名、类型、参数范围、应用白名单和场景 ID，生成实际执行计划。
3. 电视无障碍浮层提示操作；手机弹窗列出完整计划。只有发起该轮对话、完成配对的手机可以允许 / 拒绝。其他手机只可查看。
4. 确认绑定随机 ID、对话代次和两分钟有效期，重复点击不会重复执行。模型不能替用户批准。
5. 允许后逐步执行并等待结果，应用切换后再次核对目标包名；任一步失败或结果不确定，停止后续步骤，不自动重试。
6. 拒绝或超时不执行，将 `tool` 结果交回模型继续文字对话。本轮余下工具禁用，避免反复请求或换方式绕过拒绝。
7. “停止”或停服会使待确认和尚未派发的操作失效。已经交给系统的单个动作无法撤回。

每轮最多 8 个工具调用、最多 6 次模型请求，避免无限循环。操作结果记录在两端对话中。`submitted` 只表示命令提交，不宣称影片已经播放或界面结果已验证。

## 提供商与模型

手机进入 **Agent → 模型设置**：

1. 在“添加提供商”中选择平台，默认填入地址、协议和参数格式。已有 Agnes 配置、Key 和模型参数会自动迁移。
2. 填写该平台的 API Key。点击 **保存并读取模型列表**，搜索、选择模型并添加；也可 **手动添加**，填写平台要求的完整模型 ID 或推理接入点 ID。
3. 确认模型的上下文窗口、最大输出、思考强度、图片和工具能力，点击 **保存并设为当前模型**。一个提供商下可配置多个模型，共用此提供商的 Key。
4. 切换旧模型时，在对应提供商中选择模型，再设为当前。正在回答、压缩或等待审批时，先停止或等完成后再保存。

宽屏使用左侧提供商列表和右侧编辑区；手机使用顶部提供商选择器，模型参数按需展开。共用显示设置中保留流式开关和电视字号。

### 内置接入预设

以下预设根据官方文档填写，可修改地址。预设只提供接入入口，不承诺账号能调用列表里的每个模型。新模型不依赖 APK 更新：从接口读取或手动填写即可。

| 提供商（官方文档） | 分类 | 默认协议 |
| --- | --- | --- |
| [Agnes AI · 国际](https://agnes-ai.com/en/docs/agnes-30-flash) | 国际 | Chat Completions |
| [OpenAI](https://developers.openai.com/api/docs/guides/function-calling) | 国际 | Responses |
| [Anthropic](https://platform.claude.com/docs/en/api/messages) | 国际 | Messages |
| [Google Gemini](https://ai.google.dev/gemini-api/docs/openai) | 国际 | Chat Completions |
| [阿里云百炼 · 新加坡](https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope) | 国际 | Chat Completions |
| [MiniMax · 国际](https://platform.minimax.io/docs/api-reference/text-openai-api) | 国际 | Chat Completions |
| [xAI / Grok](https://docs.x.ai/developers/model-capabilities/legacy/chat-completions) | 国际 | Chat Completions |
| [Mistral AI](https://docs.mistral.ai/api/endpoint/chat) | 国际 | Chat Completions |
| [DeepSeek](https://api-docs.deepseek.com/) | 国内 | Chat Completions |
| [阿里云百炼 · 北京](https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope) | 国内 | Chat Completions |
| [火山方舟 / 豆包](https://www.volcengine.com/docs/82379) | 国内 | Chat Completions |
| [月之暗面 Kimi](https://platform.kimi.com/docs/api/chat) | 国内 | Chat Completions |
| [智谱 GLM](https://docs.bigmodel.cn/cn/guide/develop/openai/introduction) | 国内 | Chat Completions |
| [MiniMax · 国内](https://platform.minimax.cn/docs/api-reference/text-openai-api) | 国内 | Chat Completions |
| [百度千帆](https://cloud.baidu.com/doc/qianfan-docs/s/Fm9l6ocai) | 国内 | Chat Completions |
| [腾讯混元](https://cloud.tencent.com/document/product/1729/111007) | 国内 | Chat Completions |
| [硅基流动](https://docs.siliconflow.cn/docs/userguide/quickstart) | 聚合平台 | Chat Completions |
| [OpenRouter](https://openrouter.ai/docs/api/reference/overview) | 聚合平台 | Chat Completions |
| [Together AI](https://docs.together.ai/docs/inference/openai-compatibility) | 聚合平台 | Chat Completions |
| [Groq](https://console.groq.com/docs/openai) | 聚合平台 | Chat Completions |
| [Fireworks AI](https://docs.fireworks.ai/tools-sdks/openai-compatibility) | 聚合平台 | Chat Completions |

另提供 **自定义服务**，支持上述三种协议、HTTPS baseURL 或完整 endpoint，以及 API Key / 无需认证两种方式。保存时自动规范化末尾路径。不能把某个平台的 Chat 接口仅通过切换协议就变成 Responses / Messages，地址和协议必须与实际服务匹配。

接口模型列表不一定包含全部可用模型，也可能包含非聊天模型；列表接口不可用时可手动添加。OpenRouter 等接口提供的上下文和能力会用于初始填写；未提供的能力需要按模型文档确认。读取最多 1000 项（Anthropic 最多 10 页）。不同地域、普通 API 与 Coding Plan 的地址和 Key 可能不同，需按控制台配置，不自动换线路或跨提供商尝试 Key。

### 模型与显示参数

配置以 TV 为准，手机每次连接后同步；保存成功才生效。当前模型由所有已配对手机共享。

| 配置 | 新模型默认 | 范围 / 说明 |
| --- | --- | --- |
| 模型 ID | 手动填写或读取 | 不固定模型版本；原 Agnes 模型保留 |
| 上下文窗口 | 32768 Token，接口提供时自动填写 | 4096–2000000；保守预算达到 80% 时压缩旧历史 |
| 最大输出 | 2048 Token，接口提供时参考其上限 | 128–262144，至少为输入预留 1024；读入值最高先取 8192，可再改 |
| 思考强度 | 提供商默认 | 默认不传参数；其余档位按协议 / 参数格式转换，须模型支持 |
| 工具调用 | 开启，接口提供能力时优先使用 | 不支持工具的模型请关闭；所有实际电视操作仍须逐次确认 |
| 图片输入 | 关闭，接口提供能力时优先使用 | 只有勾选后才可发送新图片；仍需选支持视觉的模型 |
| 输出参数名 | 自动 | Chat 可选择 `max_tokens` / `max_completion_tokens`；Responses 用 `max_output_tokens`，Messages 用 `max_tokens` |
| 扩展 JSON | `{}` | 高级采样 / 思考参数；须为对象，最多 8192 字符，不允许覆盖消息、工具、认证和模型 |
| 流式输出 | 开启 | 下一个问题生效，电视持久化 |
| 电视文字大小 | 28 | 12–48 CSS px，立即同步到电视 |
| API Key | 未设置 | 最多 4096 个可打印英文字符；同一提供商留空沿用，支持明确移除 |

**思考参数：** OpenAI 使用 [reasoning 参数](https://developers.openai.com/api/docs/guides/reasoning)；Anthropic 非默认强度使用 [adaptive thinking / effort](https://platform.claude.com/docs/en/build-with-claude/effort)，旧模型可选默认并在扩展 JSON 配置 `thinking.budget_tokens`；OpenRouter 使用 [reasoning 配置](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens)。千问等适配 `enable_thinking`，Kimi / GLM 适配 `thinking.type`。不是所有模型都支持关闭思考或全部强度，不确定时选择“提供商默认”。扩展 JSON 中同名字段覆盖表单生成值。

协议适配保留本轮工具续答所需的 Responses reasoning 项、Anthropic 思考签名及 Chat 原始 assistant 字段；这些内容不进入展示历史。兼容接口返回在开头的 `<think>` 内容也不展示，原始内容仅用于本轮续答。拒绝工具后禁用本轮后续调用，不依赖模型自行遵守。

上下文窗口是本地预算，不会改变模型固有的窗口，也不是精确的服务商 tokenizer 计数。文字按 UTF-8 字节保守估算，图片预留 8192 Token，同时计算工具定义、最大输出及安全余量。达到 80% 时，用同一个模型分块总结历史目标、事实、操作参数、确认状态和结果；摘要请求不携带工具。最新用户问题与本轮工具链保留原样。只有压缩后低于 80% 且至少减少约 10% 预算才继续，否则提示新开会话，原记录保留。摘要调用也消耗额度。切换模型会继续携带当前会话历史；若新模型不支持历史图片，需切回视觉模型或另开会话。

### 保存与同步

- 提供商、各自 Key、模型和当前选择整体以 Android Keystore + AES-GCM 加密，原子写入电视私有目录，无明文回退；手机同步数据只包含 `hasKey`，不含 Key 原文。
- 地址或协议变更时，留空 Key 不会自动带到新接口：需要重新填写，或明确勾选沿用已保存 Key。网络不跟随重定向，也不把上游原始错误体回显到手机。
- 使用版本号防止另一部手机的旧草稿覆盖最新配置。关闭配置面板会丢弃未保存草稿；没有自动保存 Key 的浏览器存储。
- 最多 24 个提供商、每个 100 个模型，配置总量最多 256K 字符。删除当前提供商或禁用当前模型后，需手动选择可用模型，不会自动换平台。

## 会话

- 手机标题下方显示当前会话，点击“切换 / 新建”管理历史；输入区的“新对话”也打开同一入口。
- 普通会话自动保存到电视，重启后恢复最近普通会话。标题来自第一条问题，可切换旧会话继续；旧会话的图片、回答和操作结果会继续参与上下文。
- 一次性对话只在内存中，切换到其他会话、新建或停服后丢弃。所有已配对手机和电视同步当前会话，不是每手机私有历史。
- 正在回答、压缩或等待审批时，需先停止或等完成再切换。消息携带会话 ID，过期页面不能把问题发入另一会话。普通草稿仅存于当前手机页面内存并按会话区分。
- 历史工具操作保存意图、参数、允许/拒绝和结果；未完成操作不会在恢复会话时自动重试。新工具调用仍逐次审批。
- 最多 30 个普通会话；单会话发送前检查 400 条 / 400 万字符容量。删除需要先切换到其他会话，再点“删除”并确认。

## 阅读定位

手机点击“到最新回答底部”时，手机立即定位，并通知电视 Agent 页面执行同样的定位、恢复跟随最新回答。此操作不自动打开电视 Agent 页面。每次点击使用独立递增序号，后续配置同步不会重复触发跳转。

## 当前限制

- Agent 无法读取电视截图或自动识别搜索入口；搜索场景要求目标 App 已提供搜索焦点。
- 普通 HTTP 配对时，手机到电视的配置和聊天仍经局域网传输；电视到所选模型提供商使用 HTTPS。
- Key 仍独立加密保存。普通会话（文字、图片、操作记录、摘要）保存在 TV 应用私有目录，应用已禁用系统备份；一次性对话不写入历史存储。卸载或清除应用数据会移除历史。
- 指针和播放 / 暂停按系统接口派发，无法保证第三方应用接受。多步场景可能部分完成，失败不会回滚已完成步骤。
- 此版本按要求只编译交付；真实模型调用、思考档位、电视弹窗和确认执行由用户在设备上测试。

## 电视适配与 Debug 诊断

如果电视一直显示初始提示，而手机已能遥控，不一定是手机没有连接。旧版电视 WebView 无法解析 optional chaining 等新语法时，阅读页脚本会停止；旧内核也可能忽略 `clamp()` / `inset`，导致左栏高度和字号异常。本版电视阅读页保留 ES2017 兼容语法，为字号提供基础值，并明确固定侧栏高度；短屏简化装饰，竖屏改为顶部栏。

- 手机进入 **Agent → 电视显示**：调节对话内容字号 **12–48px**，保存到电视并立即生效；此入口也可开关 Debug、刷新及复制诊断报告。模型设置内也保留同一组控件。
- 电视 AirMouse 首页点击 **Agent Debug**，再进入 Agent；也可用遥控器 **菜单键**切换 Debug。页面下方会出现原生诊断面板，即使网页 JavaScript 无法执行，也能看到错误。原生面板提供重新加载按钮。
- Debug 报告包括 App / Android / WebView 版本、视口、真实 WebSocket 连接数、桥接轮询与最后渲染时间、页面错误和最近 80 条事件。手机报告补充手机端连接状态和页面版本序号，可用来区分“未连接”“桥接未轮询”“脚本已运行但渲染失败”。
- Debug 开关保存在电视；事件仅驻留内存，关闭后清空。诊断不采集聊天、图片、API Key、配对地址或请求正文，不自动上传；开启后由用户手动复制报告。最后的页面错误会保留在当前进程中，方便出错后再开启 Debug 查看。

真实索尼电视的 WebView 版本需通过报告确认；本地旧语法解析和浏览器布局检查不能替代具体电视固件上的验证。
