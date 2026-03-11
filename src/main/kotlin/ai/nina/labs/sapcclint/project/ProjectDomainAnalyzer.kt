package ai.nina.labs.sapcclint.project

import ai.nina.labs.sapcclint.config.AnalyzerConfig
import ai.nina.labs.sapcclint.core.AnalysisDomain
import ai.nina.labs.sapcclint.core.DomainAnalysisResult
import ai.nina.labs.sapcclint.core.DomainAnalyzer
import ai.nina.labs.sapcclint.core.RepositoryAnalysisContext
import ai.nina.labs.sapcclint.itemsxml.model.SourcePosition
import ai.nina.labs.sapcclint.project.catalog.ExtensionRegistry
import ai.nina.labs.sapcclint.project.model.ParsedExtensionInfoFile
import ai.nina.labs.sapcclint.project.model.ParsedLocalExtensionsFile
import ai.nina.labs.sapcclint.rules.EffectiveRuleSettings
import ai.nina.labs.sapcclint.rules.Finding
import ai.nina.labs.sapcclint.rules.FindingLocation
import ai.nina.labs.sapcclint.rules.FindingSeverity
import ai.nina.labs.sapcclint.rules.RegisteredRule
import ai.nina.labs.sapcclint.rules.resolveRuleSettings
import java.nio.file.Path

class ProjectDomainAnalyzer(
    private val supportLoader: ProjectSupportLoader = ProjectSupportLoader(),
) : DomainAnalyzer {

    override val domain: AnalysisDomain = AnalysisDomain.PROJECT

    private val rules = listOf(
        SimpleDomainRule("EiUnknownExtensionDefinition", FindingSeverity.ERROR, domain),
        SimpleDomainRule("EiDuplicateExtensionDefinition", FindingSeverity.ERROR, domain),
        SimpleDomainRule("LeUnknownExtensionDefinition", FindingSeverity.ERROR, domain),
    )

    override fun analyze(context: RepositoryAnalysisContext): DomainAnalysisResult {
        val files = context.scan.filesFor(domain)
        if (files.isEmpty()) {
            return DomainAnalysisResult(domain, 0, emptyList(), emptyList())
        }

        val projectSupport = supportLoader.load(context.repo, context.config, files)
        val extensionInfoFiles = projectSupport.extensionInfoFiles
        val localExtensionsFiles = projectSupport.localExtensionsFiles
        val registry = projectSupport.registry

        val findings = buildList {
            addAll(findDuplicateRequiredExtensions(extensionInfoFiles, context.config))
            if (registry.hasFullPlatformContext()) {
                addAll(findUnknownRequiredExtensions(extensionInfoFiles, registry, context.config))
                addAll(findUnknownLocalExtensions(localExtensionsFiles, registry, context.config))
            }
        }.sortedWith(compareBy({ it.location.file.toString() }, { it.location.position.line }, { it.ruleId }))

        return DomainAnalysisResult(
            domain = domain,
            analyzedFileCount = files.size,
            findings = findings,
            rules = rules,
        )
    }

    private fun findDuplicateRequiredExtensions(
        extensionInfoFiles: List<ParsedExtensionInfoFile>,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rules.first { it.ruleId == "EiDuplicateExtensionDefinition" }
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return extensionInfoFiles.flatMap { file ->
            val grouped = file.requiredExtensions
                .filter { it.name.value != null }
                .groupBy { it.name.value!!.lowercase() }
            file.requiredExtensions
                .filter { required ->
                    val name = required.name.value?.lowercase() ?: return@filter false
                    grouped[name].orEmpty().size > 1
                }
                .map { required ->
                    finding(
                        file = file.path,
                        location = required.name.location ?: required.location,
                        rule = rule,
                        settings = settings,
                        message = "Required extension '${required.name.value}' is declared more than once in the same extensioninfo.xml.",
                        entityKey = "extensioninfo:${file.path}:${required.name.value?.lowercase()}",
                    )
                }
        }
    }

    private fun findUnknownRequiredExtensions(
        extensionInfoFiles: List<ParsedExtensionInfoFile>,
        registry: ExtensionRegistry,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rules.first { it.ruleId == "EiUnknownExtensionDefinition" }
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return extensionInfoFiles.flatMap { file ->
            file.requiredExtensions
                .filter { required -> required.name.value != null }
                .filter { required -> registry.findDeclaredExtensions(required.name.value).isEmpty() }
                .map { required ->
                    finding(
                        file = file.path,
                        location = required.name.location ?: required.location,
                        rule = rule,
                        settings = settings,
                        message = "Required extension '${required.name.value}' is not declared in any discovered extensioninfo.xml.",
                        entityKey = "requires-extension:${file.path}:${required.name.value?.lowercase()}",
                    )
                }
        }
    }

    private fun findUnknownLocalExtensions(
        localExtensionsFiles: List<ParsedLocalExtensionsFile>,
        registry: ExtensionRegistry,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rules.first { it.ruleId == "LeUnknownExtensionDefinition" }
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return localExtensionsFiles.flatMap { file ->
            file.extensions
                .filter { extension -> extension.name.value != null }
                .filter { extension -> registry.findDeclaredExtensions(extension.name.value).isEmpty() }
                .map { extension ->
                    finding(
                        file = file.path,
                        location = extension.name.location ?: extension.location,
                        rule = rule,
                        settings = settings,
                        message = "Local extension '${extension.name.value}' is not declared in any discovered extensioninfo.xml.",
                        entityKey = "local-extension:${file.path}:${extension.name.value?.lowercase()}",
                    )
                }
        }
    }

    private fun finding(
        file: Path,
        location: SourcePosition,
        rule: SimpleDomainRule,
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
}

private data class SimpleDomainRule(
    override val ruleId: String,
    override val defaultSeverity: FindingSeverity,
    override val domain: AnalysisDomain,
) : RegisteredRule
