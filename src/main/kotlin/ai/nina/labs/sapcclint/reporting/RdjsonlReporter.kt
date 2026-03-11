package ai.nina.labs.sapcclint.reporting

import ai.nina.labs.sapcclint.core.AnalysisResult
import ai.nina.labs.sapcclint.rules.Finding
import ai.nina.labs.sapcclint.rules.FindingSeverity
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

class RdjsonlReporter(
    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL),
) {

    fun write(result: AnalysisResult, outputPath: Path) {
        outputPath.parent?.createDirectories()
        outputPath.outputStream().bufferedWriter().use { writer ->
            result.findings.forEach { finding ->
                writer.write(objectMapper.writeValueAsString(toDiagnostic(result.repo, finding)))
                writer.newLine()
            }
        }
    }

    private fun toDiagnostic(repo: Path, finding: Finding): RdjsonlDiagnostic {
        val path = finding.relativePath(repo)
        val line = finding.location.position.line
        val column = finding.location.position.column

        return RdjsonlDiagnostic(
            message = finding.message,
            severity = finding.severity.toRdjsonSeverity(),
            code = RdjsonlCode(value = finding.ruleId),
            source = RdjsonlSource(name = "sapcc-lint"),
            location = RdjsonlLocation(
                path = path,
                range = RdjsonlRange(
                    start = RdjsonlPosition(line = line, column = column),
                    end = RdjsonlPosition(line = line, column = column),
                ),
            ),
            originalOutput = "[${finding.severity.name}] [${finding.domain.cliValue}] ${finding.ruleId}:$line:$column ${finding.message}",
        )
    }
}

private fun FindingSeverity.toRdjsonSeverity(): String = when (this) {
    FindingSeverity.ERROR -> "ERROR"
    FindingSeverity.WARNING -> "WARNING"
}

data class RdjsonlDiagnostic(
    val message: String,
    val severity: String,
    val code: RdjsonlCode,
    val source: RdjsonlSource,
    val location: RdjsonlLocation,
    val originalOutput: String,
)

data class RdjsonlCode(
    val value: String,
)

data class RdjsonlSource(
    val name: String,
)

data class RdjsonlLocation(
    val path: String,
    val range: RdjsonlRange,
)

data class RdjsonlRange(
    val start: RdjsonlPosition,
    val end: RdjsonlPosition,
)

data class RdjsonlPosition(
    val line: Int,
    val column: Int,
)
