package com.cci.sapcclint.impex

import com.cci.sapcclint.config.AnalyzerConfig
import com.cci.sapcclint.core.AnalysisDomain
import com.cci.sapcclint.core.RepositoryAnalysisContext
import com.cci.sapcclint.rules.Finding
import com.cci.sapcclint.scanner.RepositoryScanner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImpexDomainAnalyzerTest {

    private val scanner = RepositoryScanner()
    private val analyzer = ImpexDomainAnalyzer()

    @Test
    fun analyze_whenRepositoryIsPartial_skipsUnknownTypeFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-partial")
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                INSERT Product;code[unique=true]
                ;demo
                """.trimIndent()
            )
        }

        val findings = analyze(repo)

        assertTrue(findings.isEmpty())
    }

    @Test
    fun analyze_whenPlatformContextIsPresent_reportsUnknownTypeInHeaderAndModifier() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-unknown-type")
        repo.resolve("bin/platform").createDirectories()
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                INSERT MissingType[disable.UniqueAttributesValidator.for.types=AnotherMissing];code
                ;demo
                """.trimIndent()
            )
        }

        val findings = analyze(repo)

        assertEquals(
            listOf("ImpExUnknownTypeNameInspection", "ImpExUnknownTypeNameInspection"),
            findings.map { it.ruleId }.sorted()
        )
        assertTrue(findings.any { it.message.contains("MissingType") })
        assertTrue(findings.any { it.message.contains("AnotherMissing") })
    }

    @Test
    fun analyze_whenAttributesDoNotResolve_reportsRootAndNestedAttributeFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-unknown-attr")
        writeItemsFile(
            repo,
            """
            <items>
              <itemtypes>
                <itemtype code="OwnerItem" extends="GenericItem">
                  <deployment table="OwnerItem" typecode="10001"/>
                  <attributes>
                    <attribute qualifier="uid" type="java.lang.String"/>
                  </attributes>
                </itemtype>
                <itemtype code="DemoItem" extends="GenericItem">
                  <deployment table="DemoItem" typecode="10002"/>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String"/>
                    <attribute qualifier="owner" type="OwnerItem"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                INSERT DemoItem;code;missing;owner(uid);owner(missingNested)
                ;demo;;;
                """.trimIndent()
            )
        }

        val findings = analyze(repo).filter { it.ruleId == "ImpExUnknownTypeAttributeInspection" }

        assertEquals(2, findings.size)
        assertTrue(findings.any { it.message.contains("missing") && it.message.contains("DemoItem") })
        assertTrue(findings.any { it.message.contains("missingNested") && it.message.contains("OwnerItem") })
    }

    @Test
    fun analyze_whenPlatformTypeIsExtendedLocally_skipsUnknownAttributeFindingsForThatType() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-platform-overlay")
        repo.resolve("bin/platform").createDirectories()
        writeItemsFile(
            repo,
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem" autocreate="false" generate="false">
                  <attributes>
                    <attribute qualifier="customFlag" type="java.lang.String"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                INSERT Product;code;customFlag
                ;demo;flag
                """.trimIndent()
            )
        }

        val findings = analyze(repo).filter { it.ruleId == "ImpExUnknownTypeAttributeInspection" }

        assertTrue(findings.isEmpty())
    }

    @Test
    fun analyze_whenModifiersAreUnknown_respectsTranslatorEscapeHatch() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-unknown-modifiers")
        writeItemsFile(
            repo,
            """
            <items>
              <itemtypes>
                <itemtype code="DemoItem" extends="GenericItem">
                  <deployment table="DemoItem" typecode="10002"/>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String"/>
                    <attribute qualifier="name" type="java.lang.String"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                INSERT DemoItem[badHeaderModifier=true];code[badAttrModifier=true];name[translator=com.example.MyTranslator,badAttrModifier=true]
                ;demo;;
                """.trimIndent()
            )
        }

        val findings = analyze(repo)
        val attributeModifierFindings = findings.filter { it.ruleId == "ImpExUnknownAttributeModifierInspection" }

        assertTrue(findings.any { it.ruleId == "ImpExUnknownTypeModifierInspection" && it.message.contains("badHeaderModifier") })
        assertEquals(1, attributeModifierFindings.size)
        assertTrue(attributeModifierFindings.single().message.contains("badAttrModifier"))
    }

    @Test
    fun analyze_whenModifierValuesAreInvalid_reportsDeterministicValueErrors() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-invalid-values")
        writeItemsFile(
            repo,
            """
            <items>
              <itemtypes>
                <itemtype code="DemoItem" extends="GenericItem">
                  <deployment table="DemoItem" typecode="10002"/>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                INSERT DemoItem[batchmode=yes,disable.interceptor.types=oops];code[unique=yes,mode=replace]
                ;demo
                """.trimIndent()
            )
        }

        val findings = analyze(repo)
        val ruleIds = findings.map { it.ruleId }

        assertEquals(4, findings.size)
        assertTrue("ImpExInvalidBooleanModifierValueInspection" in ruleIds)
        assertTrue("ImpExInvalidModeModifierValueInspection" in ruleIds)
        assertTrue("ImpExInvalidDisableInterceptorTypesModifierValueInspection" in ruleIds)
    }

    @Test
    fun analyze_whenClassModifierTargetsResolveLocally_reportsInvalidImplementations() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-invalid-classes")
        writeItemsFile(
            repo,
            """
            <items>
              <itemtypes>
                <itemtype code="DemoItem" extends="GenericItem">
                  <deployment table="DemoItem" typecode="10002"/>
                  <attributes>
                    <attribute qualifier="name" type="java.lang.String"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        repo.resolve("src/com/example/WrongProcessor.java").apply {
            parent.createDirectories()
            writeText(
                """
                package com.example;
                public class WrongProcessor {}
                """.trimIndent()
            )
        }
        repo.resolve("src/com/example/WrongTranslator.java").apply {
            parent.createDirectories()
            writeText(
                """
                package com.example;
                public class WrongTranslator {}
                """.trimIndent()
            )
        }
        repo.resolve("src/com/example/WrongDecorator.java").apply {
            parent.createDirectories()
            writeText(
                """
                package com.example;
                public class WrongDecorator {}
                """.trimIndent()
            )
        }
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                INSERT DemoItem[processor=com.example.WrongProcessor];name[translator=com.example.WrongTranslator,cellDecorator=com.example.WrongDecorator]
                ;demo;
                """.trimIndent()
            )
        }

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpExInvalidProcessorValueInspection" })
        assertTrue(findings.any { it.ruleId == "ImpExInvalidTranslatorValueInspection" })
        assertTrue(findings.any { it.ruleId == "ImpExInvalidCellDecoratorValueInspection" })
    }

    @Test
    fun analyze_whenClassModifierCannotBeResolvedLocally_skipsTheFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-unresolved-class")
        repo.resolve("bin/platform").createDirectories()
        writeItemsFile(
            repo,
            """
            <items>
              <itemtypes>
                <itemtype code="DemoItem" extends="GenericItem">
                  <deployment table="DemoItem" typecode="10002"/>
                  <attributes>
                    <attribute qualifier="name" type="java.lang.String"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(
                """
                INSERT DemoItem;name[translator=de.hybris.platform.impex.jalo.translators.SpecialValueTranslator]
                ;demo;
                """.trimIndent()
            )
        }

        val findings = analyze(repo)

        assertFalse(findings.any { it.ruleId == "ImpExInvalidTranslatorValueInspection" })
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

    private fun writeItemsFile(repo: Path, content: String) {
        repo.resolve("custom/core/resources/custom-items.xml").apply {
            parent.createDirectories()
            writeText(content)
        }
    }
}
