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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CsvReporterTest {

    @Test
    fun write_whenFindingContainsCommaAndQuotes_escapesCsvFields() {
        val repo = Files.createTempDirectory("sapcc-lint-csv-reporter")
        val output = repo.resolve("build/reports/findings.csv")
        val finding = Finding(
            ruleId = "SampleRule",
            severity = FindingSeverity.ERROR,
            message = "Value \"quoted\", with comma",
            location = FindingLocation(
                file = repo.resolve("custom/core/resources/custom-items.xml"),
                position = SourcePosition(line = 12, column = 4),
            ),
            entityKey = "sample,key",
            domain = AnalysisDomain.TYPE_SYSTEM,
        )
        val result = analysisResult(repo, finding)

        CsvReporter().write(result, output)

        val lines = Files.readAllLines(output)
        assertEquals("severity,domain,rule_id,file,line,column,message,entity_key", lines.first())
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("\"Value \"\"quoted\"\", with comma\""))
        assertTrue(lines[1].contains("\"sample,key\""))
        assertTrue(lines[1].contains("\"custom/core/resources/custom-items.xml\""))
    }

    @Test
    fun write_whenNoFindingsExist_writesHeaderOnly() {
        val repo = Files.createTempDirectory("sapcc-lint-csv-reporter-empty")
        val output = repo.resolve("build/reports/findings.csv")
        val result = AnalysisResult(
            repo = repo,
            config = AnalyzerConfig(),
            scan = RepositoryScan(repo = repo, filesByDomain = emptyMap()),
            domainResults = emptyList(),
            findings = emptyList(),
            rules = emptyList(),
        )

        CsvReporter().write(result, output)

        val lines = Files.readAllLines(output)
        assertEquals(listOf("severity,domain,rule_id,file,line,column,message,entity_key"), lines)
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
