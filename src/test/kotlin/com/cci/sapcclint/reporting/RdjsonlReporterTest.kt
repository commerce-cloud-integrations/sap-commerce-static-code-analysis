package com.cci.sapcclint.reporting

import com.cci.sapcclint.config.AnalyzerConfig
import com.cci.sapcclint.core.AnalysisDomain
import com.cci.sapcclint.core.AnalysisResult
import com.cci.sapcclint.core.DomainAnalysisResult
import com.cci.sapcclint.itemsxml.model.SourcePosition
import com.cci.sapcclint.rules.Finding
import com.cci.sapcclint.rules.FindingLocation
import com.cci.sapcclint.rules.FindingSeverity
import com.cci.sapcclint.rules.RegisteredRule
import com.cci.sapcclint.scanner.RepositoryScan
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RdjsonlReporterTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun write_whenFindingIsInsideRepository_writesRelativeRdjsonlDiagnostic() {
        val repo = Files.createTempDirectory("sapcc-lint-rdjsonl-reporter")
        val output = repo.resolve("build/reports/findings.rdjsonl")
        val finding = Finding(
            ruleId = "SampleRule",
            severity = FindingSeverity.WARNING,
            message = "Sample message",
            location = FindingLocation(
                file = repo.resolve("custom/core/resources/custom-items.xml"),
                position = SourcePosition(line = 12, column = 4),
            ),
            entityKey = "sample",
            domain = AnalysisDomain.TYPE_SYSTEM,
        )
        val result = AnalysisResult(
            repo = repo,
            config = AnalyzerConfig(),
            scan = RepositoryScan(repo = repo, filesByDomain = emptyMap()),
            domainResults = listOf(
                DomainAnalysisResult(
                    domain = AnalysisDomain.TYPE_SYSTEM,
                    analyzedFileCount = 1,
                    findings = listOf(finding),
                    rules = listOf(sampleRule("SampleRule", FindingSeverity.WARNING, AnalysisDomain.TYPE_SYSTEM)),
                )
            ),
            findings = listOf(finding),
            rules = listOf(sampleRule("SampleRule", FindingSeverity.WARNING, AnalysisDomain.TYPE_SYSTEM)),
        )

        RdjsonlReporter().write(result, output)

        val lines = Files.readAllLines(output).filter { it.isNotBlank() }
        assertEquals(1, lines.size)

        val diagnostic = objectMapper.readTree(lines.single())
        assertEquals("Sample message", diagnostic["message"].asText())
        assertEquals("WARNING", diagnostic["severity"].asText())
        assertEquals("SampleRule", diagnostic["code"]["value"].asText())
        assertEquals("sapcc-lint", diagnostic["source"]["name"].asText())
        assertEquals("custom/core/resources/custom-items.xml", diagnostic["location"]["path"].asText())
        assertEquals(12, diagnostic["location"]["range"]["start"]["line"].asInt())
        assertEquals(4, diagnostic["location"]["range"]["start"]["column"].asInt())
        assertTrue(diagnostic["originalOutput"].asText().contains("SampleRule:12:4"))
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
