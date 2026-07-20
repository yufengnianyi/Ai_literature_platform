# Project API Reference

## Project Structure

| Path | Purpose |
| --- | --- |
| `src/main/java/com/example/demo_01` | Spring Boot 后端源码，包含用户、会话、AI、RAG、知识图谱、证据抽取、报告等核心模块。 |
| `src/main/resources` | 后端配置、提示词模板、Flyway 数据库迁移脚本。 |
| `ai-literature-frontend` | Vue/Vite 前端工程，包含页面、组件、API 调用封装和状态管理。 |
| `PreTreatment` | 独立的文献预筛选脚本、配置、提示词和运行输出。 |
| `scripts` | 构建、部署、生产检查等辅助脚本。 |
| `data` | 应用或实验使用的本地数据文件。 |
| `outputs` | 报告、评估结果、导出文件和实验过程产物。 |

## API Table

大多数 JSON 接口统一返回 `BaseResponse<T>`。`Admin` 表示接口受 `@AuthCheck(mustRole = "admin")` 保护，仅管理员可访问；`Login` 表示需要已登录会话；`Public` 表示 Controller 层未显式声明登录或管理员校验。

| Module | Method | Path | Auth | Function |
| --- | --- | --- | --- | --- |
| User | POST | `/user/register` | Public | 用户注册接口。提交账号、密码等注册信息，创建新的普通用户账号。 |
| User | POST | `/user/login` | Public | 用户登录接口。校验账号密码，登录成功后写入会话，并返回当前登录用户信息。 |
| User | GET | `/user/get/login` | Login | 获取当前登录用户。用于前端刷新页面后恢复登录态、判断用户角色和展示用户信息。 |
| User | POST | `/user/logout` | Login | 用户退出登录。清理当前会话中的登录状态。 |
| User Admin | POST | `/user/add` | Admin | 管理员新增用户。由管理员直接创建用户账号，通常用于后台用户管理。 |
| User Admin | GET | `/user/get?id={userId}` | Admin | 管理员按用户 ID 查询用户详情。用于后台查看单个用户资料。 |
| User Admin | POST | `/user/update` | Admin | 管理员更新用户信息。可修改用户资料、角色或状态等字段，具体字段取决于 `UserUpdateRequest`。 |
| User Admin | POST | `/user/delete` | Admin | 管理员删除用户。根据用户 ID 删除指定用户账号。 |
| User Admin | POST | `/user/list/page/vo` | Admin | 管理员分页查询用户列表。支持后台用户表格、筛选和分页展示。 |
| Conversation | POST | `/conversations` | Login | 创建会话。为当前用户新建一个聊天或报告会话，可携带初始化参数。 |
| Conversation | GET | `/conversations` | Login | 查询当前用户的会话列表。用于左侧会话栏或历史记录展示。 |
| Conversation | GET | `/conversations/{conversationId}/messages` | Login | 查询指定会话的消息列表。用于打开历史会话时恢复聊天上下文。 |
| Conversation | PATCH | `/conversations/{conversationId}` | Login | 重命名会话。修改指定会话标题。 |
| Conversation | PATCH | `/conversations/{conversationId}/pin` | Login | 置顶或取消置顶会话。用于控制会话列表排序和常用会话展示。 |
| Conversation | DELETE | `/conversations/{conversationId}` | Login | 删除会话。移除当前用户名下指定会话。 |
| Chat | GET | `/ai` | Login | AI 流式聊天接口。通过 SSE 持续返回模型输出；必传 `prompt`，并传 `conversationId` 或兼容旧版的 `memory_id`；`enableThinking` 控制是否启用思考模式。 |
| Preprocess | POST | `/preprocess/documents` | Public | 上传单个文档进行预处理。服务端接收文件后异步处理，返回 `202 Accepted` 和任务信息。 |
| Preprocess | GET | `/preprocess/jobs/{jobId}` | Public | 查询单个预处理任务状态。用于查看上传文档的处理进度、结果或失败原因。 |
| Preprocess | POST | `/preprocess/batches/folder` | Public | 按服务器本地文件夹批量预处理文档。提交文件夹路径后异步创建批处理任务，返回 `202 Accepted`。 |
| Preprocess | GET | `/preprocess/batches/{batchId}` | Public | 查询预处理批次状态。用于查看批量任务整体进度和每个文件处理情况。 |
| RAG Document | POST | `/rag/documents` | Admin | 上传单个 PDF 或文档进入 RAG 入库流程。服务端异步解析、切分并准备向量/检索数据，返回 `202 Accepted`。 |
| RAG Document | POST | `/rag/documents/batch` | Admin | 批量上传多个文档进入 RAG 入库流程。适合管理员一次导入多篇文献，返回 `202 Accepted`。 |
| RAG Document | POST | `/rag/documents/{documentId}/ingest` | Admin | 对已存在的文档产物重新发起入库。用于补跑或从已有 artifact 恢复 RAG 入库任务，返回 `202 Accepted`。 |
| RAG Document | GET | `/rag/documents/stats` | Admin | 获取 RAG 文档统计信息。展示已入库文档数、任务状态分布等统计数据。 |
| RAG Document | GET | `/rag/documents/{documentId}` | Admin | 查询单个 RAG 文档记录。返回文档元信息、入库状态和相关处理结果。 |
| RAG Entity | POST | `/rag/documents/{documentId}/entities/extract` | Admin | 对单篇 RAG 文档抽取实体。可选传入问题，让实体抽取围绕特定研究问题进行。 |
| RAG Entity | POST | `/rag/documents/entities/extract-batch` | Admin | 批量抽取多个 RAG 文档的实体。用于批处理实体识别和后续知识图谱构建。 |
| RAG Batch | POST | `/rag/batches/folder` | Admin | 从服务器本地文件夹批量导入文档到 RAG。适合处理已放在服务器目录中的大量 PDF，返回 `202 Accepted`。 |
| RAG Batch | GET | `/rag/batches/{batchId}` | Admin | 查询 RAG 批量入库批次。用于查看批次进度、成功/失败数量和任务明细。 |
| RAG Job | GET | `/rag/jobs/{jobId}` | Admin | 查询单个 RAG 入库任务。用于追踪某个文档的解析、切分、向量化等处理状态。 |
| KG | POST | `/kg/documents/{documentId}/extract` | Public | 为指定文档发起知识图谱抽取任务。将文档 chunk 中的实体和关系抽取为图谱数据。 |
| KG | GET | `/kg/documents/{documentId}/job` | Public | 查询指定文档最近一次知识图谱抽取任务。用于查看 KG pipeline 是否完成或失败。 |
| KG | GET | `/kg/documents/{documentId}/payload` | Public | 获取指定文档组装后的知识图谱 payload。通常用于前端图谱展示或写入图数据库前检查。 |
| KG | GET | `/kg/documents/{documentId}/entities` | Public | 查询文档 chunk 级实体抽取结果。返回实体名称、类型、来源片段等结构化数据。 |
| KG | GET | `/kg/documents/{documentId}/relations` | Public | 查询文档 chunk 级关系抽取结果。返回实体之间的关系、证据来源等数据。 |
| KG Query | GET | `/kg/query?prompt={prompt}` | Public | 自然语言查询知识图谱。根据用户问题检索或构造相关图谱视图。 |
| Evidence Admin | POST | `/admin/evidence/documents/{documentId}/extract?force={bool}` | Admin | 对单篇文档发起证据抽取。`force=true` 时可强制重新抽取，覆盖跳过已有结果的默认行为。 |
| Evidence Admin | POST | `/admin/evidence/extractions/backfill` | Admin | 批量补跑证据抽取。对符合条件但缺少证据结果的文档创建抽取任务。 |
| Evidence Admin | GET | `/admin/evidence/extractions/{runId}` | Admin | 查询单次证据抽取运行记录。用于查看某篇文档某次抽取的状态、结果和错误信息。 |
| Evidence Admin | GET | `/admin/evidence/extractions/batches/{batchId}` | Admin | 查询证据抽取批次。用于查看批量证据抽取任务的整体状态。 |
| Evidence Admin | GET | `/admin/evidence/extractions/batches/{batchId}/documents?page={page}&size={size}` | Admin | 分页查询批次内文档抽取记录。用于后台表格展示每篇文档的抽取进度和结果。 |
| Multi-Profile Evidence | POST | `/admin/evidence/multi-profile-batches` | Admin | 提交多问题/多画像证据抽取批次。用于按多个研究问题或 profile 对文献进行分类和证据抽取。 |
| Multi-Profile Evidence | GET | `/admin/evidence/multi-profile-batches/{batchId}` | Admin | 查询多 profile 批次状态。返回批次元信息、执行状态和统计数据。 |
| Multi-Profile Evidence | GET | `/admin/evidence/multi-profile-batches/{batchId}/documents` | Admin | 分页查询批次内文档。支持按 `questionId`、分类状态、抽取状态过滤。 |
| Multi-Profile Evidence | GET | `/admin/evidence/multi-profile-batches/{batchId}/records` | Admin | 分页查询已抽取的证据记录。可按问题、文档、审核状态过滤，用于证据审阅。 |
| Multi-Profile Evidence | GET | `/admin/evidence/multi-profile-batches/{batchId}/export` | Admin | 导出多 profile 证据结果。下载 `.xlsx` 工作簿，便于人工复核和交付。 |
| RAG Evaluation | POST | `/rag-evaluation/experiments` | Login | 提交 RAG 检索评估实验。根据用户问题运行检索评测，生成实验记录。 |
| RAG Evaluation | POST | `/rag-evaluation/experiment-suites/required` | Login | 提交必需评估套件。针对某个问题运行系统预设的一组 RAG 评估实验。 |
| RAG Evaluation | POST | `/rag-evaluation/experiments/antimicrobial-summary` | Login | 提交抗菌总结专项实验。用于特定领域任务的文献检索和总结评估。 |
| RAG Evaluation | GET | `/rag-evaluation/experiments/{experimentId}` | Login | 查询当前用户拥有的评估实验。只允许访问自己的实验记录。 |
| RAG Evaluation | GET | `/rag-evaluation/experiments/{experimentId}/judgments` | Login | 查询实验中的文档判定结果。返回相关/不相关等 judgment 数据。 |
| RAG Evaluation | GET | `/rag-evaluation/experiments/{experimentId}/metrics` | Login | 查询实验指标。返回召回率、准确率等 RAG 评估指标。 |
| RAG Evaluation | GET | `/rag-evaluation/experiments/{experimentId}/antimicrobial-results` | Login | 查询抗菌专项实验结果。返回该实验生成的领域结果列表。 |
| RAG Evaluation | POST | `/rag-evaluation/experiments/{experimentId}/judgments/{documentId}/override` | Login | 人工覆盖某篇文档的评估判定，并重新计算实验指标。用于修正自动判定误差。 |
| Report | POST | `/report/runs` | Login | 提交深度报告生成任务。基于会话问题生成文献/证据报告，并把会话模式切换为报告模式。 |
| Report | GET | `/report/runs/{reportId}` | Login | 查询当前用户拥有的报告任务。返回报告状态、内容摘要、附件信息等。 |
| Report | GET | `/conversations/{conversationId}/report-runs` | Login | 查询某个会话下的报告任务列表。用于在会话页面展示历史报告。 |
| Report | GET | `/report/runs/{reportId}/attachment` | Login | 下载报告附件。通常为证据表或结果工作簿 `.xlsx`。 |

## Functional Overview

| Area | Description |
| --- | --- |
| User and session | 用户与会话模块。负责注册、登录、退出、当前用户查询，以及管理员用户管理。 |
| Conversation and chat | 会话与聊天模块。保存会话元数据和消息历史，并通过 SSE 向前端流式输出 AI 回复。 |
| Preprocessing | 文档预处理模块。将上传文件或服务器文件夹中的原始文档转换为后续入库可用的标准化产物。 |
| RAG ingestion | RAG 入库模块。负责上传文档、解析文本、切分 chunk、保存文档记录，并准备检索/向量化数据。 |
| Knowledge graph | 知识图谱模块。从 RAG 文档中抽取实体和关系，并提供图谱 payload、实体、关系和自然语言查询接口。 |
| Evidence extraction | 证据抽取模块。运行面向领域问题的证据抽取 pipeline，记录抽取批次和运行结果。 |
| Multi-profile evidence | 多 profile 证据模块。围绕多个研究问题或画像执行文献分类、证据抽取、审核查询和 Excel 导出。 |
| RAG evaluation | RAG 评估模块。运行检索评估实验，记录文档判定，计算指标，并支持人工覆盖判定。 |
| Report generation | 报告生成模块。基于会话问题生成文献/证据报告，并提供报告状态查询和附件下载。 |
