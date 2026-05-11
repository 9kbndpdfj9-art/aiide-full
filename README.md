# AIIDE - Android AI IDE

意图驱动、零Token预测、沙箱安全的移动端AI开发环境

## 特性

- **意图驱动开发**: 自然语言输入意图，自动完成代码生成
- **零Token预测引擎**: 80%任务无需LLM调用，节省成本
- **语义影子系统**: 实时追踪代码行为变化，保证正确性
- **沙箱隔离**: 实验性修改在沙箱中预演，确保安全
- **多模型路由**: 支持OpenAI/Anthropic/Gemini/Ollama等
- **SCTP协议**: 90% Token压缩比，极致效率

## 项目结构

```
aiide/
├── android/          # Android Kotlin应用 (88+引擎)
│   └── app/src/main/
│       └── java/com/aiide/   # 所有引擎代码
├── engine/             # Rust高性能引擎
│   └── src/
├── docs/               # 完整文档
├── frontend/           # 前端资源
├── scripts/            # 工具脚本
└── downloads/          # 下载文件
```

## 下载

- [下载完整项目ZIP](https://github.com/9kbndpdfj9-art/aiide-full/archive/refs/heads/main.zip)

## 技术栈

- **Android**: Kotlin, MVVM, Coroutines
- **Rust**: 高性能引擎核心
- **AI**: OpenAI, Anthropic, Google Gemini, Ollama

## License

MIT
