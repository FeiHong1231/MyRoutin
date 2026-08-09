package com.hss.myroutin.model

/**
 * 说明：模型雷达的紧凑展示快照，只保留 App 页面需要的数据，避免缓存第三方接口的完整任务明细。
 *
 * @作者 huangssh
 * @版本 3.0
 */
data class ModelRadarSnapshot(
    val sourceUpdatedAt: String?,
    val fetchedAt: Long,
    val recommendations: List<ModelRadarRecommendation>,
    val models: List<ModelRadarModel>,
    /** 每个模型与推理档位的效率明细，供手机端按档位逐张展示。 */
    val efficiencyPoints: List<ModelRadarEfficiency> = emptyList(),
    /** 轻量指标接口提供的近期开测量，用于页面顶部的整体数据量提示。 */
    val recentRuns24h: Int? = null,
    val recentRuns48h: Int? = null,
    val totalRuns: Int? = null
)

/**
 * 说明：一个使用场景下的首选模型，费用为 CodexRadar 给出的单次任务平均费用。
 *
 * @作者 huangssh
 * @版本 3.0
 */
data class ModelRadarRecommendation(
    val title: String,
    val modelId: String,
    val modelName: String,
    val effort: String,
    val iq: Double?,
    val averageCostUsd: Double?,
    val averageDurationMinutes: Double?
)

/**
 * 说明：模型概览卡片数据；每个模型选取当前 IQ 最高的推理档位作为页面代表值。
 *
 * @作者 huangssh
 * @版本 3.0
 */
data class ModelRadarModel(
    val id: String,
    val name: String,
    val inputPriceUsdPerMillion: Double?,
    val bestEffort: String?,
    val iq: Double?,
    val passedTasks: Int?,
    val validTasks: Int?,
    val averageCostUsd: Double?,
    val averageDurationMinutes: Double?,
    val totalRuns: Int?,
    val averageAgentSteps: Double?,
    val averageTotalTokens: Double?,
    val cacheHitRate: Double?,
    val communityRating: Double?,
    val communityRatingCount: Int?
)

/**
 * 说明：单个模型推理档位的智力效率数据，保留官网效率卡片需要的主要指标。
 *
 * @作者 huangssh
 * @版本 3.0
 */
data class ModelRadarEfficiency(
    val id: String,
    val modelId: String,
    val modelName: String,
    val effort: String,
    val iq: Double,
    /** 当前档位通过题数与有效题数，用于决策页说明推荐依据而不是重复展示排行。 */
    val passedTasks: Int? = null,
    val validTasks: Int? = null,
    val recentRuns24h: Int?,
    val averageCostUsd: Double?,
    val averageDurationMinutes: Double?,
    val totalRuns: Int,
    val averageAgentSteps: Double?,
    val averageTotalTokens: Double?,
    val cacheHitRate: Double?,
    /** 社区体感必须按模型与 effort 精确匹配，不能复用模型最高 IQ 档位的评分。 */
    val communityRating: Double? = null,
    val communityRatingCount: Int? = null
)
