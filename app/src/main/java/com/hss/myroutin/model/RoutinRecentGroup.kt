package com.hss.myroutin.model

/**
 * 说明：保存用户最近一次从 Routin 模型请求日志同步到的分组信息，不包含网页登录凭证。
 *
 * @作者 huangssh
 * @版本 5.1
 */
data class RoutinRecentGroup(
    /** 日志“令牌”列展示的实际分组名称，例如 Codex Pro。 */
    val groupName: String,
    /** 日志详情中的独立计费倍率，用于提示真实额度消耗口径。 */
    val multiplier: Double,
    /** 日志页面展示的请求时间文本，保留站点当前时区和格式。 */
    val requestTime: String
)
