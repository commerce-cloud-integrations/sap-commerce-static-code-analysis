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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImpexSemanticRulesTest {

    private val scanner = RepositoryScanner()
    private val analyzer = ImpexDomainAnalyzer()

    @Test
    fun analyze_whenLangModifierUsesUnsupportedLanguage_reportsFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-lang-unsupported")
        repo.resolve("config/local.properties").apply {
            parent.createDirectories()
            writeText("lang.packs=de,fr")
        }
        writeItemsFile(
            repo,
            """
            <items>
              <itemtypes>
                <itemtype code="DemoItem" extends="GenericItem">
                  <deployment table="DemoItem" typecode="12000"/>
                  <attributes>
                    <attribute qualifier="name" type="localized:java.lang.String"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        writeImpex(
            repo,
            """
            INSERT DemoItem;name[lang=es]
            ;hola
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpexLanguageIsNotSupportedInspection" && it.message.contains("es") })
    }

    @Test
    fun analyze_whenLangModifierTargetsNonLocalizedAttribute_reportsFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-lang-not-allowed")
        writeItemsFile(
            repo,
            """
            <items>
              <itemtypes>
                <itemtype code="DemoItem" extends="GenericItem">
                  <deployment table="DemoItem" typecode="12000"/>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        writeImpex(
            repo,
            """
            INSERT DemoItem;code[lang=de]
            ;demo
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpexLanguageModifierIsNotAllowedInspection" && it.message.contains("code") })
    }

    @Test
    fun analyze_whenFunctionInlineTypeIsUnknown_reportsFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-function-unknown")
        repo.resolve("bin/platform").createDirectories()
        writeItemsFile(
            repo,
            """
            <items>
              <itemtypes>
                <itemtype code="OwnerItem" extends="GenericItem">
                  <deployment table="OwnerItem" typecode="12001"/>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String"/>
                  </attributes>
                </itemtype>
                <itemtype code="DemoItem" extends="GenericItem">
                  <deployment table="DemoItem" typecode="12002"/>
                  <attributes>
                    <attribute qualifier="owner" type="OwnerItem"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        writeImpex(
            repo,
            """
            INSERT DemoItem;owner(MissingOwner.code)
            ;missing
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpexUnknownFunctionTypeInspection" && it.message.contains("MissingOwner") })
    }

    @Test
    fun analyze_whenFunctionInlineTypeDoesNotMatchExpectedOwnerType_reportsFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-function-mismatch")
        writeItemsFile(
            repo,
            """
            <items>
              <itemtypes>
                <itemtype code="BaseOwner" extends="GenericItem">
                  <deployment table="BaseOwner" typecode="12001"/>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String"/>
                  </attributes>
                </itemtype>
                <itemtype code="GoodOwner" extends="BaseOwner">
                  <attributes>
                    <attribute qualifier="extra" type="java.lang.String"/>
                  </attributes>
                </itemtype>
                <itemtype code="OtherOwner" extends="GenericItem">
                  <deployment table="OtherOwner" typecode="12003"/>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String"/>
                  </attributes>
                </itemtype>
                <itemtype code="DemoItem" extends="GenericItem">
                  <deployment table="DemoItem" typecode="12004"/>
                  <attributes>
                    <attribute qualifier="owner" type="BaseOwner"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        writeImpex(
            repo,
            """
            INSERT DemoItem;owner(OtherOwner.code)
            ;other
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpexFunctionReferenceTypeMismatchInspection" && it.message.contains("OtherOwner") })
    }

    @Test
    fun analyze_whenUniqueHeaderAttributeHasNoIndex_reportsFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-unique-index")
        writeItemsFile(
            repo,
            """
            <items>
              <itemtypes>
                <itemtype code="DemoItem" extends="GenericItem">
                  <deployment table="DemoItem" typecode="12000"/>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        writeImpex(
            repo,
            """
            INSERT_UPDATE DemoItem;code[unique=true]
            ;demo
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpexUniqueAttributeWithoutIndex" && it.message.contains("code") })
    }

    @Test
    fun analyze_whenDocumentIdValuesRepeat_reportsFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-doc-id")
        writeImpex(
            repo,
            """
            INSERT DemoItem;&ref;code
            ;item1;first
            ;item1;second
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpexUniqueDocumentId" && it.message.contains("&ref") })
    }

    @Test
    fun analyze_whenDuplicateUniqueRowsOverrideValues_reportsWarning() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-duplicate-override")
        writeImpex(
            repo,
            """
            INSERT_UPDATE DemoItem;code[unique=true];name
            ;demo;first
            ;demo;second
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpexNoUniqueValueInspection" && it.message.contains("overrides") })
    }

    @Test
    fun analyze_whenDuplicateUniqueRowsUseReorderedHeaders_stillReportsOverrideWarning() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-reordered-headers")
        writeImpex(
            repo,
            """
            INSERT_UPDATE DemoItem;code[unique=true];name
            ;demo;first

            INSERT_UPDATE DemoItem;name;code[unique=true]
            ;second;demo
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpexNoUniqueValueInspection" && it.message.contains("overrides") })
    }

    @Test
    fun analyze_whenDuplicateRowsUseReorderedMultiColumnUniqueKeys_stillReportsOverrideWarning() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-reordered-multi-key")
        writeImpex(
            repo,
            """
            INSERT_UPDATE DemoItem;code[unique=true];catalogVersion[unique=true];name
            ;demo;staged;first

            INSERT_UPDATE DemoItem;catalogVersion[unique=true];code[unique=true];name
            ;staged;demo;second
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpexNoUniqueValueInspection" && it.message.contains("overrides") })
    }

    @Test
    fun analyze_whenStaticEnumUsesInsert_reportsFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-static-enum")
        writeItemsFile(
            repo,
            """
            <items>
              <enumtypes>
                <enumtype code="ApprovalStatus" dynamic="false">
                  <value code="PENDING"/>
                </enumtype>
              </enumtypes>
            </items>
            """.trimIndent()
        )
        writeImpex(
            repo,
            """
            INSERT ApprovalStatus;code[unique=true]
            ;PENDING
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "ImpexOnlyUpdateAllowedForNonDynamicEnumInspection" && it.message.contains("ApprovalStatus") })
    }

    @Test
    fun analyze_whenFunctionInlineSubtypeMatchesExpectedOwnerType_skipsMismatchFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-function-valid")
        writeItemsFile(
            repo,
            """
            <items>
              <itemtypes>
                <itemtype code="BaseOwner" extends="GenericItem">
                  <deployment table="BaseOwner" typecode="12001"/>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String"/>
                  </attributes>
                </itemtype>
                <itemtype code="GoodOwner" extends="BaseOwner">
                  <attributes>
                    <attribute qualifier="extra" type="java.lang.String"/>
                  </attributes>
                </itemtype>
                <itemtype code="DemoItem" extends="GenericItem">
                  <deployment table="DemoItem" typecode="12004"/>
                  <attributes>
                    <attribute qualifier="owner" type="BaseOwner"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        writeImpex(
            repo,
            """
            INSERT DemoItem;owner(GoodOwner.code)
            ;good
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertFalse(findings.any { it.ruleId == "ImpexFunctionReferenceTypeMismatchInspection" })
    }

    @Test
    fun analyze_whenInheritedIndexesDependOnMissingPlatformAncestor_skipsUniqueIndexFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-impex-partial-index")
        repo.resolve("bin/platform").createDirectories()
        writeItemsFile(
            repo,
            """
            <items>
              <itemtypes>
                <itemtype code="CustomProduct" extends="Product">
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        writeImpex(
            repo,
            """
            INSERT_UPDATE CustomProduct;code[unique=true]
            ;demo
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertFalse(findings.any { it.ruleId == "ImpexUniqueAttributeWithoutIndex" })
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

    private fun writeImpex(repo: Path, content: String) {
        repo.resolve("resources/projectdata.impex").apply {
            parent.createDirectories()
            writeText(content)
        }
    }
}
