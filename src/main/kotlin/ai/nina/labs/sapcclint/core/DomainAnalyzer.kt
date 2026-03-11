package ai.nina.labs.sapcclint.core

import ai.nina.labs.sapcclint.config.AnalyzerConfig
import ai.nina.labs.sapcclint.rules.DefaultTypeSystemRules
import ai.nina.labs.sapcclint.rules.Finding
import ai.nina.labs.sapcclint.rules.RegisteredRule
import ai.nina.labs.sapcclint.rules.RuleEngine
import ai.nina.labs.sapcclint.rules.TypeSystemRule
import ai.nina.labs.sapcclint.scanner.RepositoryScan
import java.nio.file.Path

data class RepositoryAnalysisContext(
    val repo: Path,
    val config: AnalyzerConfig,
    val scan: RepositoryScan,
)

data class DomainAnalysisResult(
    val domain: AnalysisDomain,
    val analyzedFileCount: Int,
    val findings: List<Finding>,
    val rules: List<RegisteredRule>,
)

interface DomainAnalyzer {
    val domain: AnalysisDomain

    fun analyze(context: RepositoryAnalysisContext): DomainAnalysisResult
}

class TypeSystemDomainAnalyzer(
    private val supportLoader: TypeSystemSupportLoader = TypeSystemSupportLoader(),
    private val rules: List<TypeSystemRule> = DefaultTypeSystemRules.all(),
) : DomainAnalyzer {

    override val domain: AnalysisDomain = AnalysisDomain.TYPE_SYSTEM

    override fun analyze(context: RepositoryAnalysisContext): DomainAnalysisResult {
        val files = context.scan.filesFor(domain)
        if (files.isEmpty()) {
            return DomainAnalysisResult(
                domain = domain,
                analyzedFileCount = 0,
                findings = emptyList(),
                rules = emptyList(),
            )
        }

        val typeSystemSupport = supportLoader.load(context.repo, context.config, files)
        val findings = RuleEngine(rules).evaluate(typeSystemSupport.catalog, context.config)

        return DomainAnalysisResult(
            domain = domain,
            analyzedFileCount = files.size,
            findings = findings,
            rules = rules,
        )
    }
}

class NoOpDomainAnalyzer(
    override val domain: AnalysisDomain,
) : DomainAnalyzer {
    override fun analyze(context: RepositoryAnalysisContext): DomainAnalysisResult {
        return DomainAnalysisResult(
            domain = domain,
            analyzedFileCount = context.scan.filesFor(domain).size,
            findings = emptyList(),
            rules = emptyList(),
        )
    }
}
