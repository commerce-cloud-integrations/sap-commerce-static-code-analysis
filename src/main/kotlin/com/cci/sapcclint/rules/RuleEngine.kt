package com.cci.sapcclint.rules

import com.cci.sapcclint.catalog.AnalysisCapability
import com.cci.sapcclint.catalog.TypeSystemCatalog
import com.cci.sapcclint.catalog.hasCapability
import com.cci.sapcclint.config.AnalyzerConfig
import com.cci.sapcclint.core.AnalysisDomain

class RuleEngine(
    private val rules: List<TypeSystemRule>,
) {

    fun evaluate(catalog: TypeSystemCatalog, config: AnalyzerConfig): List<Finding> {
        val context = RuleContext(catalog = catalog, config = config)

        return rules.flatMap { rule ->
            val settings = context.resolve(rule.ruleId, rule.defaultSeverity)
            if (!settings.enabled) {
                emptyList()
            } else {
                rule.evaluate(context)
                    .map { finding ->
                        settings.severityOverride?.let { finding.copy(severity = it) } ?: finding
                    }
            }
        }.sortedWith(compareBy({ it.location.file.toString() }, { it.location.position.line }, { it.ruleId }))
    }
}

data class RuleContext(
    val catalog: TypeSystemCatalog,
    val config: AnalyzerConfig,
) {

    fun hasCapability(capability: AnalysisCapability): Boolean = catalog.hasCapability(capability)

    fun analysisMode(): String = config.analysis.mode.lowercase()

    fun requiresFullContext(ruleId: String): Boolean = ruleId in config.capabilities.requireFullContextFor

    fun resolve(ruleId: String, defaultSeverity: FindingSeverity): EffectiveRuleSettings =
        resolveRuleSettings(config, ruleId, defaultSeverity)
}

data class EffectiveRuleSettings(
    val enabled: Boolean,
    val severityOverride: FindingSeverity?,
)

fun resolveRuleSettings(
    config: AnalyzerConfig,
    ruleId: String,
    defaultSeverity: FindingSeverity,
): EffectiveRuleSettings {
    val ruleConfig = config.rules[ruleId]
    val rawSeverity = ruleConfig?.severity?.uppercase()
    if (rawSeverity == "OFF") {
        return EffectiveRuleSettings(enabled = false, severityOverride = defaultSeverity)
    }
    val severity = when (rawSeverity) {
        "ERROR" -> FindingSeverity.ERROR
        "WARNING" -> FindingSeverity.WARNING
        else -> null
    }

    return EffectiveRuleSettings(
        enabled = ruleConfig?.enabled ?: true,
        severityOverride = severity,
    )
}

interface RegisteredRule {
    val ruleId: String
    val defaultSeverity: FindingSeverity
    val domain: AnalysisDomain
}

interface TypeSystemRule : RegisteredRule {
    override val domain: AnalysisDomain
        get() = AnalysisDomain.TYPE_SYSTEM

    fun evaluate(context: RuleContext): List<Finding>
}
