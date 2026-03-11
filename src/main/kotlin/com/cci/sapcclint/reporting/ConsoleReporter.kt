package com.cci.sapcclint.reporting

import com.cci.sapcclint.core.AnalysisResult
import java.io.PrintStream
import java.nio.file.Path

class ConsoleReporter {

    fun write(result: AnalysisResult, output: PrintStream) {
        val scannedDomains = result.domainResults.filter { it.analyzedFileCount > 0 }
        output.println("Scanned ${result.analyzedFileCount} file(s) across ${scannedDomains.size} domain(s) under ${result.repo.toAbsolutePath().normalize()}")
        if (scannedDomains.isNotEmpty()) {
            output.println("Domains:")
            scannedDomains
                .sortedBy { it.domain.cliValue }
                .forEach { domainResult ->
                    output.println("  - ${domainResult.domain.cliValue}: ${domainResult.analyzedFileCount} file(s)")
                }
        }
        output.println("Findings: ${result.errorCount} error(s), ${result.warningCount} warning(s)")

        if (result.findings.isEmpty()) {
            output.println("No findings.")
            return
        }

        result.findings
            .groupBy { relativize(result.repo, it.location.file) }
            .forEach { (file, findings) ->
                output.println()
                output.println(file)
                findings.forEach { finding ->
                    output.println(
                        "  [${finding.severity.name}] [${finding.domain.cliValue}] ${finding.ruleId}:${finding.location.position.line}:${finding.location.position.column} ${finding.message}"
                    )
                }
            }
    }

    private fun relativize(repo: Path, file: Path): String {
        val normalizedRepo = repo.toAbsolutePath().normalize()
        val normalizedFile = file.toAbsolutePath().normalize()
        return if (normalizedFile.startsWith(normalizedRepo)) {
            normalizedRepo.relativize(normalizedFile).toString()
        } else {
            normalizedFile.toString()
        }
    }
}
