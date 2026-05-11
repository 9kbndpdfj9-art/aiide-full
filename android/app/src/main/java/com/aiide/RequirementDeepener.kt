package com.aiide

import java.util.*

data class RequirementDeepeningResult(val requirements: List<DeepenedRequirement>, val needsConfirmation: Boolean, val confirmationQuestions: List<String>, val summaryText: String)

class RequirementDeepener {
    private val deepeningRules = mapOf("支付" to DeepeningRule("支付", listOf(DeepenedRequirement("支付方式", RequirementStatus.SUGGESTED, "微信支付 + 支付宝"), DeepenedRequirement("支付场景", RequirementStatus.SUGGESTED, "购买商品"), DeepenedRequirement("退款流程", RequirementStatus.SUGGESTED, "支持部分退款和全额退款，原路返回"), DeepenedRequirement("订单联动", RequirementStatus.SUGGESTED, "支付成功/失败异步回调更新订单状态"), DeepenedRequirement("对账", RequirementStatus.SUGGESTED, "生成支付日志，日终对账"), DeepenedRequirement("安全", RequirementStatus.CONFIRMED, "HTTPS传输，支付参数签名验证"), DeepenedRequirement("异常处理", RequirementStatus.SUGGESTED, "支付超时、网络中断、重复支付检测")), listOf("是否有购物车？", "对账日志是否需要生成文件？", "是否需要发送支付成功通知？")),
        "登录" to DeepeningRule("登录", listOf(DeepenedRequirement("注册方式", RequirementStatus.SUGGESTED, "手机号+验证码"), DeepenedRequirement("登录态", RequirementStatus.SUGGESTED, "JWT，过期时间30天"), DeepenedRequirement("密码找回", RequirementStatus.SUGGESTED, "支持手机验证码找回"), DeepenedRequirement("多设备管理", RequirementStatus.AMBIGUOUS, ""), DeepenedRequirement("安全", RequirementStatus.CONFIRMED, "HTTPS传输，密码加密存储（bcrypt）")), listOf("是否需要限制同时登录设备数？", "注册后是否需要邮箱/手机验证？")),
        "注销" to DeepeningRule("注销", listOf(DeepenedRequirement("注销验证", RequirementStatus.SUGGESTED, "密码确认"), DeepenedRequirement("冷静期", RequirementStatus.SUGGESTED, "30天，可撤销"), DeepenedRequirement("数据处理", RequirementStatus.SUGGESTED, "匿名化订单记录"), DeepenedRequirement("关联模块", RequirementStatus.SUGGESTED, "用户表、订单表、支付日志、会话管理"), DeepenedRequirement("撤销机制", RequirementStatus.SUGGESTED, "用户登录时提醒，一键撤销")), listOf("支付日志中的用户标识是否也需要匿名化？", "30天后自动删除是定时任务还是事件触发？", "正在进行的订单如何处理？")))
    private val defaultDeepening = listOf(DeepenedRequirement("技术栈", RequirementStatus.SUGGESTED, "自动检测现有项目技术栈"), DeepenedRequirement("数据库", RequirementStatus.SUGGESTED, "使用现有数据库配置"), DeepenedRequirement("API设计", RequirementStatus.SUGGESTED, "RESTful风格"), DeepenedRequirement("测试", RequirementStatus.SUGGESTED, "单元测试 + 集成测试"), DeepenedRequirement("文档", RequirementStatus.SUGGESTED, "API文档 + 代码注释"))

    fun deepenRequirements(polishedIntent: String): RequirementDeepeningResult {
        val lowerIntent = polishedIntent.lowercase()
        val matchedRule = identifyModule(lowerIntent)
        val requirements = matchedRule?.requirementTemplate ?: defaultDeepening
        val needsConfirmation = requirements.any { it.needsConfirmation || it.status == RequirementStatus.SUGGESTED || it.status == RequirementStatus.AMBIGUOUS }
        val confirmationQuestions = matchedRule?.confirmationQuestions ?: listOf("是否需要调整上述建议方案？")
        val summaryText = buildSummaryText(polishedIntent, requirements)
        return RequirementDeepeningResult(requirements = requirements, needsConfirmation = needsConfirmation, confirmationQuestions = confirmationQuestions, summaryText = summaryText)
    }

    private fun identifyModule(lowerIntent: String): DeepeningRule? = deepeningRules.entries.find { lowerIntent.contains(it.key) }?.value

    private fun buildSummaryText(intent: String, requirements: List<DeepenedRequirement>): String = buildString {
        appendLine("需求理解摘要："); appendLine()
        requirements.forEach { req -> val icon = when (req.status) { RequirementStatus.CONFIRMED -> "✅"; RequirementStatus.SUGGESTED -> "⚠️"; RequirementStatus.AMBIGUOUS -> "❓"; RequirementStatus.SKIPPED -> "⏭️" }; appendLine("$icon ${req.name}：${req.details.ifEmpty { "待确认" }}"); if (req.suggestion.isNotEmpty() && req.status != RequirementStatus.CONFIRMED) appendLine("   建议：${req.suggestion}") }
        appendLine(); appendLine("⏩ 你可以直接回复'全部按建议方案'，或逐个回答。")
    }

    fun processConfirmations(requirements: List<DeepenedRequirement>, answers: Map<String, String>): List<DeepenedRequirement> = requirements.map { req -> val answer = answers[req.name]; if (answer != null) { if (answer.contains("全部按建议") || answer.contains("默认") || answer.isEmpty()) req.copy(status = RequirementStatus.CONFIRMED) else req.copy(details = answer, status = RequirementStatus.CONFIRMED) } else if (req.status == RequirementStatus.SUGGESTED) req.copy(status = RequirementStatus.CONFIRMED) else req }

    data class DeepeningRule(val module: String, val requirementTemplate: List<DeepenedRequirement>, val confirmationQuestions: List<String>)
}