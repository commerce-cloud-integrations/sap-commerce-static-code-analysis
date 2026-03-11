package com.cci.sapcclint.reporting

import com.cci.sapcclint.core.AnalysisResult
import com.cci.sapcclint.rules.Finding
import com.cci.sapcclint.rules.FindingSeverity
import com.cci.sapcclint.rules.RegisteredRule
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

class SarifReporter(
    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL),
) {

    fun write(result: AnalysisResult, outputPath: Path) {
        outputPath.parent?.createDirectories()
        outputPath.outputStream().use { output ->
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                output,
                SarifLog(
                    runs = listOf(
                        SarifRun(
                            tool = SarifTool(
                                driver = SarifDriver(
                                    name = "sapcc-lint",
                                    informationUri = "https://github.com/epam/sap-commerce-intellij-idea-plugin",
                                    rules = result.rules
                                        .sortedBy { it.ruleId }
                                        .map(::toRuleDescriptor),
                                )
                            ),
                            results = result.findings.map { finding -> toResult(result.repo, finding) },
                        )
                    )
                )
            )
        }
    }

    private fun toRuleDescriptor(rule: RegisteredRule): SarifRuleDescriptor {
        return SarifRuleDescriptor(
            id = rule.ruleId,
            name = rule.ruleId,
            shortDescription = SarifText("Static rule ${rule.ruleId}"),
            defaultConfiguration = SarifDefaultConfiguration(level = rule.defaultSeverity.toSarifLevel()),
            properties = SarifPropertyBag(tags = listOf(rule.domain.cliValue)),
        )
    }

    private fun toResult(repo: Path, finding: Finding): SarifResult {
        val location = finding.location
        val normalizedRepo = repo.toAbsolutePath().normalize()
        val normalizedFile = location.file.toAbsolutePath().normalize()
        val artifactUri = if (normalizedFile.startsWith(normalizedRepo)) {
            normalizedRepo.relativize(normalizedFile).toSarifUri()
        } else {
            normalizedFile.toSarifUri()
        }

        return SarifResult(
            ruleId = finding.ruleId,
            level = finding.severity.toSarifLevel(),
            message = SarifText(finding.message),
            locations = listOf(
                SarifLocation(
                    physicalLocation = SarifPhysicalLocation(
                        artifactLocation = SarifArtifactLocation(uri = artifactUri),
                        region = SarifRegion(
                            startLine = location.position.line,
                            startColumn = location.position.column,
                        ),
                    )
                )
            ),
            partialFingerprints = finding.entityKey?.let { mapOf("entityKey" to it) },
            properties = SarifPropertyBag(tags = listOf(finding.domain.cliValue)),
        )
    }
}

private fun FindingSeverity.toSarifLevel(): String = when (this) {
    FindingSeverity.ERROR -> "error"
    FindingSeverity.WARNING -> "warning"
}

private fun Path.toSarifUri(): String = toString().replace('\\', '/')

data class SarifLog(
    @JsonProperty("\$schema")
    val schema: String = "https://json.schemastore.org/sarif-2.1.0.json",
    val version: String = "2.1.0",
    val runs: List<SarifRun>,
)

data class SarifRun(
    val tool: SarifTool,
    val results: List<SarifResult>,
)

data class SarifTool(
    val driver: SarifDriver,
)

data class SarifDriver(
    val name: String,
    val informationUri: String,
    val rules: List<SarifRuleDescriptor>,
)

data class SarifRuleDescriptor(
    val id: String,
    val name: String,
    val shortDescription: SarifText,
    val defaultConfiguration: SarifDefaultConfiguration,
    val properties: SarifPropertyBag? = null,
)

data class SarifDefaultConfiguration(
    val level: String,
)

data class SarifResult(
    val ruleId: String,
    val level: String,
    val message: SarifText,
    val locations: List<SarifLocation>,
    val partialFingerprints: Map<String, String>? = null,
    val properties: SarifPropertyBag? = null,
)

data class SarifLocation(
    val physicalLocation: SarifPhysicalLocation,
)

data class SarifPhysicalLocation(
    val artifactLocation: SarifArtifactLocation,
    val region: SarifRegion,
)

data class SarifArtifactLocation(
    val uri: String,
)

data class SarifRegion(
    val startLine: Int,
    val startColumn: Int,
)

data class SarifText(
    val text: String,
)

data class SarifPropertyBag(
    val tags: List<String>? = null,
)
