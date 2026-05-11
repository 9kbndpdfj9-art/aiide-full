# AIIDE 安卓端 - 项目蓝图 v6.0

## 颠覆性创新：Prompt → App

### 核心洞察
Claude Code 给你代码。Trae SOLO 给你代码 + 预览。**用户真正想要的不是代码，是能用的 App。**

---

## 一、三大颠覆性能力

### 颠覆 1：Live App Engine（即时应用引擎）
- 用户说一句话，AI 生成可直接运行的 App
- Quick Run：WebView 直接运行 < 1秒
- Native Build：Termux + Gradle 编译 30-120秒

### 颠覆 2：Phone-Native Development
- 摄像头、GPS、加速度计、NFC、蓝牙
- 指纹/面容、通知、传感器

### 颠覆 3：Skill 生态（Git-based）
- 用 Git 仓库作为 Skill 分发渠道
- 不需要任何服务器

---

## 二、六大极致编程体验创新

### 创新 1：Intent Graph（意图图谱）
代码不是文件，是意图的网络。每个函数、每个模块、每个组件都有一个意图标签。

### 创新 2：Code Time Travel（代码时光机）
每一秒都可回溯。记录每一次按键的意图和结果。

### 创新 3：Predictive Edit（预测性编辑）
AI 知道你接下来要写什么，提前生成代码，以影子层呈现。

### 创新 4：Live Diff（实时差异感知）
每次代码变更，实时计算影响范围并可视化。

### 创新 5：Context Weaving（上下文编织）
AI 永远不会忘记任何代码，精确选取相关片段。

### 创新 6：Self-Evolving AI（自进化 AI）
用得越久，AI 越懂你。构建个人编码模型。

---

## 三、支撑能力（内核四件套）

- 美学引擎
- 观测系统
- 上下文压缩（L0-L3）
- Rust 计算引擎

---

## 四、Quick Run 技术方案

### Bridge Action 列表
- listFiles, readFile, writeFile
- createDirectory, deleteFile
- getConfig, setConfig
- callLLM, validateFileName
- getSkills, matchSkill
- openPreview, captureScreenshot
- visualAnalyze, getAvailableSensors

---

## 五、碾压级对比

| 维度 | Claude Code | 我们 |
|------|-------------|------|
| 交付物 | 代码 | **可运行的 App** |
| 运行验证 | 用户自己编译 | **即时预览** |
| 传感器 | 不支持 | **摄像头/GPS/加速度计** |
| 平台 | 桌面 | **手机** |
| 隐私 | 代码经云端 | **纯本地** |

---

## 六、开发路线图

### 阶段 1（0-2 周）
- 文件管理、代码编辑器、AI 对话
- 自动保存、Bridge 通信
- Skill 系统、预览引擎

### 阶段 2（3-6 周）
- 意图图谱、代码时光机
- 传感器桥接、5 个官方 Skill

### 阶段 3（7-16 周）
- Live Diff、上下文编织
- 自进化 AI、React 前端

---

## 七、目录结构

```
/workspace/
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/aiide/
│   │   │   └── assets/www/
│   │   └── build.gradle.kts
│   └── ...
├── engine/
│   ├── Cargo.toml
│   └── src/
│       ├── lib.rs
│       ├── file_graph/mod.rs
│       ├── context/mod.rs
│       ├── memory/mod.rs
│       └── algorithm/
├── skills/
│   ├── general/
│   └── web-ui/
└── docs/
```