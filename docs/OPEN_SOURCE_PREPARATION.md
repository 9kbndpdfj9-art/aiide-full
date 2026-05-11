# DevView IDE 开源准备方案

**核心理念**：砍掉噪音，保留信号。不堆功能，先跑通闭环。

---

## 核心自测清单

### 验证 1：SemanticShadowEngine 能独立跑
打开一个 Kotlin 文件，影子引擎能自动提取函数签名、输入输出类型、调用依赖。

### 验证 2：SCTPProtocol 格式固化
三种消息类型的 Schema 有严格的 JSON/YAML 定义。

### 验证 3：ModelRouter 能不能带一个本地模型
本地模型 Ollama + 7B，能否作为默认后端做到完全免费。

### 验证 4：意图输入到代码生成的完整链路
语音输入 → IntentParser 解析 → DevGenieEngine → 拿到骨架 → 应用为代码文件。

---

## 开源准备

### 90 秒演示视频
展示：意图输入 → 自动执行 → 成果呈现

### Demo 项目结构
```
demo-project/
├── src/
│   └── main/kotlin/
│       └── Demo.kt
├── build.gradle.kts
└── devview/
    ├── intent.json
    └── shadow/
```

---

## 社区驱动策略

### 核心维护范围
| 模块 | 责任 |
|------|------|
| SemCore | 我们维护 |
| SCTP 协议 | 我们维护 |
| Bridge 核心 | 我们维护 |

### 社区贡献范围
| 模块 | 贡献方式 |
|------|---------|
| Skills | 任何人可提交 |
| 引擎扩展 | 社区实现 |
| UI 组件 | 社区设计 |
| 本地模型 | 社区集成 |