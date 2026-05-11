package com.aiide

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class AgentProposal(
    val id: String,
    val agentName: String,
    val proposalType: String,
    val content: String,
    val confidence: Double,
    val reasoning: String,
    val timestamp: Long,
    val round: Int = 0
)

data class CollaborationSession(
    val id: String,
    val topic: String,
    val participants: List<String>,
    val status: String,
    val rounds: Int,
    val outcome: String,
    val startTime: Long,
    val endTime: Long = 0
)

data class DebateResult(
    val topic: String,
    val sideA: String,
    val sideB: String,
    val argumentsA: List<String>,
    val argumentsB: List<String>,
    val winner: String,
    val rounds: Int
)

class CrossAgentCollaboration {
    companion object {
        private const val MAX_AGENTS = 20
        private const val MAX_ROUNDS = 5
        private const val CONSENSUS_THRESHOLD = 0.7
        private const val MAX_SESSIONS = 50
    }

    private val executor: ExecutorService = Executors.newFixedThreadPool(8)
    private val isShutdownFlag = AtomicBoolean(false)
    private val agents = ConcurrentHashMap<String, AgentCapability>()
    private val activeSessions = ConcurrentHashMap<String, CollaborationSession>()
    private val collaborationHistory = CopyOnWriteArrayList<CollaborationSession>()
    private val idCounter = AtomicInteger(0)

    fun registerAgent(name: String, capabilities: Set<String>, description: String): Result<Boolean> {
        if (isShutdownFlag.get()) return Result.failure(IllegalStateException("System is shutdown"))
        if (name.isBlank()) return Result.failure(IllegalArgumentException("Agent name cannot be blank"))
        if (capabilities.isEmpty()) return Result.failure(IllegalArgumentException("Agent must have at least one capability"))
        if (agents.size >= MAX_AGENTS) return Result.failure(IllegalStateException("Maximum agent limit: $MAX_AGENTS"))
        if (agents.containsKey(name)) return Result.failure(IllegalArgumentException("Agent already registered: $name"))
        agents[name] = AgentCapability(name, capabilities.map { it.lowercase() }.toSet(), description)
        return Result.success(true)
    }

    fun unregisterAgent(name: String): Boolean {
        if (activeSessions.values.any { it.participants.contains(name) }) return false
        return agents.remove(name) != null
    }

    fun startCollaboration(topic: String, participantNames: List<String>, maxRounds: Int = MAX_ROUNDS): Result<CollaborationSession> {
        if (isShutdownFlag.get()) return Result.failure(IllegalStateException("System is shutdown"))
        if (topic.isBlank()) return Result.failure(IllegalArgumentException("Topic cannot be blank"))
        if (participantNames.isEmpty()) return Result.failure(IllegalArgumentException("No participants provided"))

        val effectiveMaxRounds = maxRounds.coerceIn(1, MAX_ROUNDS)
        val participants = participantNames.filter { it.isNotBlank() }.distinct().filter { agents.containsKey(it) }
        if (participants.isEmpty()) return Result.failure(IllegalArgumentException("No valid participants"))

        val sessionId = generateId("collab")
        val now = System.currentTimeMillis()
        val session = CollaborationSession(id = sessionId, topic = topic, participants = participants, status = "running", rounds = 0, outcome = "", startTime = now)
        activeSessions[sessionId] = session

        val proposals = CopyOnWriteArrayList<AgentProposal>()
        for (round in 0 until effectiveMaxRounds) {
            if (isShutdownFlag.get()) break
            val roundProposals = participants.mapNotNull { agentName ->
                val agent = agents[agentName] ?: return@mapNotNull null
                generateProposal(agent, topic, round)
            }
            proposals.addAll(roundProposals)

            val avgConfidence = proposals.map { it.confidence }.average()
            if (avgConfidence >= CONSENSUS_THRESHOLD) {
                val finalSession = session.copy(status = "consensus_reached", rounds = round + 1, outcome = proposals.joinToString("\n") { it.content }, endTime = System.currentTimeMillis())
                activeSessions[sessionId] = finalSession
                addHistory(finalSession)
                return Result.success(finalSession)
            }
        }

        val bestProposal = proposals.maxByOrNull { it.confidence }
        val finalOutcome = bestProposal?.content ?: "No consensus reached"
        val finalSession = session.copy(status = "completed", rounds = effectiveMaxRounds, outcome = finalOutcome, endTime = System.currentTimeMillis())
        activeSessions[sessionId] = finalSession
        addHistory(finalSession)
        return Result.success(finalSession)
    }

    fun peerReview(topic: String, reviewerName: String, authorName: String): Result<AgentProposal> {
        if (isShutdownFlag.get()) return Result.failure(IllegalStateException("System is shutdown"))
        if (topic.isBlank()) return Result.failure(IllegalArgumentException("Topic cannot be blank"))
        if (reviewerName == authorName) return Result.failure(IllegalArgumentException("Reviewer and author must be different"))
        val reviewer = agents[reviewerName] ?: return Result.failure(IllegalArgumentException("Reviewer not found"))
        val author = agents[authorName] ?: return Result.failure(IllegalArgumentException("Author not found"))
        return Result.success(generateReview(reviewer, author, topic))
    }

    fun debate(topic: String, sideA: String, sideB: String, maxRounds: Int = 3): Result<DebateResult> {
        if (isShutdownFlag.get()) return Result.failure(IllegalStateException("System is shutdown"))
        if (topic.isBlank()) return Result.failure(IllegalArgumentException("Topic cannot be blank"))
        if (sideA == sideB) return Result.failure(IllegalArgumentException("Side A and Side B must be different"))
        val agentA = agents[sideA] ?: return Result.failure(IllegalArgumentException("Agent not found: $sideA"))
        val agentB = agents[sideB] ?: return Result.failure(IllegalArgumentException("Agent not found: $sideB"))

        val effectiveMaxRounds = maxRounds.coerceIn(1, 10)
        val argumentsA = mutableListOf<String>()
        val argumentsB = mutableListOf<String>()

        for (round in 0 until effectiveMaxRounds) {
            argumentsA.add("Supporting $topic: ${agentA.description} perspective")
            argumentsB.add("Opposing $topic: ${agentB.description} perspective")
        }

        val scoreA = argumentsA.size * 0.1 + agentA.capabilities.size * 0.1
        val scoreB = argumentsB.size * 0.1 + agentB.capabilities.size * 0.1
        val winner = when { scoreA > scoreB -> agentA.name; scoreB > scoreA -> agentB.name; else -> "tie" }

        return Result.success(DebateResult(topic = topic, sideA = sideA, sideB = sideB, argumentsA = argumentsA, argumentsB = argumentsB, winner = winner, rounds = effectiveMaxRounds))
    }

    fun askExperts(question: String, expertNames: List<String>): Result<List<AgentProposal>> {
        if (isShutdownFlag.get()) return Result.failure(IllegalStateException("System is shutdown"))
        if (question.isBlank()) return Result.failure(IllegalArgumentException("Question cannot be blank"))
        val experts = expertNames.filter { it.isNotBlank() }.distinct().mapNotNull { agents[it] }
        if (experts.isEmpty()) return Result.failure(IllegalArgumentException("No experts found"))
        return Result.success(experts.map { generateExpertAnswer(it, question) })
    }

    fun getActiveSessions(): List<CollaborationSession> = activeSessions.values.toList()
    fun getCollaborationHistory(): List<CollaborationSession> = collaborationHistory.toList()
    fun getRegisteredAgents(): List<AgentCapability> = agents.values.toList()
    fun getAgentCount(): Int = agents.size
    fun clearHistory() { collaborationHistory.clear() }
    fun reset() { cleanup(); isShutdownFlag.set(false) }

    fun cleanup() {
        isShutdownFlag.set(true)
        executor.shutdown()
        try { if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow() }
        catch (e: InterruptedException) { executor.shutdownNow() }
        agents.clear(); activeSessions.clear()
    }

    private fun generateId(prefix: String): String = "$prefix-${System.currentTimeMillis()}-${idCounter.incrementAndGet()}-${UUID.randomUUID().toString().take(8)}"

    private fun generateProposal(agent: AgentCapability, topic: String, round: Int): AgentProposal {
        return AgentProposal(
            id = generateId("proposal"), agentName = agent.name, proposalType = determineProposalType(agent),
            content = "Agent ${agent.name} suggests: ${generateSuggestionContent(agent, topic)}",
            confidence = calculateAgentConfidence(agent, round),
            reasoning = "Based on ${agent.description}", timestamp = System.currentTimeMillis(), round = round
        )
    }

    private fun determineProposalType(agent: AgentCapability): String = when {
        agent.capabilities.contains("coding") -> "code_solution"
        agent.capabilities.contains("review") -> "review_feedback"
        agent.capabilities.contains("architecture") -> "architectural_design"
        agent.capabilities.contains("testing") -> "test_strategy"
        else -> "general_suggestion"
    }

    private fun calculateAgentConfidence(agent: AgentCapability, round: Int): Double {
        val baseConfidence = 0.5 + (agent.capabilities.size * 0.05).coerceAtMost(0.4)
        return (baseConfidence + round * 0.02).coerceIn(0.1, 0.95)
    }

    private fun generateSuggestionContent(agent: AgentCapability, topic: String): String = when {
        agent.capabilities.contains("coding") -> "Implementation approach for $topic"
        agent.capabilities.contains("review") -> "Code review findings for $topic"
        agent.capabilities.contains("architecture") -> "Architectural recommendation for $topic"
        agent.capabilities.contains("testing") -> "Testing strategy for $topic"
        else -> "General suggestion for $topic"
    }

    private fun generateReview(reviewer: AgentCapability, author: AgentCapability, topic: String): AgentProposal {
        return AgentProposal(
            id = generateId("review"), agentName = reviewer.name, proposalType = "peer_review",
            content = "Review of ${author.name}'s work on $topic",
            confidence = 0.6 + (reviewer.capabilities.size * 0.05).coerceAtMost(0.35),
            reasoning = "Reviewer capabilities: ${reviewer.capabilities.joinToString(", ")}", timestamp = System.currentTimeMillis()
        )
    }

    private fun generateExpertAnswer(expert: AgentCapability, question: String): AgentProposal {
        val relevanceScore = expert.capabilities.size.coerceAtMost(5) / 5.0
        return AgentProposal(
            id = generateId("expert"), agentName = expert.name, proposalType = "expert_opinion",
            content = "Expert ${expert.name} on: $question",
            confidence = (0.7 + expert.capabilities.size * 0.05 * relevanceScore).coerceAtMost(0.95),
            reasoning = "Expertise: ${expert.capabilities.joinToString(", ")}", timestamp = System.currentTimeMillis()
        )
    }

    private fun addHistory(session: CollaborationSession) { collaborationHistory.add(session) }

    data class AgentCapability(val name: String, val capabilities: Set<String>, val description: String)
}