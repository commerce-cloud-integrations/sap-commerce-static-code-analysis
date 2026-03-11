package com.cci.sapcclint.impex

import com.cci.sapcclint.catalog.TypeSystemCatalog
import com.cci.sapcclint.catalog.findAttributesByQualifier
import com.cci.sapcclint.catalog.findCollectionTypes
import com.cci.sapcclint.catalog.hasLocalDeclaredType
import com.cci.sapcclint.catalog.findEnumTypes
import com.cci.sapcclint.catalog.findItemTypes
import com.cci.sapcclint.catalog.findIndexedAttributeQualifiers
import com.cci.sapcclint.catalog.findMapTypes
import com.cci.sapcclint.catalog.findRelations
import com.cci.sapcclint.catalog.findRelationEndsByQualifier
import com.cci.sapcclint.catalog.isSameOrSubtypeOf
import com.cci.sapcclint.config.AnalyzerConfig
import com.cci.sapcclint.core.AnalysisDomain
import com.cci.sapcclint.core.DomainAnalysisResult
import com.cci.sapcclint.core.DomainAnalyzer
import com.cci.sapcclint.core.RepositoryAnalysisContext
import com.cci.sapcclint.core.TypeSystemSupportLoader
import com.cci.sapcclint.impex.model.ImpexHeaderBlock
import com.cci.sapcclint.impex.model.ImpexHeaderParameter
import com.cci.sapcclint.impex.model.ImpexModifier
import com.cci.sapcclint.impex.model.ImpexParameter
import com.cci.sapcclint.impex.model.ImpexReference
import com.cci.sapcclint.impex.model.ImpexReferenceKind
import com.cci.sapcclint.impex.model.ParsedImpexFile
import com.cci.sapcclint.impex.parser.ImpexParser
import com.cci.sapcclint.itemsxml.model.LocatedValue
import com.cci.sapcclint.itemsxml.model.SourcePosition
import com.cci.sapcclint.project.ProjectSupportLoader
import com.cci.sapcclint.rules.EffectiveRuleSettings
import com.cci.sapcclint.rules.Finding
import com.cci.sapcclint.rules.FindingLocation
import com.cci.sapcclint.rules.FindingSeverity
import com.cci.sapcclint.rules.RegisteredRule
import com.cci.sapcclint.rules.resolveRuleSettings
import java.nio.file.Path

class ImpexDomainAnalyzer(
    private val parser: ImpexParser = ImpexParser(),
    private val typeSystemSupportLoader: TypeSystemSupportLoader = TypeSystemSupportLoader(),
    private val projectSupportLoader: ProjectSupportLoader = ProjectSupportLoader(),
    private val localClassIndexLoader: LocalClassIndexLoader = LocalClassIndexLoader(),
    private val propertyIndexLoader: PropertyIndexLoader = PropertyIndexLoader(),
) : DomainAnalyzer {

    private data class UnknownAttributeFindingContext(
        val file: Path,
        val support: ImpexSupportContext,
        val rule: SimpleDomainRule,
        val settings: EffectiveRuleSettings,
        val entityKeyPrefix: String,
    )

    override val domain: AnalysisDomain = AnalysisDomain.IMPEX

    private val rules = listOf(
        SimpleDomainRule("ImpExUnknownTypeNameInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpExUnknownTypeAttributeInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpExUnknownTypeModifierInspection", FindingSeverity.WARNING, domain),
        SimpleDomainRule("ImpExUnknownAttributeModifierInspection", FindingSeverity.WARNING, domain),
        SimpleDomainRule("ImpExInvalidBooleanModifierValueInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpExInvalidModeModifierValueInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpExInvalidDisableInterceptorTypesModifierValueInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpExInvalidProcessorValueInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpExInvalidTranslatorValueInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpExInvalidCellDecoratorValueInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpExUnknownMacrosInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpExUnknownConfigPropertyInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpExConfigProcessorInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpExIncompleteHeaderAbbreviationUsageInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpExMissingHeaderParameterInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpExMissingValueGroupInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpExOrphanValueGroupInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpExMultilineMacroNameInspection", FindingSeverity.WARNING, domain),
        SimpleDomainRule("ImpexLanguageIsNotSupportedInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpexLanguageModifierIsNotAllowedInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpexNoUniqueValueInspection", FindingSeverity.WARNING, domain),
        SimpleDomainRule("ImpexUnknownFunctionTypeInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpexFunctionReferenceTypeMismatchInspection", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpexUniqueAttributeWithoutIndex", FindingSeverity.WARNING, domain),
        SimpleDomainRule("ImpexUniqueDocumentId", FindingSeverity.ERROR, domain),
        SimpleDomainRule("ImpexOnlyUpdateAllowedForNonDynamicEnumInspection", FindingSeverity.ERROR, domain),
    )

    override fun analyze(context: RepositoryAnalysisContext): DomainAnalysisResult {
        val files = context.scan.filesFor(domain)
        if (files.isEmpty()) {
            return DomainAnalysisResult(domain, 0, emptyList(), emptyList())
        }

        val parsedFiles = files.map(parser::parse)
        val typeSystemSupport = typeSystemSupportLoader.load(context.repo, context.config)
        val projectSupport = projectSupportLoader.load(context.repo, context.config)
        val localClassIndex = localClassIndexLoader.load(context.repo, context.config)
        val propertyIndex = propertyIndexLoader.load(context.repo, context.config)
        val supportContext = ImpexSupportContext(
            catalog = typeSystemSupport.catalog,
            hasFullPlatformContext = projectSupport.registry.hasFullPlatformContext(),
            localClassIndex = localClassIndex,
            propertyIndex = propertyIndex,
        )

        val findings = buildList {
            addAll(findUnknownTypeNames(parsedFiles, supportContext, context.config))
            addAll(findUnknownAttributes(parsedFiles, supportContext, context.config))
            addAll(findUnknownTypeModifiers(parsedFiles, context.config))
            addAll(findUnknownAttributeModifiers(parsedFiles, context.config))
            addAll(findInvalidBooleanModifierValues(parsedFiles, context.config))
            addAll(findInvalidModeModifierValues(parsedFiles, context.config))
            addAll(findInvalidDisableInterceptorTypeValues(parsedFiles, context.config))
            addAll(findInvalidClassModifierValues(parsedFiles, supportContext, context.config))
            addAll(findUnknownMacros(parsedFiles, context.config))
            addAll(findUnknownConfigProperties(parsedFiles, supportContext, context.config))
            addAll(findMissingConfigProcessor(parsedFiles, context.config))
            addAll(findIncompleteHeaderAbbreviationUsage(parsedFiles, supportContext, context.config))
            addAll(findMissingHeaderParameters(parsedFiles, context.config))
            addAll(findMissingValueGroups(parsedFiles, context.config))
            addAll(findOrphanValueGroups(parsedFiles, context.config))
            addAll(findMultilineMacroNames(parsedFiles, context.config))
            addAll(findUnsupportedLanguageModifiers(parsedFiles, supportContext, context.config))
            addAll(findLanguageModifierNotAllowed(parsedFiles, supportContext, context.config))
            addAll(findUnknownFunctionTypes(parsedFiles, supportContext, context.config))
            addAll(findFunctionReferenceTypeMismatches(parsedFiles, supportContext, context.config))
            addAll(findUniqueAttributesWithoutIndexes(parsedFiles, supportContext, context.config))
            addAll(findDuplicateDocumentIds(parsedFiles, context.config))
            addAll(findDuplicateUniqueValueOverrides(parsedFiles, context.config))
            addAll(findInvalidEnumHeaderModes(parsedFiles, supportContext, context.config))
        }.sortedWith(compareBy({ it.location.file.toString() }, { it.location.position.line }, { it.ruleId }))

        return DomainAnalysisResult(
            domain = domain,
            analyzedFileCount = files.size,
            findings = findings,
            rules = rules,
        )
    }

    private fun findUnknownTypeNames(
        parsedFiles: List<ParsedImpexFile>,
        support: ImpexSupportContext,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpExUnknownTypeNameInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return buildList {
            parsedFiles.forEach { file ->
                file.headerBlocks.forEach { block ->
                    findUnknownHeaderType(file.path, block, support, rule, settings)?.let(::add)
                    addAll(findUnknownTypeModifierValues(file.path, block, support, rule, settings))
                }
            }
        }
    }

    private fun findUnknownHeaderType(
        file: Path,
        block: ImpexHeaderBlock,
        support: ImpexSupportContext,
        rule: SimpleDomainRule,
        settings: EffectiveRuleSettings,
    ): Finding? {
        val typeName = normalizeTypeName(block.header.typeName.value) ?: return null
        if (support.isResolvableType(typeName) || !support.shouldReportUnknownType()) {
            return null
        }
        return finding(
            file = file,
            location = block.header.typeName.location ?: block.header.location,
            rule = rule,
            settings = settings,
            message = "Unknown type '$typeName' in ImpEx header.",
            entityKey = "impex-type:${file}:${typeName.lowercase()}",
        )
    }

    private fun findUnknownTypeModifierValues(
        file: Path,
        block: ImpexHeaderBlock,
        support: ImpexSupportContext,
        rule: SimpleDomainRule,
        settings: EffectiveRuleSettings,
    ): List<Finding> {
        if (!support.shouldReportUnknownType()) {
            return emptyList()
        }
        return block.header.modifiers
            .asSequence()
            .filter { it.name.value == typeModifierDisableUniqueAttributesValidatorForTypes }
            .flatMap { modifier ->
                splitModifierValueTokens(modifier.value)
                    .mapNotNull { token -> unknownTypeModifierFinding(file, modifier, token, support, rule, settings) }
                    .asSequence()
            }
            .toList()
    }

    private fun unknownTypeModifierFinding(
        file: Path,
        modifier: ImpexModifier,
        token: LocatedValue<String>,
        support: ImpexSupportContext,
        rule: SimpleDomainRule,
        settings: EffectiveRuleSettings,
    ): Finding? {
        val tokenValue = token.value ?: return null
        if (tokenValue.startsWith("&") || support.isResolvableType(tokenValue)) {
            return null
        }
        return finding(
            file = file,
            location = token.location ?: modifier.value.location ?: modifier.location,
            rule = rule,
            settings = settings,
            message = "Unknown type '$tokenValue' in '${modifier.name.value}' modifier.",
            entityKey = "impex-type-modifier:${file}:${tokenValue.lowercase()}",
        )
    }

    private fun findUnknownAttributes(
        parsedFiles: List<ParsedImpexFile>,
        support: ImpexSupportContext,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpExUnknownTypeAttributeInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return buildList {
            parsedFiles.forEach { file ->
                file.headerBlocks.forEach { block ->
                    val headerType = normalizeTypeName(block.header.typeName.value)
                    val initialTypes = headerType?.let(::setOf).orEmpty()
                    block.header.parameters.forEach { parameter ->
                        addAll(validateHeaderParameter(file.path, parameter, initialTypes, support, rule, settings))
                    }
                }
            }
        }
    }

    private fun validateHeaderParameter(
        file: Path,
        parameter: ImpexHeaderParameter,
        ownerTypes: Set<String>,
        support: ImpexSupportContext,
        rule: SimpleDomainRule,
        settings: EffectiveRuleSettings,
    ): List<Finding> {
        val findings = mutableListOf<Finding>()
        val resolution = resolveQualifier(parameter.name, ownerTypes, support)
        val context = UnknownAttributeFindingContext(file, support, rule, settings, "impex-attribute")
        maybeUnknownAttributeFinding(
            context = context,
            reference = parameter.name,
            ownerTypes = ownerTypes,
            resolution = resolution,
        )?.let(findings::add)

        parameter.leadingParameters.forEach { nested ->
            findings += validateParameter(file, nested, resolution.nextTypes, support, rule, settings)
        }
        parameter.trailingParameters.forEach { nested ->
            findings += validateParameter(file, nested, resolution.nextTypes, support, rule, settings)
        }

        return findings
    }

    private fun validateParameter(
        file: Path,
        parameter: ImpexParameter,
        ownerTypes: Set<String>,
        support: ImpexSupportContext,
        rule: SimpleDomainRule,
        settings: EffectiveRuleSettings,
    ): List<Finding> {
        val findings = mutableListOf<Finding>()
        val inlineTypeName = parameter.inlineTypeName(support)
        if (inlineTypeName != null) {
            parameter.suffixReference?.let { suffix ->
                val suffixResolution = resolveQualifier(suffix, setOf(inlineTypeName), support)
                maybeUnknownAttributeFinding(
                    context = UnknownAttributeFindingContext(file, support, rule, settings, "impex-function-attribute"),
                    reference = suffix,
                    ownerTypes = setOf(inlineTypeName),
                    resolution = suffixResolution,
                )?.let(findings::add)
            }
            parameter.parameters.forEach { nested ->
                findings += validateParameter(file, nested, setOf(inlineTypeName), support, rule, settings)
            }
            return findings
        }

        val resolution = resolveQualifier(parameter.name, ownerTypes, support)
        val context = UnknownAttributeFindingContext(file, support, rule, settings, "impex-attribute")
        maybeUnknownAttributeFinding(
            context = context,
            reference = parameter.name,
            ownerTypes = ownerTypes,
            resolution = resolution,
        )?.let(findings::add)

        parameter.parameters.forEach { nested ->
            findings += validateParameter(file, nested, resolution.nextTypes, support, rule, settings)
        }

        parameter.suffixReference?.let { suffix ->
            val suffixResolution = resolveQualifier(suffix, resolution.nextTypes, support)
            maybeUnknownAttributeFinding(
                context = UnknownAttributeFindingContext(file, support, rule, settings, "impex-attribute-suffix"),
                reference = suffix,
                ownerTypes = resolution.nextTypes,
                resolution = suffixResolution,
            )?.let(findings::add)
        }

        return findings
    }

    private fun maybeUnknownAttributeFinding(
        context: UnknownAttributeFindingContext,
        reference: ImpexReference,
        ownerTypes: Set<String>,
        resolution: QualifierResolution,
    ): Finding? {
        if (!reference.kind.isAttributeLike() || resolution.resolved) {
            return null
        }
        if (!context.support.shouldReportUnknownAttribute(ownerTypes, resolution.confidentOwnerTypes)) {
            return null
        }

        val ownerLabel = ownerTypes.joinToString(", ").ifBlank { "unknown type" }
        return finding(
            file = context.file,
            location = reference.location,
            rule = context.rule,
            settings = context.settings,
            message = "Unknown attribute '${reference.text}' for type '$ownerLabel'.",
            entityKey = "${context.entityKeyPrefix}:${context.file}:${ownerLabel.lowercase()}:${reference.text.lowercase()}",
        )
    }

    private fun resolveQualifier(
        reference: ImpexReference,
        ownerTypes: Set<String>,
        support: ImpexSupportContext,
    ): QualifierResolution {
        if (!reference.kind.isAttributeLike() || reference.text.isBlank() || ownerTypes.isEmpty()) {
            return QualifierResolution(resolved = !reference.kind.isAttributeLike(), nextTypes = emptySet(), confidentOwnerTypes = emptySet())
        }

        val nextTypes = linkedSetOf<String>()
        val confidentOwnerTypes = linkedSetOf<String>()
        var resolved = false

        ownerTypes.forEach { ownerType ->
            if (support.canConfidentlyValidateAttributes(ownerType)) {
                confidentOwnerTypes += ownerType
            }

            val attributeMatches = support.catalog.findAttributesByQualifier(
                typeName = ownerType,
                qualifier = reference.text,
                includeAncestors = true,
            )
            val relationMatches = support.catalog.findRelationEndsByQualifier(
                typeName = ownerType,
                qualifier = reference.text,
                includeAncestors = true,
            )

            if (attributeMatches.isNotEmpty() || relationMatches.isNotEmpty()) {
                resolved = true
            }

            attributeMatches.mapNotNullTo(nextTypes) { normalizeTypeName(it.declaration.type.value) }
            relationMatches.mapNotNullTo(nextTypes) { normalizeTypeName(it.declaration.type.value) }
        }

        return QualifierResolution(
            resolved = resolved,
            nextTypes = nextTypes,
            confidentOwnerTypes = confidentOwnerTypes,
        )
    }

    private fun findUnknownTypeModifiers(
        parsedFiles: List<ParsedImpexFile>,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpExUnknownTypeModifierInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return parsedFiles.flatMap { file ->
            file.headerBlocks.flatMap { block ->
                block.header.modifiers
                    .filter { it.name.value !in knownTypeModifierNames }
                    .map { modifier ->
                        finding(
                            file = file.path,
                            location = modifier.name.location ?: modifier.location,
                            rule = rule,
                            settings = settings,
                            message = "Unknown type modifier '${modifier.name.value}'.",
                            entityKey = "impex-type-modifier:${file.path}:${modifier.name.value?.lowercase()}",
                        )
                    }
            }
        }
    }

    private fun findUnknownAttributeModifiers(
        parsedFiles: List<ParsedImpexFile>,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpExUnknownAttributeModifierInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return parsedFiles.flatMap { file ->
            collectParameterModifierScopes(file).flatMap { scope ->
                if (scope.hasTranslator) {
                    emptyList()
                } else {
                    scope.modifiers
                        .filter { it.name.value !in knownAttributeModifierNames }
                        .map { modifier ->
                            finding(
                                file = file.path,
                                location = modifier.name.location ?: modifier.location,
                                rule = rule,
                                settings = settings,
                                message = "Unknown attribute modifier '${modifier.name.value}'.",
                                entityKey = "impex-attribute-modifier:${file.path}:${modifier.name.value?.lowercase()}",
                            )
                        }
                }
            }
        }
    }

    private fun findInvalidBooleanModifierValues(
        parsedFiles: List<ParsedImpexFile>,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpExInvalidBooleanModifierValueInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return collectAllModifiers(parsedFiles).mapNotNull { candidate ->
            val modifierName = candidate.modifier.name.value ?: return@mapNotNull null
            if (modifierName !in booleanModifierNames) {
                return@mapNotNull null
            }
            val value = candidate.modifier.value.value ?: return@mapNotNull null
            if (value in booleanModifierValues) {
                return@mapNotNull null
            }
            finding(
                file = candidate.file,
                location = candidate.modifier.value.location ?: candidate.modifier.location,
                rule = rule,
                settings = settings,
                message = "Modifier '$modifierName' expects one of ${booleanModifierValues.joinToString(", ")} but was '$value'.",
                entityKey = "impex-boolean-modifier:${candidate.file}:${modifierName.lowercase()}",
            )
        }
    }

    private fun findInvalidModeModifierValues(
        parsedFiles: List<ParsedImpexFile>,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpExInvalidModeModifierValueInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return collectParameterModifierScopes(parsedFiles).flatMap { scope ->
            scope.modifiers.mapNotNull { modifier ->
                if (modifier.name.value != attributeModifierMode) {
                    return@mapNotNull null
                }
                val value = modifier.value.value ?: return@mapNotNull null
                if (value in allowedModeModifierValues) {
                    return@mapNotNull null
                }
                finding(
                    file = scope.file,
                    location = modifier.value.location ?: modifier.location,
                    rule = rule,
                    settings = settings,
                    message = "Modifier 'mode' must be one of ${allowedModeModifierValues.joinToString(", ")} but was '$value'.",
                    entityKey = "impex-mode-modifier:${scope.file}:${value.lowercase()}",
                )
            }
        }
    }

    private fun findInvalidDisableInterceptorTypeValues(
        parsedFiles: List<ParsedImpexFile>,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpExInvalidDisableInterceptorTypesModifierValueInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return collectAllModifiers(parsedFiles).mapNotNull { candidate ->
            val modifier = candidate.modifier
            if (modifier.name.value != typeModifierDisableInterceptorTypes) {
                return@mapNotNull null
            }
            val value = modifier.value.value ?: return@mapNotNull null
            if (value in allowedDisableInterceptorTypes) {
                return@mapNotNull null
            }
            finding(
                file = candidate.file,
                location = modifier.value.location ?: modifier.location,
                rule = rule,
                settings = settings,
                message = "Modifier '${modifier.name.value}' must be one of ${allowedDisableInterceptorTypes.joinToString(", ")} but was '$value'.",
                entityKey = "impex-disable-interceptor-types:${candidate.file}:${value.lowercase()}",
            )
        }
    }

    private fun findInvalidClassModifierValues(
        parsedFiles: List<ParsedImpexFile>,
        support: ImpexSupportContext,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rulesByModifier = listOf(
            ClassModifierRule(
                rule = rule("ImpExInvalidProcessorValueInspection"),
                modifierName = typeModifierProcessor,
                targetTypes = setOf("de.hybris.platform.impex.jalo.imp.ImportProcessor"),
                label = "ImportProcessor",
            ),
            ClassModifierRule(
                rule = rule("ImpExInvalidTranslatorValueInspection"),
                modifierName = attributeModifierTranslator,
                targetTypes = setOf(
                    "de.hybris.platform.impex.jalo.translators.SpecialValueTranslator",
                    "de.hybris.platform.impex.jalo.translators.HeaderCellTranslator",
                    "de.hybris.platform.impex.jalo.translators.AbstractValueTranslator",
                ),
                label = "SpecialValueTranslator, HeaderCellTranslator, or AbstractValueTranslator",
            ),
            ClassModifierRule(
                rule = rule("ImpExInvalidCellDecoratorValueInspection"),
                modifierName = attributeModifierCellDecorator,
                targetTypes = setOf("de.hybris.platform.util.CSVCellDecorator"),
                label = "CSVCellDecorator",
            ),
        )

        return rulesByModifier.flatMap { classRule ->
            val settings = resolveRuleSettings(config, classRule.rule.ruleId, classRule.rule.defaultSeverity)
            if (!settings.enabled) {
                emptyList()
            } else {
                collectAllModifiers(parsedFiles).mapNotNull { candidate ->
                    val modifier = candidate.modifier
                    if (modifier.name.value != classRule.modifierName) {
                        return@mapNotNull null
                    }
                    val value = modifier.value.value ?: return@mapNotNull null
                    if (value.startsWith("$")) {
                        return@mapNotNull null
                    }

                    when (support.localClassIndex.resolvesAssignableTo(value, classRule.targetTypes)) {
                        LocalClassResolution.VALID -> null
                        LocalClassResolution.UNRESOLVED -> null
                        LocalClassResolution.INVALID -> finding(
                            file = candidate.file,
                            location = modifier.value.location ?: modifier.location,
                            rule = classRule.rule,
                            settings = settings,
                            message = "Modifier '${modifier.name.value}' must reference a ${classRule.label} implementation.",
                            entityKey = "impex-class-modifier:${candidate.file}:${modifier.name.value}:${value.lowercase()}",
                        )
                    }
                }
            }
        }
    }

    private fun findUnknownMacros(
        parsedFiles: List<ParsedImpexFile>,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpExUnknownMacrosInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return parsedFiles.flatMap { file ->
            val declaredMacros = file.macroDeclarations.mapNotNullTo(linkedSetOf()) { it.name.value }
            collectMacroUsages(file).mapNotNull { usage ->
                val text = usage.reference.text
                if (text.startsWith(configMacroPrefix) || text in declaredMacros) {
                    return@mapNotNull null
                }
                finding(
                    file = file.path,
                    location = usage.reference.location,
                    rule = rule,
                    settings = settings,
                    message = "Unknown macro '$text'.",
                    entityKey = "impex-macro:${file.path}:${text.lowercase()}",
                )
            }
        }
    }

    private fun findUnknownConfigProperties(
        parsedFiles: List<ParsedImpexFile>,
        support: ImpexSupportContext,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpExUnknownConfigPropertyInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return buildList {
            parsedFiles.forEach { file ->
                collectMacroUsages(file)
                    .filter { it.reference.text.startsWith(configMacroPrefix) }
                    .forEach { usage ->
                        val propertyName = usage.reference.text.removePrefix(configMacroPrefix)
                        if (propertyName.isBlank() || support.propertyIndex.containsKey(propertyName)) {
                            return@forEach
                        }
                        add(
                            finding(
                                file = file.path,
                                location = usage.reference.location,
                                rule = rule,
                                settings = settings,
                                message = "Unknown config property '$propertyName'.",
                                entityKey = "impex-config-property:${file.path}:${propertyName.lowercase()}",
                            )
                        )
                    }

                file.macroDeclarations
                    .filter { it.name.value?.startsWith(configMacroPrefix) == true }
                    .forEach { declaration ->
                        val propertyKey = declaration.rawValue.trim()
                        if (propertyKey.isBlank() || support.propertyIndex.containsKey(propertyKey)) {
                            return@forEach
                        }
                        add(
                            finding(
                                file = file.path,
                                location = declaration.name.location ?: declaration.location,
                                rule = rule,
                                settings = settings,
                                message = "Unknown config property '$propertyKey'.",
                                entityKey = "impex-config-declaration:${file.path}:${propertyKey.lowercase()}",
                            )
                        )
                    }
            }
        }
    }

    private fun findMissingConfigProcessor(
        parsedFiles: List<ParsedImpexFile>,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpExConfigProcessorInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return parsedFiles.flatMap { file ->
            val configUsages = collectMacroUsages(file)
                .filter { it.reference.text.startsWith(configMacroPrefix) }
            val configMacroDeclarations = file.macroDeclarations
                .filter { it.name.value?.startsWith(configMacroPrefix) == true }
            if (configUsages.isEmpty() && configMacroDeclarations.isEmpty()) {
                return@flatMap emptyList()
            }

            val hasProcessorDeclaration = file.headerBlocks.any { block ->
                block.header.modifiers.any { modifier ->
                    modifier.name.value == typeModifierProcessor && modifier.value.value == configPropertyImportProcessor
                }
            }
            if (hasProcessorDeclaration) {
                return@flatMap emptyList()
            }

            configMacroDeclarations.map { declaration ->
                finding(
                    file = file.path,
                    location = declaration.name.location ?: declaration.location,
                    rule = rule,
                    settings = settings,
                    message = "ImpEx files using '$configMacroShortPrefix' macros must declare processor '$configPropertyImportProcessor'.",
                    entityKey = "impex-config-processor:${file.path}:${declaration.name.value?.lowercase()}",
                )
            } + configUsages.map { usage ->
                finding(
                    file = file.path,
                    location = usage.reference.location,
                    rule = rule,
                    settings = settings,
                    message = "ImpEx files using '$configMacroShortPrefix' macros must declare processor '$configPropertyImportProcessor'.",
                    entityKey = "impex-config-processor:${file.path}:${usage.reference.text.lowercase()}:${usage.reference.location.line}:${usage.reference.location.column}",
                )
            }
        }
    }

    private fun findIncompleteHeaderAbbreviationUsage(
        parsedFiles: List<ParsedImpexFile>,
        support: ImpexSupportContext,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpExIncompleteHeaderAbbreviationUsageInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled || support.propertyIndex.headerAbbreviationRules.isEmpty()) {
            return emptyList()
        }

        return parsedFiles.flatMap { file ->
            val declaredMacros = file.macroDeclarations.mapNotNullTo(linkedSetOf()) { it.name.value }
            file.headerBlocks.flatMap { block ->
                block.header.parameters.mapNotNull { parameter ->
                    val name = parameter.name.text
                    val abbreviationRule = support.propertyIndex.headerAbbreviationRules.firstOrNull { it.pattern.matches(name) }
                        ?: return@mapNotNull null
                    val missingMacros = macroReferenceRegex.findAll(abbreviationRule.expansion)
                        .map { it.value }
                        .distinct()
                        .filterNot { it in declaredMacros }
                        .toList()
                    if (missingMacros.isEmpty()) {
                        return@mapNotNull null
                    }
                    finding(
                        file = file.path,
                        location = parameter.name.location,
                        rule = rule,
                        settings = settings,
                        message = "Header abbreviation '${parameter.name.text}' is incomplete; declare macros ${missingMacros.joinToString(", ")}.",
                        entityKey = "impex-header-abbreviation:${file.path}:${parameter.name.text.lowercase()}",
                    )
                }
            }
        }
    }

    private fun findMissingHeaderParameters(
        parsedFiles: List<ParsedImpexFile>,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpExMissingHeaderParameterInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return parsedFiles.flatMap { file ->
            file.headerBlocks.flatMap { block ->
                block.header.missingParameterSeparators.mapIndexed { index, location ->
                    finding(
                        file = file.path,
                        location = location,
                        rule = rule,
                        settings = settings,
                        message = "Header parameter separator is missing a following parameter name.",
                        entityKey = "impex-missing-header-parameter:${file.path}:${location.line}:${location.column}:$index",
                    )
                }
            }
        }
    }

    private fun findMissingValueGroups(
        parsedFiles: List<ParsedImpexFile>,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpExMissingValueGroupInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return parsedFiles.flatMap { file ->
            file.headerBlocks.flatMap { block ->
                block.valueLines.mapNotNull { valueLine ->
                    val missingValueGroups = block.header.parameters.size - valueLine.valueGroups.size
                    if (missingValueGroups <= 0) {
                        return@mapNotNull null
                    }
                    finding(
                        file = file.path,
                        location = valueLine.location,
                        rule = rule,
                        settings = settings,
                        message = "Value line is missing $missingValueGroups value group(s).",
                        entityKey = "impex-missing-value-group:${file.path}:${valueLine.location.line}:${valueLine.location.column}",
                    )
                }
            }
        }
    }

    private fun findOrphanValueGroups(
        parsedFiles: List<ParsedImpexFile>,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpExOrphanValueGroupInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return parsedFiles.flatMap { file ->
            file.headerBlocks.flatMap { block ->
                block.valueLines.flatMap { valueLine ->
                    val extraGroups = valueLine.valueGroups.size - block.header.parameters.size
                    if (extraGroups <= 0) {
                        return@flatMap emptyList()
                    }
                    valueLine.valueGroups.takeLast(extraGroups).map { valueGroup ->
                        val preview = valueGroup.rawValue.let {
                            if (it.length > 50) it.take(50) + "..." else it
                        }
                        finding(
                            file = file.path,
                            location = valueGroup.location,
                            rule = rule,
                            settings = settings,
                            message = "Orphan value group '$preview' has no matching header parameter.",
                            entityKey = "impex-orphan-value-group:${file.path}:${valueGroup.location.line}:${valueGroup.location.column}",
                        )
                    }
                }
            }
        }
    }

    private fun findMultilineMacroNames(
        parsedFiles: List<ParsedImpexFile>,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpExMultilineMacroNameInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return parsedFiles.flatMap { file ->
            file.macroDeclarations.mapNotNull { declaration ->
                if ('\n' !in declaration.rawName && '\\' !in declaration.rawName) {
                    return@mapNotNull null
                }
                finding(
                    file = file.path,
                    location = declaration.name.location ?: declaration.location,
                    rule = rule,
                    settings = settings,
                    message = "Macro name '${declaration.name.value}' must not span multiple lines.",
                    entityKey = "impex-multiline-macro:${file.path}:${declaration.name.value?.lowercase()}",
                )
            }
        }
    }

    private fun findUnsupportedLanguageModifiers(
        parsedFiles: List<ParsedImpexFile>,
        support: ImpexSupportContext,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpexLanguageIsNotSupportedInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        val supportedLanguages = support.propertyIndex.supportedLanguages()
        if (support.propertyIndex.valuesByKey["lang.packs"].isNullOrEmpty()) {
            return emptyList()
        }

        return collectParameterModifierScopes(parsedFiles).flatMap { scope ->
            scope.modifiers.mapNotNull { modifier ->
                if (modifier.name.value != attributeModifierLang) {
                    return@mapNotNull null
                }
                val language = resolveModifierValue(modifier.value.value, scope.declaredMacros, support)?.trim()?.lowercase()
                    ?: return@mapNotNull null
                if (language in supportedLanguages) {
                    return@mapNotNull null
                }
                finding(
                    file = scope.file,
                    location = modifier.value.location ?: modifier.location,
                    rule = rule,
                    settings = settings,
                    message = "Language '$language' is not present in lang.packs (${supportedLanguages.joinToString(", ")}).",
                    entityKey = "impex-lang:${scope.file}:${language}",
                )
            }
        }
    }

    private fun findLanguageModifierNotAllowed(
        parsedFiles: List<ParsedImpexFile>,
        support: ImpexSupportContext,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpexLanguageModifierIsNotAllowedInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return collectResolvedHeaderParameters(parsedFiles, support).mapNotNull { candidate ->
            val langModifier = candidate.parameter.modifiers.firstOrNull { it.name.value == attributeModifierLang }
                ?: return@mapNotNull null
            val resolvedAttributes = candidate.resolvedAttributes
            if (resolvedAttributes.isEmpty()) {
                return@mapNotNull null
            }
            val allLocalized = resolvedAttributes.all { attribute ->
                attribute.declaration.type.value?.trim()?.startsWith("localized:", ignoreCase = true) == true
            }
            if (allLocalized) {
                return@mapNotNull null
            }
            finding(
                file = candidate.file,
                location = langModifier.name.location ?: langModifier.location,
                rule = rule,
                settings = settings,
                message = "Lang modifier is not allowed for non-localized attribute '${candidate.parameter.name.text}'.",
                entityKey = "impex-lang-modifier:${candidate.file}:${candidate.parameter.name.text.lowercase()}",
            )
        }
    }

    private fun findUnknownFunctionTypes(
        parsedFiles: List<ParsedImpexFile>,
        support: ImpexSupportContext,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpexUnknownFunctionTypeInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled || !support.shouldReportUnknownType()) {
            return emptyList()
        }

        return collectFunctionParameters(parsedFiles, support).mapNotNull { candidate ->
            val inlineType = candidate.parameter.inlineTypeName(support) ?: return@mapNotNull null
            if (support.isResolvableType(inlineType)) {
                return@mapNotNull null
            }
            finding(
                file = candidate.file,
                location = candidate.parameter.name.location,
                rule = rule,
                settings = settings,
                message = "Unknown inline type '$inlineType' in function reference.",
                entityKey = "impex-function-inline-type:${candidate.file}:${inlineType.lowercase()}",
            )
        }
    }

    private fun findFunctionReferenceTypeMismatches(
        parsedFiles: List<ParsedImpexFile>,
        support: ImpexSupportContext,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpexFunctionReferenceTypeMismatchInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return collectFunctionParameters(parsedFiles, support).mapNotNull { candidate ->
            val inlineType = candidate.parameter.inlineTypeName(support) ?: return@mapNotNull null
            if (!support.isResolvableType(inlineType)) {
                return@mapNotNull null
            }
            val expectedTypes = candidate.expectedTypes.filterNotNull().toSet()
            if (expectedTypes.isEmpty()) {
                return@mapNotNull null
            }

            val mismatch = expectedTypes.all { expectedType ->
                when (support.classifierKind(expectedType)) {
                    ClassifierKind.ITEM -> !support.catalog.isSameOrSubtypeOf(inlineType, expectedType)
                    ClassifierKind.ITEM_ROOT -> !support.catalog.isSameOrSubtypeOf(inlineType, expectedType) &&
                        !sameClassifierName(inlineType, expectedType)
                    ClassifierKind.ENUM,
                    ClassifierKind.COLLECTION,
                    ClassifierKind.MAP,
                    ClassifierKind.RELATION,
                    ClassifierKind.ATOMIC,
                    ClassifierKind.UNKNOWN -> true
                }
            }

            if (!mismatch) {
                return@mapNotNull null
            }

            val expectedTypeLabel = expectedTypes.joinToString(", ")
            finding(
                file = candidate.file,
                location = candidate.parameter.name.location,
                rule = rule,
                settings = settings,
                message = "Inline type '$inlineType' does not match expected reference type '$expectedTypeLabel' for '${candidate.referenceName ?: "?"}'.",
                entityKey = "impex-function-mismatch:${candidate.file}:${inlineType.lowercase()}:${expectedTypeLabel.lowercase()}",
            )
        }
    }

    private fun findUniqueAttributesWithoutIndexes(
        parsedFiles: List<ParsedImpexFile>,
        support: ImpexSupportContext,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpexUniqueAttributeWithoutIndex")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return parsedFiles.flatMap { file ->
            file.headerBlocks.flatMap { block ->
                val headerType = normalizeTypeName(block.header.typeName.value)
                val indexedQualifiers = support.catalog.findIndexedAttributeQualifiers(headerType, includeAncestors = true)
                val hasLocalTypeMetadata = support.catalog.findItemTypes(headerType).isNotEmpty()
                block.header.parameters.mapNotNull { parameter ->
                    val qualifier = parameter.name.text
                    if (!hasLocalTypeMetadata || !support.canConfidentlyValidateIndexes(headerType) ||
                        qualifier.startsWith("@") || qualifier.equals("pk", ignoreCase = true)
                    ) {
                        return@mapNotNull null
                    }
                    val uniqueModifier = parameter.modifiers.firstOrNull {
                        it.name.value == attributeModifierUnique && it.value.value == "true"
                    } ?: return@mapNotNull null
                    if (qualifier in indexedQualifiers) {
                        return@mapNotNull null
                    }
                    finding(
                        file = file.path,
                        location = uniqueModifier.location,
                        rule = rule,
                        settings = settings,
                        message = "Attribute '$qualifier' does not have an index for '$headerType' type.",
                        entityKey = "impex-unique-index:${file.path}:${headerType?.lowercase()}:${qualifier.lowercase()}",
                    )
                }
            }
        }
    }

    private fun findDuplicateDocumentIds(
        parsedFiles: List<ParsedImpexFile>,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpexUniqueDocumentId")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return parsedFiles.flatMap { file ->
            file.headerBlocks.flatMap { block ->
                block.header.parameters.flatMapIndexed { index, parameter ->
                    if (parameter.name.kind != ImpexReferenceKind.DOCUMENT_ID) {
                        return@flatMapIndexed emptyList()
                    }
                    val seenValues = mutableSetOf<String>()
                    block.valueLines.mapNotNull { valueLine ->
                        val valueGroup = valueLine.valueGroups.getOrNull(index) ?: return@mapNotNull null
                        val value = valueGroup.rawValue.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        if (seenValues.add(value)) {
                            return@mapNotNull null
                        }
                        finding(
                            file = file.path,
                            location = valueGroup.location,
                            rule = rule,
                            settings = settings,
                            message = "Qualifier '$value' is already used for docId '${parameter.name.text}'.",
                            entityKey = "impex-doc-id:${file.path}:${parameter.name.text.lowercase()}:${value.lowercase()}",
                        )
                    }
                }
            }
        }
    }

    private fun findDuplicateUniqueValueOverrides(
        parsedFiles: List<ParsedImpexFile>,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpexNoUniqueValueInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return parsedFiles.flatMap { file ->
            val groupedBlocks = file.headerBlocks.groupBy { block ->
                DuplicateHeaderKey(
                    typeName = normalizeTypeName(block.header.typeName.value),
                    keyParameters = block.header.parameters
                        .filter(::isUniqueHeaderParameter)
                        .map { it.name.text.lowercase() }
                        .sorted(),
                )
            }

            groupedBlocks.flatMap { (headerKey, blocks) ->
                analyzeDuplicateUniqueKeys(file.path, headerKey, blocks, rule, settings)
            }
        }
    }

    private fun findInvalidEnumHeaderModes(
        parsedFiles: List<ParsedImpexFile>,
        support: ImpexSupportContext,
        config: AnalyzerConfig,
    ): List<Finding> {
        val rule = rule("ImpexOnlyUpdateAllowedForNonDynamicEnumInspection")
        val settings = resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
        if (!settings.enabled) {
            return emptyList()
        }

        return parsedFiles.flatMap { file ->
            file.headerBlocks.mapNotNull { block ->
                val typeName = normalizeTypeName(block.header.typeName.value) ?: return@mapNotNull null
                val enumType = support.catalog.findEnumTypes(typeName).firstOrNull()?.declaration ?: return@mapNotNull null
                if (enumType.dynamic.value == true || block.header.mode in allowedStaticEnumModes) {
                    return@mapNotNull null
                }
                finding(
                    file = file.path,
                    location = block.header.typeName.location ?: block.header.location,
                    rule = rule,
                    settings = settings,
                    message = "Mode '${block.header.mode.name}' is not allowed for non-dynamic enum '$typeName'; use UPDATE or REMOVE.",
                    entityKey = "impex-enum-mode:${file.path}:${typeName.lowercase()}:${block.header.mode.name.lowercase()}",
                )
            }
        }
    }

    private fun analyzeDuplicateUniqueKeys(
        file: Path,
        headerKey: DuplicateHeaderKey,
        blocks: List<ImpexHeaderBlock>,
        rule: SimpleDomainRule,
        settings: EffectiveRuleSettings,
    ): List<Finding> {
        if (headerKey.keyParameters.isEmpty()) {
            return emptyList()
        }

        val rowsByKey = collectRowsByUniqueKey(headerKey, blocks)

        return buildList {
            rowsByKey.values.forEach { rows ->
                if (rows.size < 2) {
                    return@forEach
                }
                val baseline = rows.first()
                rows.drop(1).forEach { row ->
                    addAll(compareDuplicateUniqueKeyRow(file, headerKey, baseline, row, rule, settings))
                }
            }
        }
    }

    private fun collectRowsByUniqueKey(
        headerKey: DuplicateHeaderKey,
        blocks: List<ImpexHeaderBlock>,
    ): Map<List<String>, MutableList<ValueRowContext>> {
        val rowsByKey = linkedMapOf<List<String>, MutableList<ValueRowContext>>()
        blocks.forEach { block ->
            block.valueLines.forEach { valueLine ->
                val row = buildRowContext(block, valueLine) ?: return@forEach
                val keyValues = headerKey.keyParameters.map { parameterName ->
                    row.columnValues[parameterName]?.rawValue?.trim().orEmpty()
                }
                if (keyValues.any(String::isBlank)) {
                    return@forEach
                }
                rowsByKey.getOrPut(keyValues) { mutableListOf() } += row
            }
        }
        return rowsByKey
    }

    private fun compareDuplicateUniqueKeyRow(
        file: Path,
        headerKey: DuplicateHeaderKey,
        baseline: ValueRowContext,
        row: ValueRowContext,
        rule: SimpleDomainRule,
        settings: EffectiveRuleSettings,
    ): List<Finding> {
        return row.columnValues.mapNotNull { (parameterName, rowGroup) ->
            if (parameterName in headerKey.keyParameters) {
                return@mapNotNull null
            }
            val baselineGroup = baseline.columnValues[parameterName] ?: return@mapNotNull null
            val baselineValue = baselineGroup.rawValue.trim()
            val rowValue = rowGroup.rawValue.trim()
            if (baselineValue.isBlank() || rowValue.isBlank() || baselineValue == rowValue) {
                return@mapNotNull null
            }
            finding(
                file = file,
                location = rowGroup.location,
                rule = rule,
                settings = settings,
                message = "This value overrides an earlier row with the same unique key.",
                entityKey = "impex-duplicate-override:${file}:${parameterName}:${row.valueLine.location.line}:${row.valueLine.location.column}",
            )
        }
    }

    private fun buildRowContext(
        block: ImpexHeaderBlock,
        valueLine: com.cci.sapcclint.impex.model.ImpexValueLine,
    ): ValueRowContext? {
        val columnValues = linkedMapOf<String, com.cci.sapcclint.impex.model.ImpexValueGroup>()
        block.header.parameters.forEachIndexed { index, parameter ->
            if (hasLangOrAppendModifier(parameter)) {
                return@forEachIndexed
            }
            val valueGroup = valueLine.valueGroups.getOrNull(index) ?: return@forEachIndexed
            columnValues[parameter.name.text.lowercase()] = valueGroup
        }
        return ValueRowContext(block, valueLine, columnValues)
    }

    private fun collectAllModifiers(parsedFiles: List<ParsedImpexFile>): List<ModifierCandidate> {
        return parsedFiles.flatMap { file ->
            buildList {
                file.headerBlocks.forEach { block ->
                    addAll(block.header.modifiers.map { ModifierCandidate(file.path, it) })
                    collectParameterModifierScopes(
                        parsedFile = file,
                        block = block,
                        parameters = block.header.parameters,
                        declaredMacros = file.macroDeclarations.associateNotNullBy({ it.name.value }, { it.rawValue }),
                    ).forEach { scope ->
                        addAll(scope.modifiers.map { ModifierCandidate(scope.file, it) })
                    }
                }
            }
        }
    }

    private fun collectMacroUsages(parsedFile: ParsedImpexFile): List<MacroUsageCandidate> {
        return buildList {
            parsedFile.macroDeclarations.forEach { declaration ->
                declaration.references
                    .filter { it.kind == ImpexReferenceKind.MACRO_USAGE }
                    .forEach { add(MacroUsageCandidate(parsedFile.path, it)) }
            }

            parsedFile.headerBlocks.forEach { block ->
                block.header.typeName.value
                    ?.takeIf { it.startsWith("$") }
                    ?.let {
                        block.header.typeName.location?.let { location ->
                            add(MacroUsageCandidate(parsedFile.path, ImpexReference(it, ImpexReferenceKind.MACRO_USAGE, location)))
                        }
                    }
                block.header.modifiers.forEach { modifier ->
                    addAll(extractMacroUsageCandidates(parsedFile.path, modifier.value.value, modifier.value.location))
                }
                block.header.parameters.forEach { parameter ->
                    addAll(collectMacroUsages(parsedFile.path, parameter))
                }
                block.valueLines.forEach { valueLine ->
                    valueLine.subType.value
                        ?.takeIf { it.startsWith("$") }
                        ?.let {
                            valueLine.subType.location?.let { location ->
                                add(MacroUsageCandidate(parsedFile.path, ImpexReference(it, ImpexReferenceKind.MACRO_USAGE, location)))
                            }
                        }
                    valueLine.valueGroups.forEach { valueGroup ->
                        valueGroup.references
                            .filter { it.kind == ImpexReferenceKind.MACRO_USAGE }
                            .forEach { add(MacroUsageCandidate(parsedFile.path, it)) }
                    }
                }
            }
        }
    }

    private fun collectMacroUsages(
        file: Path,
        parameter: ImpexHeaderParameter,
    ): List<MacroUsageCandidate> {
        return buildList {
            if (parameter.name.kind == ImpexReferenceKind.MACRO_USAGE) {
                add(MacroUsageCandidate(file, parameter.name))
            }
            parameter.modifiers.forEach { modifier ->
                addAll(extractMacroUsageCandidates(file, modifier.value.value, modifier.value.location))
            }
            parameter.leadingParameters.forEach { nested -> addAll(collectMacroUsages(file, nested)) }
            parameter.trailingParameters.forEach { nested -> addAll(collectMacroUsages(file, nested)) }
        }
    }

    private fun collectMacroUsages(
        file: Path,
        parameter: ImpexParameter,
    ): List<MacroUsageCandidate> {
        return buildList {
            if (parameter.name.kind == ImpexReferenceKind.MACRO_USAGE) {
                add(MacroUsageCandidate(file, parameter.name))
            }
            parameter.suffixReference
                ?.takeIf { it.kind == ImpexReferenceKind.MACRO_USAGE }
                ?.let { add(MacroUsageCandidate(file, it)) }
            parameter.modifiers.forEach { modifier ->
                addAll(extractMacroUsageCandidates(file, modifier.value.value, modifier.value.location))
            }
            parameter.parameters.forEach { nested -> addAll(collectMacroUsages(file, nested)) }
        }
    }

    private fun extractMacroUsageCandidates(
        file: Path,
        raw: String?,
        location: SourcePosition?,
    ): List<MacroUsageCandidate> {
        if (raw.isNullOrBlank() || location == null) {
            return emptyList()
        }

        return macroReferenceRegex.findAll(raw).map { match ->
            MacroUsageCandidate(
                file = file,
                reference = ImpexReference(
                    text = match.value,
                    kind = ImpexReferenceKind.MACRO_USAGE,
                    location = SourcePosition(location.line, location.column + match.range.first),
                ),
            )
        }.toList()
    }

    private fun collectParameterModifierScopes(parsedFiles: List<ParsedImpexFile>): List<ParameterModifierScope> {
        return parsedFiles.flatMap(::collectParameterModifierScopes)
    }

    private fun collectParameterModifierScopes(parsedFile: ParsedImpexFile): List<ParameterModifierScope> {
        return parsedFile.headerBlocks.flatMap { block ->
            collectParameterModifierScopes(
                parsedFile = parsedFile,
                block = block,
                parameters = block.header.parameters,
                declaredMacros = parsedFile.macroDeclarations.associateNotNullBy({ it.name.value }, { it.rawValue }),
            )
        }
    }

    private fun collectParameterModifierScopes(
        parsedFile: ParsedImpexFile,
        block: ImpexHeaderBlock,
        parameters: List<ImpexHeaderParameter>,
        declaredMacros: Map<String, String>,
    ): List<ParameterModifierScope> {
        return buildList {
            parameters.forEach { parameter ->
                add(
                    ParameterModifierScope(
                        file = parsedFile.path,
                        block = block,
                        parameter = parameter,
                        modifiers = parameter.modifiers,
                        hasTranslator = parameter.modifiers.any { it.name.value == attributeModifierTranslator },
                        declaredMacros = declaredMacros,
                    )
                )
                parameter.leadingParameters.forEach { nested ->
                    addAll(collectParameterModifierScopes(parsedFile.path, block, nested, declaredMacros))
                }
                parameter.trailingParameters.forEach { nested ->
                    addAll(collectParameterModifierScopes(parsedFile.path, block, nested, declaredMacros))
                }
            }
        }
    }

    private fun collectParameterModifierScopes(
        file: Path,
        block: ImpexHeaderBlock,
        parameter: ImpexParameter,
        declaredMacros: Map<String, String>,
    ): List<ParameterModifierScope> {
        return buildList {
            add(
                ParameterModifierScope(
                    file = file,
                    block = block,
                    parameter = null,
                    modifiers = parameter.modifiers,
                    hasTranslator = parameter.modifiers.any { it.name.value == attributeModifierTranslator },
                    declaredMacros = declaredMacros,
                )
            )
            parameter.parameters.forEach { nested ->
                addAll(collectParameterModifierScopes(file, block, nested, declaredMacros))
            }
        }
    }

    private fun collectResolvedHeaderParameters(
        parsedFiles: List<ParsedImpexFile>,
        support: ImpexSupportContext,
    ): List<ResolvedHeaderParameterCandidate> {
        return parsedFiles.flatMap { file ->
            file.headerBlocks.flatMap { block ->
                val ownerTypes = normalizeTypeName(block.header.typeName.value)?.let(::setOf).orEmpty()
                block.header.parameters.mapNotNull { parameter ->
                    val resolvedAttributes = resolveHeaderParameterAttributes(parameter, ownerTypes, support)
                    if (resolvedAttributes.isEmpty()) {
                        null
                    } else {
                        ResolvedHeaderParameterCandidate(
                            file = file.path,
                            parameter = parameter,
                            resolvedAttributes = resolvedAttributes,
                        )
                    }
                }
            }
        }
    }

    private fun resolveHeaderParameterAttributes(
        parameter: ImpexHeaderParameter,
        ownerTypes: Set<String>,
        support: ImpexSupportContext,
    ): List<com.cci.sapcclint.catalog.AttributeRecord> {
        if (!parameter.name.kind.isAttributeLike()) {
            return emptyList()
        }
        return ownerTypes.flatMap { ownerType ->
            support.catalog.findAttributesByQualifier(ownerType, parameter.name.text, includeAncestors = true)
        }
    }

    private fun collectFunctionParameters(
        parsedFiles: List<ParsedImpexFile>,
        support: ImpexSupportContext,
    ): List<FunctionParameterCandidate> {
        return parsedFiles.flatMap { file ->
            file.headerBlocks.flatMap { block ->
                val headerType = normalizeTypeName(block.header.typeName.value)?.let(::setOf).orEmpty()
                block.header.parameters.flatMap { parameter ->
                    collectFunctionParameters(file.path, parameter, headerType, support)
                }
            }
        }
    }

    private fun collectFunctionParameters(
        file: Path,
        parameter: ImpexHeaderParameter,
        ownerTypes: Set<String>,
        support: ImpexSupportContext,
    ): List<FunctionParameterCandidate> {
        val resolution = resolveQualifier(parameter.name, ownerTypes, support)
        return parameter.leadingParameters.flatMap { nested ->
            collectFunctionParameters(file, nested, resolution.nextTypes, support, parameter.name.text)
        } + parameter.trailingParameters.flatMap { nested ->
            collectFunctionParameters(file, nested, resolution.nextTypes, support, parameter.name.text)
        }
    }

    private fun collectFunctionParameters(
        file: Path,
        parameter: ImpexParameter,
        ownerTypes: Set<String>,
        support: ImpexSupportContext,
        referenceName: String?,
    ): List<FunctionParameterCandidate> {
        val inlineTypeName = parameter.inlineTypeName(support)
        val ownCandidate = inlineTypeName?.let {
            FunctionParameterCandidate(
                file = file,
                parameter = parameter,
                expectedTypes = ownerTypes,
                referenceName = referenceName,
            )
        }
        val nestedOwnerTypes = inlineTypeName?.let(::setOf) ?: resolveQualifier(parameter.name, ownerTypes, support).nextTypes
        return listOfNotNull(ownCandidate) + parameter.parameters.flatMap { nested ->
            collectFunctionParameters(file, nested, nestedOwnerTypes, support, parameter.name.text)
        }
    }

    private fun resolveModifierValue(
        rawValue: String?,
        declaredMacros: Map<String, String>,
        support: ImpexSupportContext,
        depth: Int = 0,
    ): String? {
        if (rawValue == null || depth > 8) {
            return rawValue
        }
        val trimmed = rawValue.trim()
        return when {
            trimmed.startsWith(configMacroPrefix) -> {
                val propertyName = trimmed.removePrefix(configMacroPrefix)
                support.propertyIndex.valuesByKey[propertyName]?.firstOrNull()
            }

            trimmed.startsWith("$") -> {
                val resolved = declaredMacros[trimmed] ?: return trimmed
                resolveModifierValue(resolved, declaredMacros, support, depth + 1)
            }

            else -> trimmed
        }
    }

    private fun isUniqueHeaderParameter(parameter: ImpexHeaderParameter): Boolean {
        return parameter.modifiers.any { it.name.value == attributeModifierUnique && it.value.value == "true" }
    }

    private fun hasLangOrAppendModifier(parameter: ImpexHeaderParameter): Boolean {
        return parameter.modifiers.any { modifier ->
            modifier.name.value == attributeModifierLang ||
                (modifier.name.value == attributeModifierMode && modifier.value.value == "append")
        }
    }

    private fun splitModifierValueTokens(value: LocatedValue<String>): List<LocatedValue<String>> {
        val raw = value.value ?: return emptyList()
        return raw.split(',')
            .mapNotNull { token ->
                token.trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { LocatedValue(it, value.location) }
            }
    }

    private fun normalizeTypeName(typeName: String?): String? =
        typeName?.removePrefix("localized:")?.trim()?.takeIf { it.isNotEmpty() }

    private fun rule(ruleId: String): SimpleDomainRule = rules.first { it.ruleId == ruleId }

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

private data class ImpexSupportContext(
    val catalog: TypeSystemCatalog,
    val hasFullPlatformContext: Boolean,
    val localClassIndex: LocalClassIndex,
    val propertyIndex: PropertyIndex,
) {
    fun isResolvableType(typeName: String): Boolean {
        return typeName in knownPlatformItemRoots ||
            catalog.hasLocalDeclaredType(typeName)
    }

    fun shouldReportUnknownType(): Boolean {
        return hasFullPlatformContext
    }

    fun shouldReportUnknownAttribute(
        ownerTypes: Set<String>,
        confidentOwnerTypes: Set<String>,
    ): Boolean {
        return confidentOwnerTypes.isNotEmpty() ||
            (hasFullPlatformContext && ownerTypes.any(::isResolvableType))
    }

    fun canConfidentlyValidateAttributes(typeName: String): Boolean {
        if (hasFullPlatformContext && isResolvableType(typeName)) {
            return true
        }

        val records = catalog.findItemTypes(typeName)
        if (records.isEmpty()) {
            return false
        }

        return records.all { record ->
            var currentType = record.declaration.extendsType.value
            while (currentType != null) {
                if (currentType in knownPlatformItemRoots) {
                    break
                }
                if (!catalog.hasLocalDeclaredType(currentType)) {
                    return@all false
                }
                currentType = catalog.findItemTypes(currentType)
                    .firstOrNull()
                    ?.declaration
                    ?.extendsType
                    ?.value
            }
            true
        }
    }

    fun canConfidentlyValidateIndexes(typeName: String?): Boolean {
        if (typeName == null) {
            return false
        }
        val records = catalog.findItemTypes(typeName)
        if (records.isEmpty()) {
            return false
        }
        return records.all { record ->
            var currentType = record.declaration.extendsType.value
            while (currentType != null) {
                if (currentType in knownPlatformItemRoots) {
                    break
                }
                if (!catalog.hasLocalDeclaredType(currentType)) {
                    return@all false
                }
                currentType = catalog.findItemTypes(currentType)
                    .firstOrNull()
                    ?.declaration
                    ?.extendsType
                    ?.value
            }
            true
        }
    }

    fun classifierKind(typeName: String?): ClassifierKind {
        if (typeName == null) {
            return ClassifierKind.UNKNOWN
        }
        return when {
            typeName in knownPlatformItemRoots -> ClassifierKind.ITEM_ROOT
            catalog.findItemTypes(typeName).isNotEmpty() -> ClassifierKind.ITEM
            catalog.findEnumTypes(typeName).isNotEmpty() -> ClassifierKind.ENUM
            catalog.findCollectionTypes(typeName).isNotEmpty() -> ClassifierKind.COLLECTION
            catalog.findMapTypes(typeName).isNotEmpty() -> ClassifierKind.MAP
            catalog.findRelations(typeName).isNotEmpty() -> ClassifierKind.RELATION
            typeName in knownAtomicTypeNames -> ClassifierKind.ATOMIC
            else -> ClassifierKind.UNKNOWN
        }
    }
}

private data class QualifierResolution(
    val resolved: Boolean,
    val nextTypes: Set<String>,
    val confidentOwnerTypes: Set<String>,
)

private data class ParameterModifierScope(
    val file: Path,
    val block: ImpexHeaderBlock,
    val parameter: ImpexHeaderParameter?,
    val modifiers: List<ImpexModifier>,
    val hasTranslator: Boolean,
    val declaredMacros: Map<String, String>,
)

private data class ModifierCandidate(
    val file: Path,
    val modifier: ImpexModifier,
)

private data class MacroUsageCandidate(
    val file: Path,
    val reference: ImpexReference,
)

private data class ClassModifierRule(
    val rule: SimpleDomainRule,
    val modifierName: String,
    val targetTypes: Set<String>,
    val label: String,
)

private data class ResolvedHeaderParameterCandidate(
    val file: Path,
    val parameter: ImpexHeaderParameter,
    val resolvedAttributes: List<com.cci.sapcclint.catalog.AttributeRecord>,
)

private data class FunctionParameterCandidate(
    val file: Path,
    val parameter: ImpexParameter,
    val expectedTypes: Set<String>,
    val referenceName: String?,
)

private fun sameClassifierName(left: String, right: String): Boolean = left.compareTo(right, ignoreCase = true) == 0

private data class DuplicateHeaderKey(
    val typeName: String?,
    val keyParameters: List<String>,
)

private data class ValueRowContext(
    val block: ImpexHeaderBlock,
    val valueLine: com.cci.sapcclint.impex.model.ImpexValueLine,
    val columnValues: Map<String, com.cci.sapcclint.impex.model.ImpexValueGroup>,
)

private enum class ClassifierKind {
    ITEM,
    ITEM_ROOT,
    ENUM,
    COLLECTION,
    MAP,
    RELATION,
    ATOMIC,
    UNKNOWN,
}

private fun ImpexReferenceKind.isAttributeLike(): Boolean = this == ImpexReferenceKind.HEADER_PARAMETER || this == ImpexReferenceKind.FUNCTION

private val knownPlatformItemRoots = setOf(
    "Item",
    "GenericItem",
    "LocalizableItem",
    "ExtensibleItem",
    "Link",
    "EnumerationValue",
)

private val knownAtomicTypeNames = setOf(
    "java.lang.String",
    "String",
    "java.lang.Boolean",
    "Boolean",
    "boolean",
    "java.lang.Integer",
    "Integer",
    "int",
    "java.lang.Long",
    "Long",
    "long",
    "java.lang.Double",
    "Double",
    "double",
    "java.lang.Float",
    "Float",
    "float",
    "java.util.Date",
    "Date",
    "java.math.BigDecimal",
    "BigDecimal",
)

private val booleanModifierValues = setOf("true", "false")
private val macroReferenceRegex = Regex("""\$(?:config-)?[A-Za-z0-9_.()-]+""")

private const val typeModifierDisableUniqueAttributesValidatorForTypes = "disable.UniqueAttributesValidator.for.types"
private const val typeModifierDisableInterceptorTypes = "disable.interceptor.types"
private const val typeModifierProcessor = "processor"
private const val attributeModifierTranslator = "translator"
private const val attributeModifierCellDecorator = "cellDecorator"
private const val attributeModifierMode = "mode"
private const val attributeModifierLang = "lang"
private const val attributeModifierUnique = "unique"
private const val configMacroShortPrefix = "\$config"
private const val configMacroPrefix = "\$config-"
private const val configPropertyImportProcessor = "de.hybris.platform.commerceservices.impex.impl.ConfigPropertyImportProcessor"

private val allowedStaticEnumModes = setOf(
    com.cci.sapcclint.impex.model.ImpexHeaderMode.UPDATE,
    com.cci.sapcclint.impex.model.ImpexHeaderMode.REMOVE,
)

private val knownTypeModifierNames = setOf(
    typeModifierDisableUniqueAttributesValidatorForTypes,
    "disable.interceptor.beans",
    typeModifierDisableInterceptorTypes,
    "batchmode",
    "sld.enabled",
    "cacheUnique",
    "impex.legacy.mode",
    typeModifierProcessor,
)

private val knownAttributeModifierNames = setOf(
    "unique",
    "allownull",
    "forceWrite",
    "ignoreKeyCase",
    "ignorenull",
    "virtual",
    attributeModifierMode,
    "alias",
    "collection-delimiter",
    "dateformat",
    "default",
    "key2value-delimiter",
    "lang",
    "map-delimiter",
    "numberformat",
    "path-delimiter",
    "pos",
    attributeModifierCellDecorator,
    attributeModifierTranslator,
    "expr",
    "system",
    "version",
    "class",
)

private val booleanModifierNames = setOf(
    "unique",
    "allownull",
    "forceWrite",
    "ignoreKeyCase",
    "ignorenull",
    "virtual",
    "batchmode",
    "sld.enabled",
    "cacheUnique",
    "impex.legacy.mode",
)

private val allowedModeModifierValues = setOf("append", "merge", "remove")

private val allowedDisableInterceptorTypes = setOf(
    "validate",
    "prepare",
    "load",
    "remove",
    "init_defaults",
)

private fun ImpexParameter.inlineTypeName(support: ImpexSupportContext): String? {
    val candidate = name.text.takeIf { suffixReference != null } ?: return null
    return candidate.takeIf { support.isResolvableType(it) || support.shouldReportUnknownType() }
}

private fun <T, K, V> Iterable<T>.associateNotNullBy(
    keySelector: (T) -> K?,
    valueTransform: (T) -> V?,
): Map<K, V> {
    val result = linkedMapOf<K, V>()
    forEach { element ->
        val key = keySelector(element) ?: return@forEach
        val value = valueTransform(element) ?: return@forEach
        result[key] = value
    }
    return result
}
