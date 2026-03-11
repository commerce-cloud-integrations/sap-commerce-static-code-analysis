package ai.nina.labs.sapcclint.impex

import ai.nina.labs.sapcclint.config.AnalyzerConfig
import ai.nina.labs.sapcclint.core.AnalysisDomain
import ai.nina.labs.sapcclint.core.RepositoryAnalysisContext
import ai.nina.labs.sapcclint.rules.Finding
import ai.nina.labs.sapcclint.scanner.RepositoryScanner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImpexMacroAndValueGroupRulesTest {

    private val scanner = RepositoryScanner()
    private val analyzer = ImpexDomainAnalyzer()

    @Test
    fun analyze_whenMacroUsagesAreUnknown_reportsUnknownMacroFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-macro-unknown")
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                ${'$'}defined=demo
                ${'$'}alias=${'$'}missing
                INSERT Product;code[default=${'$'}missingInModifier]
                ;demo
                """.trimIndent()
            )
        }

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpExUnknownMacrosInspection" && it.message.contains("${'$'}missing") })
        assertTrue(findings.any { it.ruleId == "ImpExUnknownMacrosInspection" && it.message.contains("${'$'}missingInModifier") })
    }

    @Test
    fun analyze_whenConfigPropertiesAreUnknown_reportsUsageAndDeclarationFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-config-property")
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                ${'$'}config-site.id=site.missing
                ${'$'}site=${'$'}config-missing.property
                INSERT Product;code[default=${'$'}config-another.missing]
                ;demo
                """.trimIndent()
            )
        }

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpExUnknownConfigPropertyInspection" && it.message.contains("site.missing") })
        assertTrue(findings.any { it.ruleId == "ImpExUnknownConfigPropertyInspection" && it.message.contains("missing.property") })
        assertTrue(findings.any { it.ruleId == "ImpExUnknownConfigPropertyInspection" && it.message.contains("another.missing") })
    }

    @Test
    fun analyze_whenConfigMacroExistsWithoutConfigProcessor_reportsFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-config-processor")
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                ${'$'}site=${'$'}config-site.id
                INSERT Product;code
                ;demo
                """.trimIndent()
            )
        }

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpExConfigProcessorInspection" })
    }

    @Test
    fun analyze_whenDirectConfigUsageExistsWithoutProcessor_reportsFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-direct-config")
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                INSERT Product;code[default=${'$'}config-site.id]
                ;demo
                """.trimIndent()
            )
        }

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpExConfigProcessorInspection" })
    }

    @Test
    fun analyze_whenHeaderAbbreviationRequiresMissingMacros_reportsFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-header-abbreviation")
        repo.resolve("config/project.properties").apply {
            parent.createDirectories()
            writeText("impex.header.replacement.catalog=^catalog$...catalogVersion(catalog(id),version)[default=${'$'}catalogVersion]")
        }
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                INSERT Product;catalog
                ;demo
                """.trimIndent()
            )
        }

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpExIncompleteHeaderAbbreviationUsageInspection" && it.message.contains("${'$'}catalogVersion") })
    }

    @Test
    fun analyze_whenHeaderParameterSeparatorsSkipNames_reportsFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-missing-header-parameter")
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                INSERT Product;catalogVersion(catalog(id),,version,);code
                ;demo
                """.trimIndent()
            )
        }

        val findings = analyze(repo).filter { it.ruleId == "ImpExMissingHeaderParameterInspection" }

        assertTrue(findings.size == 2)
        assertTrue(findings.all { it.message.contains("missing a following parameter name") })
    }

    @Test
    fun analyze_whenLeadingParameterSeparatorStillHasFollowingName_skipsFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-leading-header-parameter")
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                INSERT Product;catalogVersion(,version);code
                ;demo
                """.trimIndent()
            )
        }

        val findings = analyze(repo)

        assertFalse(findings.any { it.ruleId == "ImpExMissingHeaderParameterInspection" })
    }

    @Test
    fun analyze_whenValueLineHasMissingAndOrphanGroups_reportsBothRules() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-value-groups")
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                INSERT Product;code;name
                ;demo
                ;demo;name;extra
                """.trimIndent()
            )
        }

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpExMissingValueGroupInspection" })
        assertTrue(findings.any { it.ruleId == "ImpExOrphanValueGroupInspection" })
    }

    @Test
    fun analyze_whenMacroNameIsMultiline_reportsWarning() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-multiline-macro")
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                ${'$'}prod\
                 uct=demo
                INSERT Product;code
                ;demo
                """.trimIndent()
            )
        }

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpExMultilineMacroNameInspection" })
    }

    @Test
    fun analyze_whenConfigPropertyAndProcessorArePresent_skipsThoseFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-config-valid")
        repo.resolve("config/local.properties").apply {
            parent.createDirectories()
            writeText("site.id=electronics")
        }
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                UPDATE GenericItem[processor=de.hybris.platform.commerceservices.impex.impl.ConfigPropertyImportProcessor];pk[unique=true]
                ;1

                ${'$'}site=${'$'}config-site.id
                INSERT Product;code[default=${'$'}config-site.id]
                ;demo
                """.trimIndent()
            )
        }

        val findings = analyze(repo)

        assertFalse(findings.any { it.ruleId == "ImpExUnknownConfigPropertyInspection" })
        assertFalse(findings.any { it.ruleId == "ImpExConfigProcessorInspection" })
    }

    @Test
    fun analyze_whenProcessorClassNameOnlyAppearsInMacroValue_stillRequiresProcessorModifier() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-config-false-negative")
        repo.resolve("config/local.properties").apply {
            parent.createDirectories()
            writeText("site.id=electronics")
        }
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                ${'$'}processorName=de.hybris.platform.commerceservices.impex.impl.ConfigPropertyImportProcessor
                INSERT Product;code[default=${'$'}config-site.id]
                ;demo
                """.trimIndent()
            )
        }

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpExConfigProcessorInspection" })
    }

    private fun analyze(repo: Path): List<Finding> {
        val config = AnalyzerConfig()
        val scan = scanner.scan(repo, config, requestedDomains = setOf(AnalysisDomain.IMPEX))
        return analyzer.analyze(
            RepositoryAnalysisContext(
                repo = repo,
                config = config,
                scan = scan,
            )
        ).findings
    }
}
