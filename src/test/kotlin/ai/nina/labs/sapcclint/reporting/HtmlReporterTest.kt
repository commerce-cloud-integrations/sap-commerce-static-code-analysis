package ai.nina.labs.sapcclint.reporting

import ai.nina.labs.sapcclint.config.AnalyzerConfig
import ai.nina.labs.sapcclint.core.AnalysisDomain
import ai.nina.labs.sapcclint.core.AnalysisResult
import ai.nina.labs.sapcclint.core.DomainAnalysisResult
import ai.nina.labs.sapcclint.itemsxml.model.SourcePosition
import ai.nina.labs.sapcclint.rules.Finding
import ai.nina.labs.sapcclint.rules.FindingLocation
import ai.nina.labs.sapcclint.rules.FindingSeverity
import ai.nina.labs.sapcclint.rules.RegisteredRule
import ai.nina.labs.sapcclint.scanner.RepositoryScan
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class HtmlReporterTest {

    @Test
    fun write_whenFindingsExist_writesSelfContainedSummaryAndEscapedTableRows() {
        val repo = Files.createTempDirectory("sapcc-lint-html-reporter")
        val output = repo.resolve("build/reports/findings.html")
        val finding = Finding(
            ruleId = "SampleRule",
            severity = FindingSeverity.WARNING,
            message = "Value <must> be \"escaped\"",
            location = FindingLocation(
                file = repo.resolve("custom/core/resources/custom-items.xml"),
                position = SourcePosition(line = 8, column = 2),
            ),
            entityKey = "entity<1>",
            domain = AnalysisDomain.TYPE_SYSTEM,
        )
        val result = analysisResult(repo, finding)

        HtmlReporter().write(result, output)

        val html = Files.readString(output)
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("<h1>sapcc-lint report</h1>"))
        assertTrue(html.contains("Findings by severity"))
        assertTrue(html.contains("Findings by rule"))
        assertTrue(html.contains("custom/core/resources/custom-items.xml"))
        assertTrue(html.contains("Value &lt;must&gt; be &quot;escaped&quot;"))
        assertTrue(html.contains("entity&lt;1&gt;"))
        assertTrue(html.contains("filterInput"))
    }

    @Test
    fun write_whenNoFindingsExist_writesNoFindingsRow() {
        val repo = Files.createTempDirectory("sapcc-lint-html-reporter-empty")
        val output = repo.resolve("build/reports/findings.html")
        val result = AnalysisResult(
            repo = repo,
            config = AnalyzerConfig(),
            scan = RepositoryScan(repo = repo, filesByDomain = emptyMap()),
            domainResults = emptyList(),
            findings = emptyList(),
            rules = emptyList(),
        )

        HtmlReporter().write(result, output)

        val html = Files.readString(output)
        assertTrue(html.contains("No findings."))
        assertTrue(html.contains("<td colspan=\"8\">No findings.</td>"))
    }

    private fun analysisResult(repo: java.nio.file.Path, finding: Finding): AnalysisResult {
        val rule = sampleRule(finding.ruleId, finding.severity, finding.domain)
        return AnalysisResult(
            repo = repo,
            config = AnalyzerConfig(),
            scan = RepositoryScan(repo = repo, filesByDomain = emptyMap()),
            domainResults = listOf(
                DomainAnalysisResult(
                    domain = finding.domain,
                    analyzedFileCount = 1,
                    findings = listOf(finding),
                    rules = listOf(rule),
                )
            ),
            findings = listOf(finding),
            rules = listOf(rule),
        )
    }

    private fun sampleRule(
        ruleIdValue: String,
        severityValue: FindingSeverity,
        domainValue: AnalysisDomain,
    ): RegisteredRule {
        return object : RegisteredRule {
            override val ruleId: String = ruleIdValue
            override val defaultSeverity: FindingSeverity = severityValue
            override val domain: AnalysisDomain = domainValue
        }
    }
}
