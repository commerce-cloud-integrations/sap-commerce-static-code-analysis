package ai.nina.labs.sapcclint.cockpitng

import ai.nina.labs.sapcclint.catalog.TypeSystemCatalog
import ai.nina.labs.sapcclint.catalog.findEnumTypes
import ai.nina.labs.sapcclint.catalog.findItemTypes
import ai.nina.labs.sapcclint.catalog.findTypeHierarchy
import ai.nina.labs.sapcclint.cockpitng.catalog.CockpitNgCatalog
import ai.nina.labs.sapcclint.cockpitng.catalog.CockpitNgCatalogBuilder
import ai.nina.labs.sapcclint.cockpitng.model.CockpitContextDecl
import ai.nina.labs.sapcclint.cockpitng.parser.CockpitNgParser
import ai.nina.labs.sapcclint.cockpitng.validation.CockpitNgStructuralValidator
import ai.nina.labs.sapcclint.config.AnalyzerConfig
import ai.nina.labs.sapcclint.core.AnalysisDomain
import ai.nina.labs.sapcclint.core.DomainAnalysisResult
import ai.nina.labs.sapcclint.core.DomainAnalyzer
import ai.nina.labs.sapcclint.core.RepositoryAnalysisContext
import ai.nina.labs.sapcclint.core.TypeSystemSupportLoader
import ai.nina.labs.sapcclint.itemsxml.model.SourcePosition
import ai.nina.labs.sapcclint.rules.EffectiveRuleSettings
import ai.nina.labs.sapcclint.rules.Finding
import ai.nina.labs.sapcclint.rules.FindingLocation
import ai.nina.labs.sapcclint.rules.FindingSeverity
import ai.nina.labs.sapcclint.rules.RegisteredRule
import ai.nina.labs.sapcclint.rules.resolveRuleSettings
import java.nio.file.Path

class CockpitNgDomainAnalyzer(
    private val parser: CockpitNgParser = CockpitNgParser(),
    private val catalogBuilder: CockpitNgCatalogBuilder = CockpitNgCatalogBuilder(),
    private val typeSystemSupportLoader: TypeSystemSupportLoader = TypeSystemSupportLoader(),
    private val structuralValidator: CockpitNgStructuralValidator = CockpitNgStructuralValidator(),
) : DomainAnalyzer {

    override val domain: AnalysisDomain = AnalysisDomain.COCKPIT_NG

    private val rules = listOf(
        CockpitNgRule("CngConfigDomElementsInspection", FindingSeverity.ERROR),
        CockpitNgRule("CngActionsDomElementsInspection", FindingSeverity.ERROR),
        CockpitNgRule("CngWidgetsDomElementsInspection", FindingSeverity.ERROR),
        CockpitNgRule("CngContextMergeByPointToExistingContextAttribute", FindingSeverity.ERROR),
        CockpitNgRule("CngContextParentIsNotValid", FindingSeverity.ERROR),
        CockpitNgRule("CngContextMergeByTypeParentIsNotValid", FindingSeverity.ERROR),
        CockpitNgRule("CngNamespaceNotOptimized", FindingSeverity.WARNING),
        CockpitNgRule("CngDuplicateNamespace", FindingSeverity.WARNING),
    )

    override fun analyze(context: RepositoryAnalysisContext): DomainAnalysisResult {
        val files = context.scan.filesFor(domain)
        if (files.isEmpty()) {
            return DomainAnalysisResult(domain, 0, emptyList(), emptyList())
        }

        val parsedFiles = files.map(parser::parse)
        val catalog = catalogBuilder.build(parsedFiles)
        val typeSystemCatalog = typeSystemSupportLoader.load(context.repo, context.config).catalog
        val findings = buildList {
            addAll(findStructuralIssues(files, context.config))
            addAll(findInvalidMergeBy(catalog, context.config))
            addAll(findInvalidParentForMergeBy(catalog, context.config))
            addAll(findInvalidTypeParent(catalog, context.config, typeSystemCatalog))
            addAll(findNamespaceNotOptimized(catalog, context.config))
            addAll(findDuplicateNamespaces(catalog, context.config))
        }.sortedWith(compareBy({ it.location.file.toString() }, { it.location.position.line }, { it.ruleId }))

        return DomainAnalysisResult(
            domain = domain,
            analyzedFileCount = files.size,
            findings = findings,
            rules = rules,
        )
    }

    private fun findStructuralIssues(files: List<Path>, config: AnalyzerConfig): List<Finding> {
        return files.flatMap { file ->
            structuralValidator.validate(file).mapIndexedNotNull { index, issue ->
                val rule = rules.firstOrNull { it.ruleId == issue.ruleId } ?: return@mapIndexedNotNull null
                val settings = settings(config, rule)
                if (!settings.enabled) {
                    return@mapIndexedNotNull null
                }
                finding(
                    file = file,
                    location = issue.location,
                    rule = rule,
                    settings = settings,
                    message = issue.message,
                    entityKey = "cng-dom:${issue.ruleId}:$file:${issue.location.line}:${issue.location.column}:$index",
                )
            }
        }
    }

    private fun findInvalidMergeBy(catalog: CockpitNgCatalog, config: AnalyzerConfig): List<Finding> {
        val rule = rule("CngContextMergeByPointToExistingContextAttribute")
        val settings = settings(config, rule)
        if (!settings.enabled) {
            return emptyList()
        }

        val validMergeByValues = catalog.contextAttributes.keys - mergeByAttribute
        return configContexts(catalog).mapNotNull { (file, declaration) ->
            val mergeBy = declaration.attributes[mergeByAttribute]?.value ?: return@mapNotNull null
            if (mergeBy in validMergeByValues) {
                return@mapNotNull null
            }
            finding(
                file = file,
                location = declaration.attributes[mergeByAttribute]?.location ?: declaration.location,
                rule = rule,
                settings = settings,
                message = "Context merge-by value '$mergeBy' does not match any known context attribute.",
                entityKey = "cng-merge-by:$file:${declaration.location.line}:$mergeBy",
            )
        }
    }

    private fun findInvalidParentForMergeBy(catalog: CockpitNgCatalog, config: AnalyzerConfig): List<Finding> {
        val rule = rule("CngContextParentIsNotValid")
        val settings = settings(config, rule)
        if (!settings.enabled) {
            return emptyList()
        }

        return configContexts(catalog).mapNotNull { (file, declaration) ->
            val mergeBy = declaration.attributes[mergeByAttribute]?.value ?: return@mapNotNull null
            if (mergeBy == typeAttribute) {
                return@mapNotNull null
            }

            val parent = declaration.attributes[parentAttribute]?.value ?: return@mapNotNull null
            if (parent == parentAutoValue) {
                return@mapNotNull null
            }

            val allowedValues = catalog.contextAttributes[mergeBy]
            if (allowedValues != null && parent in allowedValues) {
                return@mapNotNull null
            }

            finding(
                file = file,
                location = declaration.attributes[parentAttribute]?.location ?: declaration.location,
                rule = rule,
                settings = settings,
                message = "Context parent '$parent' is not valid for merge-by='$mergeBy'.",
                entityKey = "cng-parent:$file:${declaration.location.line}:$mergeBy:$parent",
            )
        }
    }

    private fun findInvalidTypeParent(
        catalog: CockpitNgCatalog,
        config: AnalyzerConfig,
        typeSystemCatalog: TypeSystemCatalog,
    ): List<Finding> {
        val rule = rule("CngContextMergeByTypeParentIsNotValid")
        val settings = settings(config, rule)
        if (!settings.enabled) {
            return emptyList()
        }

        return configContexts(catalog).mapNotNull { (file, declaration) ->
            val mergeBy = declaration.attributes[mergeByAttribute]?.value ?: return@mapNotNull null
            if (mergeBy != typeAttribute) {
                return@mapNotNull null
            }

            val parent = declaration.attributes[parentAttribute]?.value ?: return@mapNotNull null
            val type = declaration.attributes[typeAttribute]?.value ?: return@mapNotNull null

            val enumTypeExists = typeSystemCatalog.findEnumTypes(type).isNotEmpty()
            if (enumTypeExists && parent == enumerationValueType) {
                return@mapNotNull null
            }
            if (enumTypeExists) {
                return@mapNotNull null
            }

            val itemTypes = typeSystemCatalog.findItemTypes(type)
            if (itemTypes.isEmpty()) {
                return@mapNotNull null
            }
            if (!itemTypes.all { hasKnownFullAncestry(typeSystemCatalog, it.declaration.code.value, linkedSetOf()) }) {
                return@mapNotNull null
            }

            val hasValidParent = itemTypes.any { itemType ->
                typeSystemCatalog.findTypeHierarchy(
                    typeName = itemType.declaration.code.value,
                    includeSelf = false,
                    includeAncestors = true,
                ).any { parent.equals(it.declaration.code.value, ignoreCase = true) }
            }
            if (hasValidParent) {
                return@mapNotNull null
            }

            finding(
                file = file,
                location = declaration.attributes[parentAttribute]?.location ?: declaration.location,
                rule = rule,
                settings = settings,
                message = "Context parent '$parent' is not a valid ancestor of type '$type'.",
                entityKey = "cng-type-parent:$file:${declaration.location.line}:$type:$parent",
            )
        }
    }

    private fun findNamespaceNotOptimized(catalog: CockpitNgCatalog, config: AnalyzerConfig): List<Finding> {
        val rule = rule("CngNamespaceNotOptimized")
        val settings = settings(config, rule)
        if (!settings.enabled) {
            return emptyList()
        }

        return catalog.configFiles.flatMap { file ->
            val rootByPrefix = file.rootNamespaces.associateBy { it.prefix }
            file.localNamespaces.mapNotNull { namespace ->
                val rootNamespace = rootByPrefix[namespace.prefix]
                if (rootNamespace != null && rootNamespace.uri != namespace.uri) {
                    return@mapNotNull null
                }
                finding(
                    file = file.path,
                    location = namespace.location,
                    rule = rule,
                    settings = settings,
                    message = "Namespace alias '${namespace.prefix}' with URI '${namespace.uri}' should be declared on the root element.",
                    entityKey = "cng-namespace-opt:${file.path}:${namespace.location.line}:${namespace.prefix}:${namespace.uri}",
                )
            }
        }
    }

    private fun findDuplicateNamespaces(catalog: CockpitNgCatalog, config: AnalyzerConfig): List<Finding> {
        val rule = rule("CngDuplicateNamespace")
        val settings = settings(config, rule)
        if (!settings.enabled) {
            return emptyList()
        }

        return catalog.configFiles.flatMap { file ->
            file.rootNamespaces
                .groupBy { it.uri }
                .values
                .filter { it.size > 1 }
                .flatMap { duplicates ->
                    duplicates.map { namespace ->
                        finding(
                            file = file.path,
                            location = namespace.location,
                            rule = rule,
                            settings = settings,
                            message = "Namespace URI '${namespace.uri}' is declared more than once on the root element.",
                            entityKey = "cng-namespace-duplicate:${file.path}:${namespace.uri}:${namespace.prefix}",
                        )
                    }
                }
        }
    }

    private fun configContexts(catalog: CockpitNgCatalog): List<Pair<Path, CockpitContextDecl>> {
        return catalog.configFiles.flatMap { file -> file.contexts.map { file.path to it } }
    }

    private fun hasKnownFullAncestry(
        typeSystemCatalog: TypeSystemCatalog,
        typeName: String?,
        visited: MutableSet<String>,
    ): Boolean {
        if (typeName == null) {
            return true
        }
        if (!visited.add(typeName.lowercase())) {
            return true
        }
        if (typeName in knownPlatformRootTypes) {
            return true
        }

        val itemTypes = typeSystemCatalog.findItemTypes(typeName)
        if (itemTypes.isEmpty()) {
            return false
        }

        return itemTypes.all { itemType ->
            val parent = itemType.declaration.extendsType.value
            parent == null || hasKnownFullAncestry(typeSystemCatalog, parent, visited)
        }
    }

    private fun rule(ruleId: String): CockpitNgRule = rules.first { it.ruleId == ruleId }

    private fun settings(config: AnalyzerConfig, rule: CockpitNgRule): EffectiveRuleSettings {
        return resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
    }

    private fun finding(
        file: Path,
        location: SourcePosition,
        rule: CockpitNgRule,
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

    private data class CockpitNgRule(
        override val ruleId: String,
        override val defaultSeverity: FindingSeverity,
        override val domain: AnalysisDomain = AnalysisDomain.COCKPIT_NG,
    ) : RegisteredRule

    companion object {
        private const val mergeByAttribute = "merge-by"
        private const val parentAttribute = "parent"
        private const val typeAttribute = "type"
        private const val parentAutoValue = "auto"
        private const val enumerationValueType = "EnumerationValue"
        private val knownPlatformRootTypes = setOf("Item", "GenericItem", "LocalizableItem", "ExtensibleItem", "Link")
    }
}
