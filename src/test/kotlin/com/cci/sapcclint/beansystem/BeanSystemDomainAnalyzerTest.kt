package com.cci.sapcclint.beansystem

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

class BeanSystemDomainAnalyzerTest {

    private val scanner = RepositoryScanner()
    private val analyzer = BeanSystemDomainAnalyzer()

    @Test
    fun analyze_whenBeansAndEnumsAreDuplicated_reportsDuplicateFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-beans-duplicates")
        writeBeans(
            repo,
            """
            <beans>
              <bean class="com.example.DemoBean">
                <property name="code" type="java.lang.String"/>
              </bean>
              <bean class="com.example.DemoBean">
                <property name="code" type="java.lang.String"/>
              </bean>
              <enum class="com.example.DemoEnum">
                <value>ONE</value>
                <value>ONE</value>
              </enum>
              <enum class="com.example.DemoEnum">
                <value>ONE</value>
              </enum>
            </beans>
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "BSDuplicateBeanDefinition" })
        assertTrue(findings.any { it.ruleId == "BSDuplicateBeanPropertyDefinition" })
        assertTrue(findings.any { it.ruleId == "BSDuplicateEnumDefinition" })
        assertTrue(findings.any { it.ruleId == "BSDuplicateEnumValueDefinition" })
    }

    @Test
    fun analyze_whenBeanClassesDifferOnlyByGenericSuffix_reportsDuplicateBeanDefinition() {
        val repo = Files.createTempDirectory("sapcc-lint-beans-generic-duplicates")
        writeBeans(
            repo,
            """
            <beans>
              <bean class="com.example.DemoBean&lt;java.lang.String>"/>
              <bean class="com.example.DemoBean&lt;java.lang.Integer>"/>
            </beans>
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "BSDuplicateBeanDefinition" })
    }

    @Test
    fun analyze_whenPropertyNameUsesKeyword_reportsFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-beans-keyword")
        writeBeans(
            repo,
            """
            <beans>
              <bean class="com.example.DemoBean">
                <property name="class" type="String"/>
              </bean>
            </beans>
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "BSKeywordIsNotAllowedAsBeanPropertyName" && it.message.contains("class") })
    }

    @Test
    fun analyze_whenPropertyTypeUsesGenericsAndJavaLangPrefix_reportsLexicalFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-beans-lexical")
        writeBeans(
            repo,
            """
            <beans>
              <bean class="java.util.function.Function&lt;java.lang.String, java.lang.String>">
                <property name="items" type="java.util.List&lt;java.lang.String>"/>
                <property name="plain" type="java.lang.String"/>
              </bean>
            </beans>
            """.trimIndent()
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "BSUnescapedGreaterThanSignIsNotAllowedInBeanPropertyType" && it.message.contains("items") })
        assertTrue(findings.any { it.ruleId == "BSUnescapedGreaterLessThanSignIsNotAllowedInBeanClass" })
        assertTrue(findings.any { it.ruleId == "BSOmitJavaLangPackageInBeanPropertyType" && it.message.contains("plain") })
        assertFalse(findings.any { it.ruleId == "BSUnescapedLessThanSignIsNotAllowedInBeanPropertyType" })
    }

    @Test
    fun analyze_whenBeansFileIsMalformedButNamedAsBeansFile_reportsUnescapedLessThanFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-beans-malformed")
        writeBeans(
            repo = repo,
            content = """
                <beans>
                  <bean class="com.example.DemoBean">
                    <property name="items" type="java.util.List<java.lang.String>"/>
                  </bean>
                </beans>
            """.trimIndent(),
            relativePath = "resources/demo-beans.xml",
        )

        val findings = analyze(repo)

        assertTrue(findings.any { it.ruleId == "BSUnescapedLessThanSignIsNotAllowedInBeanPropertyType" && it.message.contains("items") })
    }

    @Test
    fun analyze_whenBeansStructureIsInvalid_reportsDomInspectionFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-beans-structure")
        writeBeans(
            repo = repo,
            content = """
                <beans>
                  <bean type="dto" unknown="x">
                    <description>One</description>
                    <description>Two</description>
                    <property type="String"/>
                    <unknown/>
                  </bean>
                  <enum class="com.example.DemoEnum"/>
                </beans>
            """.trimIndent(),
            relativePath = "resources/demo-beans.xml",
        )

        val findings = analyze(repo).filter { it.ruleId == "BSDomElementsInspection" }

        assertTrue(findings.any { it.message.contains("Attribute 'class' is required on <bean>") })
        assertTrue(findings.any { it.message.contains("Attribute 'unknown' is not allowed on <bean>") })
        assertTrue(findings.any { it.message.contains("Attribute 'type' on <bean> must be one of") })
        assertTrue(findings.any { it.message.contains("Attribute 'name' is required on <property>") })
        assertTrue(findings.any { it.message.contains("Element <unknown> is not allowed inside <bean>") })
        assertTrue(findings.any { it.message.contains("Element <description> may appear only once inside <bean>") })
        assertTrue(findings.any { it.message.contains("Element <enum> requires at least 1 <value> child") })
    }

    @Test
    fun analyze_whenBeansFileHasWrongRoot_reportsDomInspectionFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-beans-root")
        writeBeans(
            repo = repo,
            content = """
                <config>
                  <bean class="com.example.DemoBean"/>
                </config>
            """.trimIndent(),
            relativePath = "resources/demo-beans.xml",
        )

        val findings = analyze(repo).filter { it.ruleId == "BSDomElementsInspection" }

        assertTrue(findings.any { it.message.contains("Root element must be <beans>") })
    }

    private fun analyze(repo: Path): List<Finding> {
        val config = AnalyzerConfig()
        val scan = scanner.scan(repo, config, requestedDomains = setOf(AnalysisDomain.BEAN_SYSTEM))
        return analyzer.analyze(
            RepositoryAnalysisContext(
                repo = repo,
                config = config,
                scan = scan,
            )
        ).findings
    }

    private fun writeBeans(repo: Path, content: String, relativePath: String = "resources/beans.xml") {
        repo.resolve(relativePath).apply {
            parent.createDirectories()
            writeText(content)
        }
    }
}
