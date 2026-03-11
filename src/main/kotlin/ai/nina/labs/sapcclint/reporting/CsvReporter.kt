package ai.nina.labs.sapcclint.reporting

import ai.nina.labs.sapcclint.core.AnalysisResult
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

class CsvReporter {

    fun write(result: AnalysisResult, outputPath: Path) {
        outputPath.parent?.createDirectories()
        outputPath.outputStream().bufferedWriter().use { writer ->
            writer.write("severity,domain,rule_id,file,line,column,message,entity_key")
            writer.newLine()
            result.findings.forEach { finding ->
                writer.write(
                    listOf(
                        finding.severity.name,
                        finding.domain.cliValue,
                        finding.ruleId,
                        finding.relativePath(result.repo),
                        finding.location.position.line.toString(),
                        finding.location.position.column.toString(),
                        finding.message,
                        finding.entityKey.orEmpty(),
                    ).joinToString(",") { escapeCsv(it) }
                )
                writer.newLine()
            }
        }
    }

    private fun escapeCsv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
