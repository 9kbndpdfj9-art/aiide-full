# AI IDE - 意图图谱 (Intent Graph) 功能更新

## 更新概述

本次更新成功实现了**阶段2**的核心功能：**意图图谱 (Intent Graph)**。

---

## 新增功能

### 1. IntentGraph.kt - 意图图谱核心数据结构
- IntentNode 数据类：标签、类型、文件范围、描述
- IntentEdge 数据类：节点间的关系
- NodeType 枚举：FUNCTION, CLASS, MODULE, COMPONENT 等
- EdgeType 枚举：DEPENDS_ON, AFFECTS, IMPLEMENTS 等
- 线程安全的图谱操作
- 影响传播分析
- JSON 序列化/反序列化

### 2. DatabaseHelper.kt - 意图图谱持久化
- intent_nodes 表
- intent_edges 表
- saveIntentNode, getIntentNode, getAllIntentNodes
- saveIntentEdge, getAllIntentEdges
- loadIntentGraph, saveIntentGraph

### 3. IntentAnalyzer.kt - 代码分析和意图提取
支持语言：JavaScript, TypeScript, Python, HTML, CSS, Kotlin, Markdown

### 4. Bridge.kt - 桥接层扩展
新增 Action：
- initIntentGraph
- loadIntentGraph
- saveIntentGraph
- analyzeFile
- addIntentNode, addIntentEdge
- searchIntentGraph
- getIntentGraphStats
- getImpactedNodes

### 5. 前端 UI 更新
- Analyze 按钮
- Graph 按钮
- 意图图谱面板
- 节点卡片

---

## 下一步计划

- [ ] 实现节点间关系的自动提取
- [ ] 添加意图图谱的可视化图表
- [ ] 实现语义搜索功能
- [ ] 添加代码时光机基础
- [ ] 实现预测性编辑