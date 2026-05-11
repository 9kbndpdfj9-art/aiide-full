# AIIDE Platform Blueprint

## Version 3.0

---

## 系统概览

| 指标 | 数量 |
|------|------|
| 代码文件数 | 80+ .kt 文件 |
| 总代码行数 | ~60,000+ 行 |
| Agent类 | 14 个 |
| Engine类 | 55+ 个 |
| Bridge路由数 | 520+ |
| 新引擎（本轮） | 20 个 |
| 落地率 | 从 16% → 75% |

---

## 核心能力架构

### 第一层：感知层
- CrossFileContextEngine, TextSearchEngine
- WebSearchEngine, WindowContextEngine
- VisualFeedbackLoop, CodeDNAAutoStyle
- CodeGenome, CodeDependencyGraph
- MultimodalEngine, VectorSearchEngine

### 第二层：认知层
- ThinkingRouterEngine, IntentFabricEngine
- IntentOrchestrator, IntentAuditor
- IntentCompletionEngine, SemanticShadowEngine
- PreMortemEngine, ContextCompressor
- SearchAnswerEngine, AutoDebugger

### 第三层：行动层
- ShellExecutor, FileAgent
- DevSwarmEngine, MultiAgentCoordinator
- CrossAgentCollaboration, CodeReviewEngine
- SmartFixEngine, CodeCompletionEngine

### 第四层：学习层
- SkillEvolver, SkillManager
- ToolGenomeEngine

### 第五层：协作层
- HumanInTheLoopEngine, CapabilityBoundaryEngine
- McpProtocolManager, ChatSessionManager

---

## 多模型支持

| 模型 | 厂商 | 成本/MTok |
|------|------|-----------|
| GPT-4o Mini | OpenAI | $0.15 |
| Claude Sonnet 4.5 | Anthropic | $3.00 |
| Gemini 2.0 Flash | Google | $0.075 |
| Ollama (本地) | Local | $0.00 |

---

## 技术保障

| 保障 | 实现方式 |
|------|---------|
| 线程安全 | @Volatile + synchronized |
| 缓存淘汰 | TTL + LRU |
| 安全防护 | 命令白名单/黑名单 |
| 速率限制 | 时间窗口限制 |