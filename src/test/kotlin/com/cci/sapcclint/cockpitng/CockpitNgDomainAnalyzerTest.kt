package com.cci.sapcclint.cockpitng

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
import kotlin.test.assertTrue

class CockpitNgDomainAnalyzerTest {

    private val scanner = RepositoryScanner()
    private val analyzer = CockpitNgDomainAnalyzer()

    @Test
    fun analyze_whenContextsUseInvalidMergeKeysAndParents_reportsSemanticFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-cng-contexts")
        writeItems(
            repo,
            """
                <items>
                  <itemtypes>
                    <itemtype code="Product" extends="GenericItem"/>
                    <itemtype code="ApparelProduct" extends="Product"/>
                  </itemtypes>
                  <enumtypes>
                    <enumtype code="DemoEnum">
                      <value code="ONE"/>
                    </enumtype>
                  </enumtypes>
                </items>
            """.trimIndent()
        )
        writeConfig(
            repo,
            """
                <config xmlns="http://www.hybris.com/cockpit/config">
                  <context merge-by="module" parent="dashboard"/>
                  <context principal="admin"/>
                  <context merge-by="principal" parent="manager"/>
                  <context merge-by="type" type="ApparelProduct" parent="CatalogAwareItem"/>
                  <context merge-by="type" type="UnknownType" parent="Product"/>
                  <context merge-by="type" type="DemoEnum" parent="EnumerationValue"/>
                </config>
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "CngContextMergeByPointToExistingContextAttribute" && it.message.contains("module") })
        assertTrue(findings.any { it.ruleId == "CngContextParentIsNotValid" && it.message.contains("manager") })
        assertTrue(findings.any { it.ruleId == "CngContextMergeByTypeParentIsNotValid" && it.message.contains("CatalogAwareItem") })
        assertEquals(1, findings.count { it.ruleId == "CngContextMergeByTypeParentIsNotValid" })
    }

    @Test
    fun analyze_whenConfigNamespacesAreDuplicatedOrLocal_reportsNamespaceFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-cng-namespaces")
        writeConfig(
            repo,
            """
                <config xmlns="http://www.hybris.com/cockpit/config" xmlns:a="urn:dup" xmlns:b="urn:dup">
                  <context principal="admin"/>
                  <a:section xmlns:a="urn:dup"/>
                  <c:section xmlns:c="urn:new"/>
                  <a:other xmlns:a="urn:other"/>
                </config>
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertEquals(2, findings.count { it.ruleId == "CngDuplicateNamespace" })
        assertEquals(2, findings.count { it.ruleId == "CngNamespaceNotOptimized" })
    }

    @Test
    fun analyze_whenTypeAncestryLeavesTheRepo_skipsTypeParentFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-cng-partial-type")
        writeItems(
            repo,
            """
                <items>
                  <itemtypes>
                    <itemtype code="ApparelProduct" extends="Product"/>
                  </itemtypes>
                </items>
            """.trimIndent()
        )
        writeConfig(
            repo,
            """
                <config xmlns="http://www.hybris.com/cockpit/config">
                  <context merge-by="type" type="ApparelProduct" parent="CatalogAwareItem"/>
                </config>
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertEquals(0, findings.count { it.ruleId == "CngContextMergeByTypeParentIsNotValid" })
    }

    @Test
    fun analyze_whenConfigAndEmbeddedActionsHaveStructuralProblems_reportsDomFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-cng-config-dom")
        writeConfig(
            repo,
            """
                <config xmlns="http://www.hybris.com/cockpit/config" unexpected="x">
                  <requires/>
                  <context bogus="x">
                    <bogus/>
                    <actions xmlns="http://www.hybris.com/cockpit/config/hybris">
                      <bad-group/>
                      <group>
                        <unexpected/>
                        <action>
                          <parameter>
                            <name>code</name>
                          </parameter>
                        </action>
                      </group>
                    </actions>
                  </context>
                  <oops/>
                </config>
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "CngConfigDomElementsInspection" && it.message.contains("Attribute 'unexpected' is not allowed on <config>") })
        assertTrue(findings.any { it.ruleId == "CngConfigDomElementsInspection" && it.message.contains("Attribute 'resource' is required on <requires>") })
        assertTrue(findings.any { it.ruleId == "CngConfigDomElementsInspection" && it.message.contains("Attribute 'bogus' is not allowed on <context>") })
        assertTrue(findings.any { it.ruleId == "CngConfigDomElementsInspection" && it.message.contains("Element <bogus> is not allowed inside <context>") })
        assertTrue(findings.any { it.ruleId == "CngConfigDomElementsInspection" && it.message.contains("Element <oops> is not allowed inside <config>") })
        assertTrue(findings.any { it.ruleId == "CngActionsDomElementsInspection" && it.message.contains("Element <bad-group> is not allowed inside <actions>") })
        assertTrue(findings.any { it.ruleId == "CngActionsDomElementsInspection" && it.message.contains("Element <unexpected> is not allowed inside <group>") })
        assertTrue(findings.any { it.ruleId == "CngActionsDomElementsInspection" && it.message.contains("Element <parameter> requires a <value> child") })
    }

    @Test
    fun analyze_whenWidgetsFileHasStructuralProblems_reportsWidgetsDomFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-cng-widgets-dom")
        writeWidgets(
            repo,
            """
                <widgets unexpected="x">
                  <widget widgetDefinitionId="editorArea">
                    <bad/>
                  </widget>
                  <widget-extension>
                    <oops/>
                  </widget-extension>
                  <widget-connection name="demo"/>
                </widgets>
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "CngWidgetsDomElementsInspection" && it.message.contains("Attribute 'unexpected' is not allowed on <widgets>") })
        assertTrue(findings.any { it.ruleId == "CngWidgetsDomElementsInspection" && it.message.contains("Attribute 'id' is required on <widget>") })
        assertTrue(findings.any { it.ruleId == "CngWidgetsDomElementsInspection" && it.message.contains("Element <bad> is not allowed inside <widget>") })
        assertTrue(findings.any { it.ruleId == "CngWidgetsDomElementsInspection" && it.message.contains("Attribute 'widgetId' is required on <widget-extension>") })
        assertTrue(findings.any { it.ruleId == "CngWidgetsDomElementsInspection" && it.message.contains("Element <oops> is not allowed inside <widget-extension>") })
        assertTrue(findings.any { it.ruleId == "CngWidgetsDomElementsInspection" && it.message.contains("Attribute 'sourceWidgetId' is required on <widget-connection>") })
        assertTrue(findings.any { it.ruleId == "CngWidgetsDomElementsInspection" && it.message.contains("Attribute 'inputId' is required on <widget-connection>") })
    }

    private fun analyze(repo: Path): List<Finding> {
        val config = AnalyzerConfig()
        val scan = scanner.scan(repo, config, requestedDomains = setOf(AnalysisDomain.COCKPIT_NG))
        return analyzer.analyze(
            RepositoryAnalysisContext(
                repo = repo,
                config = config,
                scan = scan,
            )
        ).findings
    }

    private fun writeConfig(repo: Path, content: String) {
        repo.resolve("resources/backoffice-config.xml").apply {
            parent.createDirectories()
            writeText(content)
        }
    }

    private fun writeItems(repo: Path, content: String) {
        repo.resolve("resources/sample-items.xml").apply {
            parent.createDirectories()
            writeText(content)
        }
    }

    private fun writeWidgets(repo: Path, content: String) {
        repo.resolve("resources/widgets.xml").apply {
            parent.createDirectories()
            writeText(content)
        }
    }
}
