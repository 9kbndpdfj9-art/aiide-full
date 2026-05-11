package com.aiide

class IntentSpecGenerator {
    fun generateSpec(polishedIntent: String, deepenedRequirements: List<DeepenedRequirement>, originalIntent: String): IntentSpec {
        val title = extractTitle(polishedIntent, originalIntent)
        val capabilities = extractCapabilities(deepenedRequirements)
        val userProfile = inferUserProfile(polishedIntent)
        val techStack = inferTechStack()
        val architecture = inferArchitecture(polishedIntent, deepenedRequirements)
        val knownConstraints = extractKnownConstraints(deepenedRequirements)
        val riskAreas = inferRiskAreas(polishedIntent, deepenedRequirements)
        return IntentSpec(title = title, capabilities = capabilities, userProfile = userProfile, techStack = techStack, architecture = architecture, knownConstraints = knownConstraints, riskAreas = riskAreas)
    }

    private fun extractTitle(polishedIntent: String, originalIntent: String): String {
        val lower = polishedIntent.lowercase()
        return when {
            lower.contains("支付") -> "电商平台支付模块"
            lower.contains("登录") || lower.contains("认证") -> "用户认证模块"
            lower.contains("注销") -> "用户注销模块"
            lower.contains("注册") -> "用户注册模块"
            lower.contains("订单") -> "订单管理模块"
            lower.contains("通知") -> "通知系统模块"
            lower.contains("搜索") -> "搜索服务模块"
            else -> originalIntent.take(50)
        }
    }

    private fun extractCapabilities(requirements: List<DeepenedRequirement>): List<String> = requirements.filter { it.status != RequirementStatus.SKIPPED }.map { it.details.ifEmpty { it.name } }.filter { it.isNotEmpty() }
    private fun inferUserProfile(intent: String): String { val lower = intent.lowercase(); return when {
        lower.contains("电商") || lower.contains("支付") -> "电商用户，移动端为主，支付方式偏好微信>支付宝"
        lower.contains("企业") -> "企业用户，PC端为主，需要多角色权限管理"
        lower.contains("社交") -> "C端用户，移动端优先，高并发场景"
        lower.contains("管理") || lower.contains("后台") -> "内部管理员，PC端操作，需要审计日志"
        else -> "通用用户群体，移动端和PC端并重" } }
    private fun inferTechStack(): Map<String, String> = mapOf("backend" to "Kotlin/Spring Boot", "database" to "PostgreSQL", "cache" to "Redis", "frontend" to "Android Native", "message_queue" to "RabbitMQ")
    private fun inferArchitecture(intent: String, requirements: List<DeepenedRequirement>): String { val lower = intent.lowercase(); return when {
        lower.contains("支付") -> "微服务模块，数据流：用户→前端→支付网关→第三方→回调→订单服务→数据库"
        lower.contains("登录") || lower.contains("认证") -> "中间件模式，数据流：用户请求→认证中间件→业务逻辑→响应"
        lower.contains("订单") -> "状态机模式，状态：待创建→待支付→已支付→已发货→已完成/已退款"
        else -> "标准分层架构（Controller→Service→Repository）" } }
    private fun extractKnownConstraints(requirements: List<DeepenedRequirement>): List<String> { val constraints = mutableListOf<String>(); requirements.filter { it.riskLevel == RiskLevel.HIGH }.forEach { constraints.add("${it.name}：高风险操作，需要额外验证") }; constraints.add("不可破坏现有API兼容性"); constraints.add("新增字段必须有默认值"); return constraints }
    private fun inferRiskAreas(intent: String, requirements: List<DeepenedRequirement>): List<String> { val risks = mutableListOf<String>(); val lower = intent.lowercase(); if (lower.contains("支付")) { risks.add("支付回调的幂等性必须保证"); risks.add("退款需经过人工审核还是自动执行") }; if (lower.contains("注销") || lower.contains("删除")) { risks.add("数据删除不可逆，需要冷静期和备份机制") }; if (lower.contains("登录") || lower.contains("认证")) { risks.add("认证失败次数限制，防止暴力破解"); risks.add("Token刷新机制的并发安全性") }; requirements.filter { it.riskLevel == RiskLevel.HIGH }.forEach { risks.add("${it.name}：高风险，需要额外测试覆盖") }; return risks }
}

data class IntentSpec(val title: String, val capabilities: List<String>, val userProfile: String, val techStack: Map<String, String>, val architecture: String, val knownConstraints: List<String>, val riskAreas: List<String>)