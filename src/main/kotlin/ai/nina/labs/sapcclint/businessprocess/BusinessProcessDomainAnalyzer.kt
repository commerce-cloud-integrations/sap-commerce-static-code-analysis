package ai.nina.labs.sapcclint.businessprocess

import ai.nina.labs.sapcclint.businessprocess.validation.BusinessProcessStructuralValidator
import ai.nina.labs.sapcclint.config.AnalyzerConfig
import ai.nina.labs.sapcclint.core.AnalysisDomain
import ai.nina.labs.sapcclint.core.DomainAnalysisResult
import ai.nina.labs.sapcclint.core.DomainAnalyzer
import ai.nina.labs.sapcclint.core.RepositoryAnalysisContext
import ai.nina.labs.sapcclint.itemsxml.model.SourcePosition
import ai.nina.labs.sapcclint.rules.EffectiveRuleSettings
import ai.nina.labs.sapcclint.rules.Finding
import ai.nina.labs.sapcclint.rules.FindingLocation
import ai.nina.labs.sapcclint.rules.FindingSeverity
import ai.nina.labs.sapcclint.rules.RegisteredRule
import ai.nina.labs.sapcclint.rules.resolveRuleSettings
import java.nio.file.Path

class BusinessProcessDomainAnalyzer(
    private val structuralValidator: BusinessProcessStructuralValidator = BusinessProcessStructuralValidator(),
) : DomainAnalyzer {

    override val domain: AnalysisDomain = AnalysisDomain.BUSINESS_PROCESS

    private val rule = BusinessProcessRule("BPDomElementsInspection", FindingSeverity.ERROR)

    override fun analyze(context: RepositoryAnalysisContext): DomainAnalysisResult {
        val files = context.scan.filesFor(domain)
        if (files.isEmpty()) {
            return DomainAnalysisResult(domain, 0, emptyList(), emptyList())
        }

        val settings = settings(context.config)
        val findings = if (!settings.enabled) {
            emptyList()
        } else {
            files.flatMap { file ->
                structuralValidator.validate(file).mapIndexed { index, issue ->
                    finding(
                        file = file,
                        location = issue.location,
                        settings = settings,
                        message = issue.message,
                        entityKey = "bp-dom:$file:${issue.location.line}:${issue.location.column}:$index",
                    )
                }
            }.sortedWith(compareBy({ it.location.file.toString() }, { it.location.position.line }, { it.ruleId }))
        }

        return DomainAnalysisResult(
            domain = domain,
            analyzedFileCount = files.size,
            findings = findings,
            rules = listOf(rule),
        )
    }

    private fun settings(config: AnalyzerConfig): EffectiveRuleSettings {
        return resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
    }

    private fun finding(
        file: Path,
        location: SourcePosition,
        settings: EffectiveRuleSettings,
        message: String,
        entityKey: String,
    ): Finding {
        return Finding(
            ruleId = rule.ruleId,
            severity = settings.severityOverride ?: rule.defaultSeverity,
            message = message,
            location = FindingLocation(file, location),
            entityKey = entityKey,
            domain = domain,
        )
    }

    private data class BusinessProcessRule(
        override val ruleId: String,
        override val defaultSeverity: FindingSeverity,
        override val domain: AnalysisDomain = AnalysisDomain.BUSINESS_PROCESS,
    ) : RegisteredRule
}
