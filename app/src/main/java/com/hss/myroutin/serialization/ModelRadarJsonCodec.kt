package com.hss.myroutin.serialization

import com.hss.myroutin.model.ModelRadarEfficiency
import com.hss.myroutin.model.ModelRadarModel
import com.hss.myroutin.model.ModelRadarRecommendation
import com.hss.myroutin.model.ModelRadarSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * 说明：负责 CodexRadar 远端 JSON 聚合与本地紧凑快照编解码，页面不接触第三方原始字段。
 *
 * @作者 huangssh
 * @版本 3.0
 */
internal object ModelRadarJsonCodec {

    /**
     * 按 CodexRadar 当前 schema=1 的公开计算口径聚合模型数据。
     * 每道题只取最新 runner，IQ 按通过题数 / 有效题数 × 150 计算。
     * @param intelligenceBody 完整智力效率接口正文
     * @param metricsBody 智力效率轻量指标接口正文，可为空
     * @param insightsBody 场景推荐接口正文，可为空
     * @param ratingsBody 社区评分接口正文，可为空
     * @param fetchedAt 本次成功拉取的本地时间
     */
    fun decodeRemoteBundle(
        intelligenceBody: String,
        metricsBody: String?,
        insightsBody: String?,
        ratingsBody: String?,
        fetchedAt: Long
    ): ModelRadarSnapshot {
        val intelligence = JSONObject(intelligenceBody)
        require(intelligence.optInt("schema") == INTELLIGENCE_SCHEMA) {
            "Unsupported intelligence schema"
        }
        val taskIds = intelligence.optJSONArray("tasks").objectIds("id")
        val cells = intelligence.optJSONObject("cells")
            ?: throw IllegalArgumentException("Missing radar cells")
        val ratingsById = parseRatings(ratingsBody)
        val pricesByModel = parseInputPrices(intelligence)
        val radarPoints = intelligence.optJSONArray("combos")
            .objects()
            .mapNotNull { combo -> aggregatePoint(combo, taskIds, cells, ratingsById) }
        val pointsByModel = radarPoints
            .groupBy(RadarPoint::modelId)
        val metrics = parseIntelligenceMetrics(metricsBody)
        val models = DISPLAY_MODEL_IDS.map { modelId ->
            val point = pointsByModel[modelId]?.maxByOrNull { it.iq }
            point.toModel(
                modelId = modelId,
                price = pricesByModel[modelId] ?: FALLBACK_INPUT_PRICES[modelId],
                metricsPoint = point?.let {
                    metrics?.pointsById?.get("${it.modelId}-${it.effort}")
                }
            )
        }
        val insights = insightsBody?.let { body -> runCatching { JSONObject(body) }.getOrNull() }
        return ModelRadarSnapshot(
            sourceUpdatedAt = metrics?.sourceUpdatedAt
                ?: insights?.stringOrNull("source_updated_at")
                ?: intelligence.stringOrNull("baseline_generated_at"),
            fetchedAt = fetchedAt,
            recommendations = parseRecommendations(insights),
            models = models,
            efficiencyPoints = radarPoints
                .filter { it.modelId in EFFICIENCY_MODEL_IDS }
                .map { point ->
                    val metricsPoint = metrics?.pointsById?.get("${point.modelId}-${point.effort}")
                    point.toEfficiency(
                        recentRuns24h = metricsPoint?.runs24h,
                        metricsPoint = metricsPoint
                    )
                }
                .sortedWith(EFFICIENCY_ORDER),
            recentRuns24h = metrics?.runs24h,
            recentRuns48h = metrics?.runs48h,
            totalRuns = metrics?.totalRuns
        )
    }

    /** 将紧凑快照编码到本地缓存，第三方任务、贡献者和历史明细不会落盘。 */
    fun encodeSnapshot(snapshot: ModelRadarSnapshot): String {
        return JSONObject().apply {
            put("schema", CACHE_SCHEMA)
            putNullable("sourceUpdatedAt", snapshot.sourceUpdatedAt)
            put("fetchedAt", snapshot.fetchedAt)
            putNullable("recentRuns24h", snapshot.recentRuns24h)
            putNullable("recentRuns48h", snapshot.recentRuns48h)
            putNullable("totalRuns", snapshot.totalRuns)
            put("recommendations", JSONArray().apply {
                snapshot.recommendations.forEach { recommendation ->
                    put(JSONObject().apply {
                        put("title", recommendation.title)
                        put("modelId", recommendation.modelId)
                        put("modelName", recommendation.modelName)
                        put("effort", recommendation.effort)
                        putNullable("iq", recommendation.iq)
                        putNullable("averageCostUsd", recommendation.averageCostUsd)
                        putNullable("averageDurationMinutes", recommendation.averageDurationMinutes)
                    })
                }
            })
            put("models", JSONArray().apply {
                snapshot.models.forEach { model -> put(model.toJsonObject()) }
            })
            put("efficiencyPoints", JSONArray().apply {
                snapshot.efficiencyPoints.forEach { point -> put(point.toJsonObject()) }
            })
        }.toString()
    }

    /**
     * 读取本地紧凑快照；schema 不匹配时拒绝复用，避免旧字段被错误解释。
     * @param json 本地保存的快照 JSON
     */
    fun decodeSnapshot(json: String): ModelRadarSnapshot {
        val root = JSONObject(json)
        require(root.optInt("schema") == CACHE_SCHEMA) { "Unsupported cache schema" }
        return ModelRadarSnapshot(
            sourceUpdatedAt = root.stringOrNull("sourceUpdatedAt"),
            fetchedAt = root.optLong("fetchedAt"),
            recommendations = root.optJSONArray("recommendations").objects().map { item ->
                ModelRadarRecommendation(
                    title = item.optString("title"),
                    modelId = item.optString("modelId"),
                    modelName = item.optString("modelName"),
                    effort = item.optString("effort"),
                    iq = item.doubleOrNull("iq"),
                    averageCostUsd = item.doubleOrNull("averageCostUsd"),
                    averageDurationMinutes = item.doubleOrNull("averageDurationMinutes")
                )
            },
            // 兼容旧缓存：历史快照可能按旧顺序保存，读取时统一恢复当前展示顺序。
            models = root.optJSONArray("models").objects()
                .map { item -> item.toRadarModel() }
                .sortedWith(compareBy { model ->
                    DISPLAY_MODEL_IDS.indexOf(model.id).let { index ->
                        if (index >= 0) index else Int.MAX_VALUE
                    }
                }),
            efficiencyPoints = root.optJSONArray("efficiencyPoints").objects()
                .map { item -> item.toRadarEfficiency() },
            recentRuns24h = root.intOrNull("recentRuns24h"),
            recentRuns48h = root.intOrNull("recentRuns48h"),
            totalRuns = root.intOrNull("totalRuns")
        )
    }

    /** 聚合单个模型档位的最新有效样本，计算口径与 CodexRadar 页面保持一致。 */
    private fun aggregatePoint(
        combo: JSONObject,
        taskIds: List<String>,
        cells: JSONObject,
        ratingsById: Map<String, CommunityRating>
    ): RadarPoint? {
        val modelId = combo.stringOrNull("model") ?: return null
        if (modelId !in SUPPORTED_MODEL_IDS) {
            return null
        }
        val effort = combo.stringOrNull("effort") ?: return null
        var passedTasks = 0
        var validTasks = 0
        var durationSum = 0.0
        var durationSamples = 0
        var costSum = 0.0
        var costSamples = 0
        var totalRuns = 0
        var agentStepsSum = 0.0
        var agentStepsSamples = 0
        var totalTokensSum = 0.0
        var totalTokenSamples = 0
        var inputTokensSum = 0.0
        var cacheTokensSum = 0.0
        var cacheTokenSamples = 0
        taskIds.forEach { taskId ->
            val runners = cells.optJSONObject("$taskId|$modelId|$effort")
                ?.optJSONArray("ran_by")
                ?: return@forEach
            totalRuns += runners.length()
            val runner = runners.optJSONObject(0) ?: return@forEach
            if (runner.has("passed") && !runner.isNull("passed")) {
                validTasks += 1
                if (runner.optBoolean("passed")) passedTasks += 1
            }
            runner.doubleOrNull("duration_sec")?.takeIf { it > 0.0 }?.let { durationSeconds ->
                durationSum += durationSeconds / SECONDS_PER_MINUTE
                durationSamples += 1
            }
            runner.doubleOrNull("actual_cost_usd")?.takeIf { it >= 0.0 }?.let { cost ->
                if (effort != "ultra" || runner.optBoolean("cost_complete")) {
                    costSum += cost
                    costSamples += 1
                }
            }
            runner.doubleOrNull("n_agent_steps")?.takeIf { it >= 0.0 }?.let { steps ->
                agentStepsSum += steps
                agentStepsSamples += 1
            }
            val inputTokens = runner.doubleOrNull("n_input_tokens")
            val outputTokens = runner.doubleOrNull("n_output_tokens")
            val cacheTokens = runner.doubleOrNull("n_cache_tokens")
            if (inputTokens != null || outputTokens != null) {
                totalTokensSum += maxOf(0.0, inputTokens ?: 0.0) + maxOf(0.0, outputTokens ?: 0.0)
                totalTokenSamples += 1
            }
            if (inputTokens != null && inputTokens > 0.0 && cacheTokens != null && cacheTokens >= 0.0) {
                inputTokensSum += inputTokens
                cacheTokensSum += cacheTokens
                cacheTokenSamples += 1
            }
        }
        if (validTasks == 0) {
            return null
        }
        val rating = ratingsById["$modelId-$effort"]
        return RadarPoint(
            modelId = modelId,
            effort = effort,
            iq = passedTasks.toDouble() / validTasks * IQ_SCALE,
            passedTasks = passedTasks,
            validTasks = validTasks,
            averageCostUsd = costSum.averageOrNull(costSamples),
            averageDurationMinutes = durationSum.averageOrNull(durationSamples),
            totalRuns = totalRuns,
            averageAgentSteps = agentStepsSum.averageOrNull(agentStepsSamples),
            averageTotalTokens = totalTokensSum.averageOrNull(totalTokenSamples),
            cacheHitRate = if (cacheTokenSamples > 0 && inputTokensSum > 0.0) {
                cacheTokensSum / inputTokensSum
            } else {
                null
            },
            communityRating = rating?.average,
            communityRatingCount = rating?.count
        )
    }

    /**
     * 每个推荐场景保留前两个候选，首选与备选仍沿用服务端排序，不在客户端重新猜测权重。
     */
    private fun parseRecommendations(insights: JSONObject?): List<ModelRadarRecommendation> {
        if (insights?.optInt("schema") != INSIGHTS_SCHEMA) {
            return emptyList()
        }
        return insights.optJSONArray("recommendations").objects().flatMap { category ->
            val title = category.stringOrNull("title") ?: return@flatMap emptyList()
            category.optJSONArray("items").objects()
                .take(MAX_RECOMMENDATIONS_PER_SCENE)
                .mapNotNull { item ->
                    val modelId = item.stringOrNull("model") ?: return@mapNotNull null
                    ModelRadarRecommendation(
                        title = title,
                        modelId = modelId,
                        modelName = DISPLAY_MODEL_NAMES[modelId] ?: modelId.removePrefix("gpt-"),
                        effort = item.stringOrNull("effort").orEmpty(),
                        iq = item.doubleOrNull("iq"),
                        averageCostUsd = item.doubleOrNull("average_cost_usd"),
                        averageDurationMinutes = item.doubleOrNull("average_duration_minutes")
                    )
                }
        }
    }

    /** 社区评分按模型与推理档位 ID 建立索引，未评分档位保持空值。 */
    private fun parseRatings(body: String?): Map<String, CommunityRating> {
        val root = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        if (!root.optBoolean("ok", true)) {
            return emptyMap()
        }
        return root.optJSONArray("models").objects().mapNotNull { item ->
            val id = item.stringOrNull("id") ?: return@mapNotNull null
            id to CommunityRating(
                average = item.doubleOrNull("average"),
                count = item.intOrNull("count") ?: 0
            )
        }.toMap()
    }

    /** 输入价只读取每百万 Token 的 input 字段，其余缓存价和输出价不混入本页。 */
    private fun parseInputPrices(intelligence: JSONObject): Map<String, Double> {
        val priceObject = intelligence.optJSONObject("token_pricing")
            ?.optJSONObject("usd_per_million")
            ?: return emptyMap()
        return priceObject.keys().asSequence().mapNotNull { modelId ->
            priceObject.optJSONObject(modelId)?.doubleOrNull("input")?.let { modelId to it }
        }.toMap()
    }

    private fun RadarPoint?.toModel(
        modelId: String,
        price: Double?,
        metricsPoint: IntelligenceMetricPoint?
    ): ModelRadarModel {
        return ModelRadarModel(
            id = modelId,
            name = DISPLAY_MODEL_NAMES.getValue(modelId),
            inputPriceUsdPerMillion = price,
            bestEffort = this?.effort,
            iq = this?.iq,
            passedTasks = this?.passedTasks,
            validTasks = this?.validTasks,
            averageCostUsd = this?.averageCostUsd,
            averageDurationMinutes = this?.averageDurationMinutes,
            totalRuns = this?.totalRuns,
            averageAgentSteps = this?.averageAgentSteps ?: metricsPoint?.averageAgentSteps,
            averageTotalTokens = this?.averageTotalTokens ?: metricsPoint?.averageTotalTokens,
            cacheHitRate = this?.cacheHitRate ?: metricsPoint?.cacheHitRate,
            communityRating = this?.communityRating,
            communityRatingCount = this?.communityRatingCount
        )
    }

    /** 将内部聚合点转换为页面按模型/档位展示的效率卡片数据。 */
    private fun RadarPoint.toEfficiency(
        recentRuns24h: Int?,
        metricsPoint: IntelligenceMetricPoint?
    ): ModelRadarEfficiency {
        return ModelRadarEfficiency(
            id = "$modelId-$effort",
            modelId = modelId,
            modelName = DISPLAY_MODEL_NAMES.getValue(modelId),
            effort = effort,
            iq = iq,
            passedTasks = passedTasks,
            validTasks = validTasks,
            recentRuns24h = recentRuns24h,
            averageCostUsd = averageCostUsd,
            averageDurationMinutes = averageDurationMinutes,
            totalRuns = totalRuns,
            averageAgentSteps = averageAgentSteps ?: metricsPoint?.averageAgentSteps,
            averageTotalTokens = averageTotalTokens ?: metricsPoint?.averageTotalTokens,
            cacheHitRate = cacheHitRate ?: metricsPoint?.cacheHitRate,
            communityRating = communityRating,
            communityRatingCount = communityRatingCount
        )
    }

    private fun ModelRadarModel.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            putNullable("inputPriceUsdPerMillion", inputPriceUsdPerMillion)
            putNullable("bestEffort", bestEffort)
            putNullable("iq", iq)
            putNullable("passedTasks", passedTasks)
            putNullable("validTasks", validTasks)
            putNullable("averageCostUsd", averageCostUsd)
            putNullable("averageDurationMinutes", averageDurationMinutes)
            putNullable("totalRuns", totalRuns)
            putNullable("averageAgentSteps", averageAgentSteps)
            putNullable("averageTotalTokens", averageTotalTokens)
            putNullable("cacheHitRate", cacheHitRate)
            putNullable("communityRating", communityRating)
            putNullable("communityRatingCount", communityRatingCount)
        }
    }

    private fun ModelRadarEfficiency.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("modelId", modelId)
            put("modelName", modelName)
            put("effort", effort)
            put("iq", iq)
            putNullable("passedTasks", passedTasks)
            putNullable("validTasks", validTasks)
            putNullable("recentRuns24h", recentRuns24h)
            putNullable("averageCostUsd", averageCostUsd)
            putNullable("averageDurationMinutes", averageDurationMinutes)
            put("totalRuns", totalRuns)
            putNullable("averageAgentSteps", averageAgentSteps)
            putNullable("averageTotalTokens", averageTotalTokens)
            putNullable("cacheHitRate", cacheHitRate)
            putNullable("communityRating", communityRating)
            putNullable("communityRatingCount", communityRatingCount)
        }
    }

    private fun JSONObject.toRadarModel(): ModelRadarModel {
        return ModelRadarModel(
            id = optString("id"),
            name = optString("name"),
            inputPriceUsdPerMillion = doubleOrNull("inputPriceUsdPerMillion"),
            bestEffort = stringOrNull("bestEffort"),
            iq = doubleOrNull("iq"),
            passedTasks = intOrNull("passedTasks"),
            validTasks = intOrNull("validTasks"),
            averageCostUsd = doubleOrNull("averageCostUsd"),
            averageDurationMinutes = doubleOrNull("averageDurationMinutes"),
            totalRuns = intOrNull("totalRuns"),
            averageAgentSteps = doubleOrNull("averageAgentSteps"),
            averageTotalTokens = doubleOrNull("averageTotalTokens"),
            cacheHitRate = doubleOrNull("cacheHitRate"),
            communityRating = doubleOrNull("communityRating"),
            communityRatingCount = intOrNull("communityRatingCount")
        )
    }

    private fun JSONObject.toRadarEfficiency(): ModelRadarEfficiency {
        return ModelRadarEfficiency(
            id = optString("id"),
            modelId = optString("modelId"),
            modelName = optString("modelName"),
            effort = optString("effort"),
            iq = optDouble("iq", 0.0),
            passedTasks = intOrNull("passedTasks"),
            validTasks = intOrNull("validTasks"),
            recentRuns24h = intOrNull("recentRuns24h"),
            averageCostUsd = doubleOrNull("averageCostUsd"),
            averageDurationMinutes = doubleOrNull("averageDurationMinutes"),
            totalRuns = optInt("totalRuns"),
            averageAgentSteps = doubleOrNull("averageAgentSteps"),
            averageTotalTokens = doubleOrNull("averageTotalTokens"),
            cacheHitRate = doubleOrNull("cacheHitRate"),
            communityRating = doubleOrNull("communityRating"),
            communityRatingCount = intOrNull("communityRatingCount")
        )
    }

    /** 解析轻量接口的整体更新时间、档位样本量和运行效率指标。 */
    private fun parseIntelligenceMetrics(body: String?): IntelligenceMetrics? {
        val root = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        if (root.optInt("schema") != METRICS_SCHEMA) return null
        val pointsById = root.optJSONArray("points").objects().mapNotNull { item ->
            val modelId = item.stringOrNull("model") ?: return@mapNotNull null
            val effort = item.stringOrNull("effort") ?: return@mapNotNull null
            "$modelId-$effort" to IntelligenceMetricPoint(
                runs24h = item.intOrNull("runs_24h"),
                averageAgentSteps = item.doubleOrNull("average_agent_steps"),
                averageTotalTokens = item.doubleOrNull("average_total_tokens"),
                cacheHitRate = item.doubleOrNull("cache_hit_rate")
            )
        }.toMap()
        return IntelligenceMetrics(
            sourceUpdatedAt = root.stringOrNull("source_updated_at"),
            runs24h = root.intOrNull("runs_24h_total"),
            runs48h = root.intOrNull("runs_48h_total"),
            totalRuns = root.intOrNull("runs_total"),
            pointsById = pointsById
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull(::optJSONObject)
    }

    private fun JSONArray?.objectIds(fieldName: String): List<String> {
        return objects().mapNotNull { item -> item.stringOrNull(fieldName) }
    }

    private fun Double.averageOrNull(sampleCount: Int): Double? {
        return if (sampleCount > 0) this / sampleCount else null
    }

    /** 单个模型档位的聚合结果，仅在远端响应解析期间存在。 */
    private data class RadarPoint(
        val modelId: String,
        val effort: String,
        val iq: Double,
        val passedTasks: Int,
        val validTasks: Int,
        val averageCostUsd: Double?,
        val averageDurationMinutes: Double?,
        val totalRuns: Int,
        val averageAgentSteps: Double?,
        val averageTotalTokens: Double?,
        val cacheHitRate: Double?,
        val communityRating: Double?,
        val communityRatingCount: Int?
    )

    /** 社区体感分及有效评分人数，按完整模型档位 ID 匹配。 */
    private data class CommunityRating(
        val average: Double?,
        val count: Int
    )

    /** 轻量智力接口的整体统计和档位效率指标索引。 */
    private data class IntelligenceMetrics(
        val sourceUpdatedAt: String?,
        val runs24h: Int?,
        val runs48h: Int?,
        val totalRuns: Int?,
        val pointsById: Map<String, IntelligenceMetricPoint>
    )

    /** 轻量接口单个模型档位的运行效率数据，作为完整任务明细的字段兜底。 */
    private data class IntelligenceMetricPoint(
        val runs24h: Int?,
        val averageAgentSteps: Double?,
        val averageTotalTokens: Double?,
        val cacheHitRate: Double?
    )

    /** 各公开接口独立演进 schema，禁止再用同一个版本号同时校验。 */
    private const val INTELLIGENCE_SCHEMA = 1
    private const val INSIGHTS_SCHEMA = 1
    private const val METRICS_SCHEMA = 2
    private const val CACHE_SCHEMA = 3
    private const val MAX_RECOMMENDATIONS_PER_SCENE = 2
    private const val IQ_SCALE = 150.0
    private const val SECONDS_PER_MINUTE = 60.0

    /** 展示顺序延续现有价格页习惯，5.4 固定放在最后。 */
    private val DISPLAY_MODEL_IDS = listOf(
        "gpt-5.6-sol",
        "gpt-5.6-terra",
        "gpt-5.6-luna",
        "gpt-5.5",
        "gpt-5.4"
    )
    /** 效率页按官网当前公开数据展示，包含 DeepSeek，5.4 没有对应公开测量时不造卡片。 */
    private val EFFICIENCY_MODEL_IDS = listOf(
        "gpt-5.6-sol",
        "gpt-5.6-terra",
        "gpt-5.6-luna",
        "gpt-5.5",
        "deepseek-v4-flash"
    )
    private val SUPPORTED_MODEL_IDS = (DISPLAY_MODEL_IDS + EFFICIENCY_MODEL_IDS).toSet()
    private val DISPLAY_MODEL_NAMES = mapOf(
        "gpt-5.6-sol" to "5.6-sol",
        "gpt-5.6-terra" to "5.6-terra",
        "gpt-5.6-luna" to "5.6-luna",
        "gpt-5.4" to "5.4",
        "gpt-5.5" to "5.5",
        "deepseek-v4-flash" to "DeepSeek V4 Flash"
    )
    private val FALLBACK_INPUT_PRICES = mapOf(
        "gpt-5.6-sol" to 5.0,
        "gpt-5.6-terra" to 2.0,
        "gpt-5.6-luna" to 0.2,
        "gpt-5.4" to 2.5,
        "gpt-5.5" to 5.0
    )

    /** 官网的卡片顺序为模型分组、档位从高到低，保持手机端阅读顺序稳定。 */
    private val EFFORT_ORDER = listOf("ultra", "max", "xhigh", "high", "medium", "low")
    private val EFFICIENCY_ORDER = compareBy<ModelRadarEfficiency>(
        { EFFICIENCY_MODEL_IDS.indexOf(it.modelId).coerceAtLeast(0) },
        { EFFORT_ORDER.indexOf(it.effort).coerceAtLeast(0) }
    )
}
