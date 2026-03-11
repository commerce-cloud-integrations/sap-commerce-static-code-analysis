package com.cci.sapcclint.manifest

import com.cci.sapcclint.config.AnalyzerConfig
import com.cci.sapcclint.core.AnalysisDomain
import com.cci.sapcclint.core.DomainAnalysisResult
import com.cci.sapcclint.core.DomainAnalyzer
import com.cci.sapcclint.core.RepositoryAnalysisContext
import com.cci.sapcclint.manifest.model.ManifestStringRef
import com.cci.sapcclint.manifest.model.ParsedManifestFile
import com.cci.sapcclint.manifest.parser.ManifestParser
import com.cci.sapcclint.project.ProjectSupportLoader
import com.cci.sapcclint.project.catalog.ExtensionRegistry
import com.cci.sapcclint.rules.EffectiveRuleSettings
import com.cci.sapcclint.rules.Finding
import com.cci.sapcclint.rules.FindingLocation
import com.cci.sapcclint.rules.FindingSeverity
import com.cci.sapcclint.rules.RegisteredRule
import com.cci.sapcclint.rules.resolveRuleSettings
import java.nio.file.Path

class ManifestDomainAnalyzer(
    private val parser: ManifestParser = ManifestParser(),
    private val projectSupportLoader: ProjectSupportLoader = ProjectSupportLoader(),
) : DomainAnalyzer {

    override val domain: AnalysisDomain = AnalysisDomain.MANIFEST

    private val rules = listOf(
        SimpleDomainRule("ManifestUnknownExtensionInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ManifestUnknownTemplateExtensionInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ManifestUnknownExtensionPackInspection", FindingSeverity.ERROR, domain),
    )

    override fun analyze(context: RepositoryAnalysisContext): DomainAnalysisResult {
        val files = context.scan.filesFor(domain)
        if (files.isEmpty()) {
            return DomainAnalysisResult(domain, 0, emptyList(), emptyList())
        }

        val parsedFiles = files.map(parser::parse)
        val projectSupport = projectSupportLoader.load(context.repo, context.config)
        val findings = buildList {
            addAll(findUnknownExtensions(parsedFiles, projectSupport.registry, context.config))
            addAll(findUnknownTemplateExtensions(parsedFiles, projectSupport.registry, context.config))
            addAll(findUnknownExtensionPacks(parsedFiles, context.config))
        }.sortedWith(compareBy({ it.location.file.toString() }, { it.location.position.line }, { it.ruleId }))

        return DomainAnalysisResult(
            domain = domain,
            analyzedFileCount = files.size,
            findings = findings,
            rules = rules,
        )
    }

    private fun findUnknownExtensions(
        parsedFiles: List<ParsedManifestFile>,
        registry: ExtensionRegistry,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rules.first { it.ruleId == "ManifestUnknownExtensionInspection" }
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled || !registry.hasFullPlatformContext()) {
            return emptyList()
        }

        return parsedFiles.flatMap { file ->
            file.extensionReferences
                .filter { registry.findDeclaredExtensions(it.value).isEmpty() }
                .map { reference ->
                    finding(
                        file = file.path,
                        reference = reference,
                        rule = rule,
                        settings = settings,
                        message = "Unknown '${reference.value}' extension in manifest.json.",
                        entityKey = "manifest-extension:${file.path}:${reference.value.lowercase()}",
                    )
                }
        }
    }

    private fun findUnknownTemplateExtensions(
        parsedFiles: List<ParsedManifestFile>,
        registry: ExtensionRegistry,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rules.first { it.ruleId == "ManifestUnknownTemplateExtensionInspection" }
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled || !registry.hasFullPlatformContext()) {
            return emptyList()
        }

        return parsedFiles.flatMap { file ->
            file.templateReferences
                .filter { registry.findDeclaredExtensions(it.value).isEmpty() }
                .map { reference ->
                    finding(
                        file = file.path,
                        reference = reference,
                        rule = rule,
                        settings = settings,
                        message = "Unknown '${reference.value}' template extension in manifest.json.",
                        entityKey = "manifest-template-extension:${file.path}:${reference.value.lowercase()}",
                    )
                }
        }
    }

    private fun findUnknownExtensionPacks(
        parsedFiles: List<ParsedManifestFile>,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rules.first { it.ruleId == "ManifestUnknownExtensionPackInspection" }
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return parsedFiles.flatMap { file ->
            file.extensionPackReferences
                .filter { it.value !in supportedExtensionPacks }
                .map { reference ->
                    finding(
                        file = file.path,
                        reference = reference,
                        rule = rule,
                        settings = settings,
                        message = "Unknown '${reference.value}' extension pack in manifest.json.",
                        entityKey = "manifest-extension-pack:${file.path}:${reference.value.lowercase()}",
                    )
                }
        }
    }

    private fun finding(
        file: Path,
        reference: ManifestStringRef,
        rule: SimpleDomainRule,
        settings: EffectiveRuleSettings,
        message: String,
        entityKey: String,
    ): Finding {
        return Finding(
            ruleId = rule.ruleId,
            severity = settings.severityOverride ?: rule.defaultSeverity,
            message = message,
            location = FindingLocation(file, reference.location),
            entityKey = entityKey,
            domain = domain,
        )
    }

    companion object {
        private val supportedExtensionPacks = setOf(
            "hybris-commerce-integrations",
            "hybris-datahub-integration-suite",
            "cx-commerce-crm-integrations",
            "media-telco",
        )
    }
}

private data class SimpleDomainRule(
    override val ruleId: String,
    override val defaultSeverity: FindingSeverity,
    override val domain: AnalysisDomain,
) : RegisteredRule
