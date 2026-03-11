package com.cci.sapcclint.businessprocess

import com.cci.sapcclint.businessprocess.validation.BusinessProcessStructuralValidator
import com.cci.sapcclint.config.AnalyzerConfig
import com.cci.sapcclint.core.AnalysisDomain
import com.cci.sapcclint.core.DomainAnalysisResult
import com.cci.sapcclint.core.DomainAnalyzer
import com.cci.sapcclint.core.RepositoryAnalysisContext
import com.cci.sapcclint.itemsxml.model.SourcePosition
import com.cci.sapcclint.rules.EffectiveRuleSettings
import com.cci.sapcclint.rules.Finding
import com.cci.sapcclint.rules.FindingLocation
import com.cci.sapcclint.rules.FindingSeverity
import com.cci.sapcclint.rules.RegisteredRule
import com.cci.sapcclint.rules.resolveRuleSettings
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
