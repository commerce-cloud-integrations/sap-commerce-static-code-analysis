package ai.nina.labs.sapcclint.core

import ai.nina.labs.sapcclint.config.AnalyzerConfig
import ai.nina.labs.sapcclint.beansystem.BeanSystemDomainAnalyzer
import ai.nina.labs.sapcclint.businessprocess.BusinessProcessDomainAnalyzer
import ai.nina.labs.sapcclint.cockpitng.CockpitNgDomainAnalyzer
import ai.nina.labs.sapcclint.impex.ImpexDomainAnalyzer
import ai.nina.labs.sapcclint.manifest.ManifestDomainAnalyzer
import ai.nina.labs.sapcclint.project.ProjectDomainAnalyzer
import ai.nina.labs.sapcclint.rules.Finding
import ai.nina.labs.sapcclint.rules.FindingSeverity
import ai.nina.labs.sapcclint.rules.RegisteredRule
import ai.nina.labs.sapcclint.scanner.RepositoryScan
import java.nio.file.Path

/**
 * Executes the repository analysis pipeline from parsed files to findings.
 */
class RepositoryAnalyzer(
    private val analyzers: List<DomainAnalyzer> = listOf(
        TypeSystemDomainAnalyzer(),
        ProjectDomainAnalyzer(),
        ManifestDomainAnalyzer(),
        ImpexDomainAnalyzer(),
        BeanSystemDomainAnalyzer(),
        CockpitNgDomainAnalyzer(),
        BusinessProcessDomainAnalyzer(),
    ),
) {

    fun analyze(repo: Path, config: AnalyzerConfig, scan: RepositoryScan): AnalysisResult {
        val context = RepositoryAnalysisContext(
            repo = repo,
            config = config,
            scan = scan,
        )
        val domainResults = analyzers.map { it.analyze(context) }
        val findings = domainResults
            .flatMap { it.findings }
            .sortedWith(compareBy({ it.location.file.toString() }, { it.location.position.line }, { it.ruleId }))
        val rules = domainResults.flatMap { it.rules }.distinctBy { it.ruleId }

        return AnalysisResult(
            repo = repo,
            config = config,
            scan = scan,
            domainResults = domainResults,
            findings = findings,
            rules = rules,
        )
    }
}

data class AnalysisResult(
    val repo: Path,
    val config: AnalyzerConfig,
    val scan: RepositoryScan,
    val domainResults: List<DomainAnalysisResult>,
    val findings: List<Finding>,
    val rules: List<RegisteredRule>,
) {
    val errorCount: Int = findings.count { it.severity == FindingSeverity.ERROR }
    val warningCount: Int = findings.count { it.severity == FindingSeverity.WARNING }
    val analyzedFileCount: Int = domainResults.sumOf { it.analyzedFileCount }

    fun hasErrors(): Boolean = errorCount > 0
}
