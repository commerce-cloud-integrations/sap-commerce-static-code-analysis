package com.cci.sapcclint.businessprocess

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
import kotlin.test.assertTrue

class BusinessProcessDomainAnalyzerTest {

    private val scanner = RepositoryScanner()
    private val analyzer = BusinessProcessDomainAnalyzer()

    @Test
    fun analyze_whenProcessContainsMissingRequiredStructure_reportsDomFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-bp-structure")
        writeProcess(
            repo,
            """
                <process xmlns="http://www.hybris.de/xsd/processdefinition">
                  <contextParameter type="java.lang.String"/>
                  <action id="startAction">
                    <parameter name="code"/>
                  </action>
                  <scriptAction id="scriptNode">
                    <script/>
                  </scriptAction>
                  <split id="splitNode"/>
                  <notify id="notifyNode">
                    <userGroup/>
                  </notify>
                  <end id="done"/>
                  <bogus/>
                </process>
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Attribute 'name' is required on <process>") })
        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Attribute 'start' is required on <process>") })
        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Attribute 'name' is required on <contextParameter>") })
        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Attribute 'bean' is required on <action>") })
        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Element <action> requires a <transition> child") })
        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Attribute 'value' is required on <parameter>") })
        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Element <script> requires text content") })
        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Attribute 'type' is required on <script>") })
        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Element <split> requires a <targetNode> child") })
        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Attribute 'name' is required on <userGroup>") })
        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Element <userGroup> requires a <locmessage> child") })
        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Element <end> requires text content") })
        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Element <bogus> is not allowed inside <process>") })
    }

    @Test
    fun analyze_whenWaitCaseAndJoinContainInvalidNestedStructure_reportsDomFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-bp-wait")
        writeProcess(
            repo,
            """
                <process xmlns="http://www.hybris.de/xsd/processdefinition" name="demo" start="waitNode">
                  <wait id="waitNode">
                    <timeout/>
                    <case>
                      <choice id="approved"/>
                    </case>
                  </wait>
                  <join id="joinNode">
                    <bad/>
                  </join>
                </process>
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Attribute 'delay' is required on <timeout>") })
        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Attribute 'then' is required on <timeout>") })
        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Attribute 'event' is required on <case>") })
        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Attribute 'then' is required on <choice>") })
        assertTrue(findings.any { it.ruleId == "BPDomElementsInspection" && it.message.contains("Element <bad> is not allowed inside <join>") })
    }

    private fun analyze(repo: Path): List<Finding> {
        val config = AnalyzerConfig()
        val scan = scanner.scan(repo, config, requestedDomains = setOf(AnalysisDomain.BUSINESS_PROCESS))
        return analyzer.analyze(
            RepositoryAnalysisContext(
                repo = repo,
                config = config,
                scan = scan,
            )
        ).findings
    }

    private fun writeProcess(repo: Path, content: String) {
        repo.resolve("resources/process.xml").apply {
            parent.createDirectories()
            writeText(content)
        }
    }
}
