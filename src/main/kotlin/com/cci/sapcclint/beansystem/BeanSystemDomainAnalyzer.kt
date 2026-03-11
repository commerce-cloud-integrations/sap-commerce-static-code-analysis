package com.cci.sapcclint.beansystem

import com.cci.sapcclint.beansystem.catalog.BeanSystemCatalog
import com.cci.sapcclint.beansystem.catalog.BeanSystemCatalogBuilder
import com.cci.sapcclint.beansystem.model.BeanPropertyDecl
import com.cci.sapcclint.beansystem.parser.BeansParser
import com.cci.sapcclint.beansystem.validation.BeanSystemStructuralValidator
import com.cci.sapcclint.config.AnalyzerConfig
import com.cci.sapcclint.core.AnalysisDomain
import com.cci.sapcclint.core.DomainAnalysisResult
import com.cci.sapcclint.core.DomainAnalyzer
import com.cci.sapcclint.core.RepositoryAnalysisContext
import com.cci.sapcclint.itemsxml.model.SourcePosition
import com.cci.sapcclint.rules.EffectiveRuleSettings
import com.cci.sapcclint.rules.Finding
import com.cci.sapcclint.rules.FindingLocation
import com.cci.sapcclint.rules.FindingSeverity
import com.cci.sapcclint.rules.RegisteredRule
import com.cci.sapcclint.rules.resolveRuleSettings
import java.nio.file.Path

class BeanSystemDomainAnalyzer(
    private val parser: BeansParser = BeansParser(),
    private val catalogBuilder: BeanSystemCatalogBuilder = BeanSystemCatalogBuilder(),
    private val structuralValidator: BeanSystemStructuralValidator = BeanSystemStructuralValidator(),
) : DomainAnalyzer {

    override val domain: AnalysisDomain = AnalysisDomain.BEAN_SYSTEM

    private val rules = listOf(
        BeanSystemRule("BSDomElementsInspection", FindingSeverity.ERROR),
        BeanSystemRule("BSDuplicateBeanDefinition", FindingSeverity.WARNING),
        BeanSystemRule("BSDuplicateEnumDefinition", FindingSeverity.WARNING),
        BeanSystemRule("BSDuplicateBeanPropertyDefinition", FindingSeverity.ERROR),
        BeanSystemRule("BSDuplicateEnumValueDefinition", FindingSeverity.ERROR),
        BeanSystemRule("BSKeywordIsNotAllowedAsBeanPropertyName", FindingSeverity.ERROR),
        BeanSystemRule("BSUnescapedLessThanSignIsNotAllowedInBeanPropertyType", FindingSeverity.ERROR),
        BeanSystemRule("BSUnescapedGreaterThanSignIsNotAllowedInBeanPropertyType", FindingSeverity.WARNING),
        BeanSystemRule("BSUnescapedGreaterLessThanSignIsNotAllowedInBeanClass", FindingSeverity.WARNING),
        BeanSystemRule("BSOmitJavaLangPackageInBeanPropertyType", FindingSeverity.WARNING),
    )

    override fun analyze(context: RepositoryAnalysisContext): DomainAnalysisResult {
        val files = context.scan.filesFor(domain)
        if (files.isEmpty()) {
            return DomainAnalysisResult(domain, 0, emptyList(), emptyList())
        }

        val parsedFiles = files.map(parser::parse)
        val catalog = catalogBuilder.build(parsedFiles)
        val findings = buildList {
            addAll(findStructuralIssues(files, context.config))
            addAll(findDuplicateBeanDefinitions(catalog, context.config))
            addAll(findDuplicateEnumDefinitions(catalog, context.config))
            addAll(findDuplicateBeanPropertyDefinitions(catalog, context.config))
            addAll(findDuplicateEnumValueDefinitions(catalog, context.config))
            addAll(findKeywordPropertyNames(catalog, context.config))
            addAll(findUnescapedLessThanInPropertyType(catalog, context.config))
            addAll(findUnescapedGreaterThanInPropertyType(catalog, context.config))
            addAll(findUnescapedSignsInBeanClass(catalog, context.config))
            addAll(findJavaLangPropertyTypes(catalog, context.config))
        }.sortedWith(compareBy({ it.location.file.toString() }, { it.location.position.line }, { it.ruleId }))

        return DomainAnalysisResult(
            domain = domain,
            analyzedFileCount = files.size,
            findings = findings,
            rules = rules,
        )
    }

    private fun findStructuralIssues(files: List<Path>, config: AnalyzerConfig): List<Finding> {
        val rule = rule("BSDomElementsInspection")
        val settings = settings(config, rule)
        if (!settings.enabled) {
            return emptyList()
        }

        return files.flatMap { file ->
            structuralValidator.validate(file).mapIndexed { index, issue ->
                finding(
                    file = file,
                    location = issue.location,
                    rule = rule,
                    settings = settings,
                    message = issue.message,
                    entityKey = "bean-dom:${file}:${issue.location.line}:${issue.location.column}:$index",
                )
            }
        }
    }

    private fun findDuplicateBeanDefinitions(catalog: BeanSystemCatalog, config: AnalyzerConfig): List<Finding> {
        val rule = rule("BSDuplicateBeanDefinition")
        val settings = settings(config, rule)
        if (!settings.enabled) {
            return emptyList()
        }

        return catalog.beansByClass.values.flatMap { records ->
            val sameFileGroups = records
                .filter { !it.declaration.clazz.value.isNullOrBlank() }
                .groupBy { it.file to shortBeanName(it.declaration.clazz.value).lowercase() }
                .values
                .filter { it.size > 1 }
            sameFileGroups.flatMap { group ->
                group.map { record ->
                    finding(
                        file = record.file,
                        location = record.declaration.clazz.location ?: record.declaration.location,
                        rule = rule,
                        settings = settings,
                        message = "Bean '${record.declaration.clazz.value}' is declared more than once in the same file.",
                        entityKey = "bean-definition:${record.file}:${record.declaration.clazz.value!!.lowercase()}",
                    )
                }
            }
        }
    }

    private fun findDuplicateEnumDefinitions(catalog: BeanSystemCatalog, config: AnalyzerConfig): List<Finding> {
        val rule = rule("BSDuplicateEnumDefinition")
        val settings = settings(config, rule)
        if (!settings.enabled) {
            return emptyList()
        }

        return catalog.enumsByClass.values.flatMap { records ->
            val sameFileGroups = records
                .filter { !it.declaration.clazz.value.isNullOrBlank() }
                .groupBy { it.file to it.declaration.clazz.value!!.lowercase() }
                .values
                .filter { it.size > 1 }
            sameFileGroups.flatMap { group ->
                group.map { record ->
                    finding(
                        file = record.file,
                        location = record.declaration.clazz.location ?: record.declaration.location,
                        rule = rule,
                        settings = settings,
                        message = "Enum '${record.declaration.clazz.value}' is declared more than once in the same file.",
                        entityKey = "bean-enum:${record.file}:${record.declaration.clazz.value!!.lowercase()}",
                    )
                }
            }
        }
    }

    private fun findDuplicateBeanPropertyDefinitions(catalog: BeanSystemCatalog, config: AnalyzerConfig): List<Finding> {
        val rule = rule("BSDuplicateBeanPropertyDefinition")
        val settings = settings(config, rule)
        if (!settings.enabled) {
            return emptyList()
        }

        return catalog.beansByClass.values.flatMap { beanRecords ->
            val declarationCountsByProperty = beanRecords
                .flatMap { record ->
                    record.declaration.properties
                        .mapNotNull { property -> property.name.value?.lowercase() }
                        .distinct()
                        .map { propertyName -> propertyName to record }
                }
                .groupBy({ it.first }, { it.second })

            beanRecords.flatMap { record ->
                val exactDuplicates = record.declaration.properties
                    .groupBy { it.name.value }
                    .filterValues { it.size > 1 }
                    .keys
                record.declaration.properties.mapNotNull { property ->
                    val propertyName = property.name.value ?: return@mapNotNull null
                    val duplicateAcrossDeclarations = (declarationCountsByProperty[propertyName.lowercase()]?.size ?: 0) > 1
                    if (!duplicateAcrossDeclarations && propertyName !in exactDuplicates) {
                        return@mapNotNull null
                    }
                    finding(
                        file = record.file,
                        location = property.name.location ?: property.location,
                        rule = rule,
                        settings = settings,
                        message = "Property '$propertyName' is declared more than once for bean '${record.declaration.clazz.value}'.",
                        entityKey = "bean-property:${record.file}:${record.declaration.clazz.value?.lowercase()}:${propertyName.lowercase()}",
                    )
                }
            }
        }
    }

    private fun findDuplicateEnumValueDefinitions(catalog: BeanSystemCatalog, config: AnalyzerConfig): List<Finding> {
        val rule = rule("BSDuplicateEnumValueDefinition")
        val settings = settings(config, rule)
        if (!settings.enabled) {
            return emptyList()
        }

        return catalog.enumsByClass.values.flatMap { enumRecords ->
            val declarationCountsByValue = enumRecords
                .flatMap { record ->
                    record.declaration.values
                        .mapNotNull { enumValue -> enumValue.value.value?.lowercase() }
                        .distinct()
                        .map { value -> value to record }
                }
                .groupBy({ it.first }, { it.second })
            enumRecords.flatMap { record ->
                record.declaration.values.mapNotNull { enumValue ->
                    val value = enumValue.value.value ?: return@mapNotNull null
                    if ((declarationCountsByValue[value.lowercase()]?.size ?: 0) <= 1) {
                        return@mapNotNull null
                    }
                    finding(
                        file = record.file,
                        location = enumValue.value.location ?: enumValue.location,
                        rule = rule,
                        settings = settings,
                        message = "Enum value '$value' is declared more than once for enum '${record.declaration.clazz.value}'.",
                        entityKey = "bean-enum-value:${record.file}:${record.declaration.clazz.value?.lowercase()}:${value.lowercase()}",
                    )
                }
            }
        }
    }

    private fun findKeywordPropertyNames(catalog: BeanSystemCatalog, config: AnalyzerConfig): List<Finding> {
        val rule = rule("BSKeywordIsNotAllowedAsBeanPropertyName")
        val settings = settings(config, rule)
        if (!settings.enabled) {
            return emptyList()
        }

        return catalog.beansByClass.values.flatMap { beanRecords ->
            beanRecords.flatMap { record ->
                record.declaration.properties.mapNotNull { property ->
                    val propertyName = property.name.value ?: return@mapNotNull null
                    if (propertyName !in javaKeywords) {
                        return@mapNotNull null
                    }
                    finding(
                        file = record.file,
                        location = property.name.location ?: property.location,
                        rule = rule,
                        settings = settings,
                        message = "Java keyword '$propertyName' cannot be used as a bean property name.",
                        entityKey = "bean-keyword:${record.file}:${propertyName.lowercase()}",
                    )
                }
            }
        }
    }

    private fun findUnescapedLessThanInPropertyType(catalog: BeanSystemCatalog, config: AnalyzerConfig): List<Finding> {
        val rule = rule("BSUnescapedLessThanSignIsNotAllowedInBeanPropertyType")
        val settings = settings(config, rule)
        if (!settings.enabled) {
            return emptyList()
        }

        return propertyFindings(catalog, rule, settings) { property ->
            property.type.rawValue?.contains('<') == true
        }
    }

    private fun findUnescapedGreaterThanInPropertyType(catalog: BeanSystemCatalog, config: AnalyzerConfig): List<Finding> {
        val rule = rule("BSUnescapedGreaterThanSignIsNotAllowedInBeanPropertyType")
        val settings = settings(config, rule)
        if (!settings.enabled) {
            return emptyList()
        }

        return propertyFindings(catalog, rule, settings) { property ->
            property.type.rawValue?.contains('>') == true
        }
    }

    private fun findUnescapedSignsInBeanClass(catalog: BeanSystemCatalog, config: AnalyzerConfig): List<Finding> {
        val rule = rule("BSUnescapedGreaterLessThanSignIsNotAllowedInBeanClass")
        val settings = settings(config, rule)
        if (!settings.enabled) {
            return emptyList()
        }

        return catalog.beansByClass.values.flatMap { beanRecords ->
            beanRecords.mapNotNull { record ->
                val clazz = record.declaration.clazz
                if (clazz.rawValue?.contains('>') != true) {
                    return@mapNotNull null
                }
                finding(
                    file = record.file,
                    location = clazz.location ?: record.declaration.location,
                    rule = rule,
                    settings = settings,
                    message = "Unescaped '>' or '<' usage is not allowed in bean class '${shortBeanName(clazz.value)}'.",
                    entityKey = "bean-class-generic:${record.file}:${clazz.value?.lowercase()}",
                )
            }
        }
    }

    private fun findJavaLangPropertyTypes(catalog: BeanSystemCatalog, config: AnalyzerConfig): List<Finding> {
        val rule = rule("BSOmitJavaLangPackageInBeanPropertyType")
        val settings = settings(config, rule)
        if (!settings.enabled) {
            return emptyList()
        }

        return propertyFindings(catalog, rule, settings) { property ->
            property.type.value?.contains(javaLangPrefix) == true
        }
    }

    private fun propertyFindings(
        catalog: BeanSystemCatalog,
        rule: BeanSystemRule,
        settings: EffectiveRuleSettings,
        predicate: (BeanPropertyDecl) -> Boolean,
    ): List<Finding> {
        return catalog.beansByClass.values.flatMap { beanRecords ->
            beanRecords.flatMap { record ->
                record.declaration.properties.mapNotNull { property ->
                    if (!predicate(property)) {
                        return@mapNotNull null
                    }
                    finding(
                        file = record.file,
                        location = property.type.location ?: property.location,
                        rule = rule,
                        settings = settings,
                        message = when (rule.ruleId) {
                            "BSUnescapedLessThanSignIsNotAllowedInBeanPropertyType" ->
                                "Unescaped '<' usage is not allowed in bean property type '${property.name.value}'."
                            "BSUnescapedGreaterThanSignIsNotAllowedInBeanPropertyType" ->
                                "Unescaped '>' usage is not allowed in bean property type '${property.name.value}'."
                            "BSOmitJavaLangPackageInBeanPropertyType" ->
                                "'java.lang' package can be omitted in bean property type '${property.name.value}'."
                            else -> rule.ruleId
                        },
                        entityKey = "bean-property-type:${record.file}:${rule.ruleId}:${property.name.value?.lowercase()}",
                    )
                }
            }
        }
    }

    private fun shortBeanName(clazz: String?): String = clazz?.substringBefore('<').orEmpty()

    private fun rule(ruleId: String): BeanSystemRule = rules.first { it.ruleId == ruleId }

    private fun settings(config: AnalyzerConfig, rule: BeanSystemRule): EffectiveRuleSettings {
        return resolveRuleSettings(config, rule.ruleId, rule.defaultSeverity)
    }

    private fun finding(
        file: Path,
        location: SourcePosition,
        rule: BeanSystemRule,
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

private data class BeanSystemRule(
    override val ruleId: String,
    override val defaultSeverity: FindingSeverity,
    override val domain: AnalysisDomain = AnalysisDomain.BEAN_SYSTEM,
) : RegisteredRule

private val javaKeywords = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
    "const", "continue", "default", "do", "double", "else", "enum", "extends", "false",
    "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
    "int", "interface", "long", "native", "new", "null", "package", "private", "protected",
    "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
    "this", "throw", "throws", "transient", "true", "try", "void", "volatile", "while",
    "permits", "_", "provides", "uses", "opens", "open", "requires", "exports", "module",
    "yield", "with", "var", "transitive", "to", "record", "non-sealed", "sealed",
)

private const val javaLangPrefix = "java.lang."
