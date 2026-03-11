package ai.nina.labs.sapcclint.beansystem.parser

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BeansParserTest {

    private val parser = BeansParser()

    @Test
    fun parse_whenBeansFileContainsBeansEnumsAndProperties_extractsDeclarationsAndRawAttributeText() {
        val path = Files.createTempFile("sapcc-beans", ".xml")
        path.writeText(
            """
            <beans>
              <bean class="java.util.function.Function&lt;java.lang.String, java.lang.String>" extends="java.lang.Object" type="bean">
                <property name="items" type="java.util.List&lt;java.lang.String>"/>
              </bean>
              <enum class="com.example.DemoEnum">
                <value>ONE</value>
              </enum>
            </beans>
            """.trimIndent()
        )

        val parsed = parser.parse(path)

        assertEquals(1, parsed.beans.size)
        assertEquals(1, parsed.enums.size)
        val bean = parsed.beans.single()
        assertEquals("java.util.function.Function<java.lang.String, java.lang.String>", bean.clazz.value)
        assertEquals("java.util.function.Function&lt;java.lang.String, java.lang.String>", bean.clazz.rawValue)
        assertEquals("java.lang.Object", bean.extendsClass.value)
        assertEquals("bean", bean.type.value)
        assertEquals(1, bean.properties.size)
        assertEquals("items", bean.properties.single().name.value)
        assertEquals("java.util.List<java.lang.String>", bean.properties.single().type.value)
        assertEquals("java.util.List&lt;java.lang.String>", bean.properties.single().type.rawValue)
        assertNotNull(bean.clazz.location)
        assertTrue(bean.location.line > 0)
        assertEquals("com.example.DemoEnum", parsed.enums.single().clazz.value)
        assertEquals("ONE", parsed.enums.single().values.single().value.value)
    }

    @Test
    fun parse_whenBeansFileIsMalformed_fallsBackToTolerantAttributeExtraction() {
        val path = Files.createTempFile("sapcc-demo-beans", "-beans.xml")
        path.writeText(
            """
            <beans>
              <bean class="com.example.DemoBean">
                <property name="items" type="java.util.List<java.lang.String>"/>
              </bean>
            </beans>
            """.trimIndent()
        )

        val parsed = parser.parse(path)

        assertEquals(1, parsed.beans.size)
        val property = parsed.beans.single().properties.single()
        assertEquals("items", property.name.value)
        assertEquals("java.util.List<java.lang.String>", property.type.value)
        assertEquals("java.util.List<java.lang.String>", property.type.rawValue)
        assertNotNull(property.type.location)
    }
}
