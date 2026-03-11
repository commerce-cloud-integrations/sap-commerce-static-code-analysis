package com.cci.sapcclint.itemsxml.parser

import com.cci.sapcclint.itemsxml.model.RelationEnd
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ItemsXmlParserTest {

    private val parser = ItemsXmlParser()

    @Test
    fun parse_whenItemTypesExist_extractsDeploymentAttributesFlagsAndLocations() {
        val path = Files.createTempFile("sapcc-items", "-items.xml")
        path.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="CustomProduct" extends="GenericItem" jaloclass="com.example.jalo.CustomProduct" autocreate="true" generate="false" abstract="true" metatype="ComposedType">
                  <deployment table="products" typecode="12345"/>
                  <custom-properties>
                    <property name="catalogVersionAttributeQualifier">
                      <value>catalogVersion</value>
                    </property>
                  </custom-properties>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String" metatype="AttributeDescriptor" autocreate="true" generate="true">
                      <defaultvalue>'DEFAULT'</defaultvalue>
                      <persistence type="dynamic" attributeHandler="productCodeHandler"/>
                      <modifiers read="true" write="false" search="true" optional="false" initial="true" unique="true" dontOptimize="true"/>
                      <custom-properties>
                        <property name="foo">
                          <value>bar</value>
                        </property>
                      </custom-properties>
                    </attribute>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val parsed = parser.parse(path)

        assertEquals(1, parsed.itemTypes.size)
        val itemType = parsed.itemTypes.single()
        assertEquals("CustomProduct", itemType.code.value)
        assertEquals("GenericItem", itemType.extendsType.value)
        assertEquals("com.example.jalo.CustomProduct", itemType.jaloClass.value)
        assertEquals(true, itemType.autoCreate.value)
        assertEquals(false, itemType.generate.value)
        assertEquals(true, itemType.isAbstract.value)
        assertEquals("ComposedType", itemType.metaType.value)
        assertEquals("products", itemType.deployment?.table?.value)
        assertEquals("12345", itemType.deployment?.typeCode?.value)
        assertEquals(1, itemType.customProperties.size)
        assertEquals("catalogVersionAttributeQualifier", itemType.customProperties.single().name.value)
        assertEquals("catalogVersion", itemType.customProperties.single().value.value)
        assertTrue(itemType.indexes.isEmpty())

        assertEquals(1, itemType.attributes.size)
        val attribute = itemType.attributes.single()
        assertEquals("code", attribute.qualifier.value)
        assertEquals("java.lang.String", attribute.type.value)
        assertEquals("AttributeDescriptor", attribute.metaType.value)
        assertEquals("'DEFAULT'", attribute.defaultValue.value)
        assertEquals("dynamic", attribute.persistence?.type?.value)
        assertEquals("productCodeHandler", attribute.persistence?.attributeHandler?.value)
        assertEquals(true, attribute.modifiers?.read?.value)
        assertEquals(false, attribute.modifiers?.write?.value)
        assertEquals(true, attribute.modifiers?.search?.value)
        assertEquals(false, attribute.modifiers?.optional?.value)
        assertEquals(true, attribute.modifiers?.initial?.value)
        assertEquals(true, attribute.modifiers?.unique?.value)
        assertEquals(true, attribute.modifiers?.doNotOptimize?.value)
        assertEquals("foo", attribute.customProperties.single().name.value)
        assertEquals("bar", attribute.customProperties.single().value.value)
        assertEquals(3, itemType.location.line)
        assertTrue(itemType.location.column > 0)
        assertEquals(3, itemType.code.location?.line)
        assertEquals(4, itemType.deployment?.typeCode?.location?.line)
        assertEquals(13, attribute.persistence?.attributeHandler?.location?.line)
        assertEquals(14, attribute.modifiers?.doNotOptimize?.location?.line)
    }

    @Test
    fun parse_whenRelationExists_extractsEndsDeploymentCustomPropertiesAndLocations() {
        val path = Files.createTempFile("sapcc-relation", "-items.xml")
        path.writeText(
            """
            <items>
              <relations>
                <relation code="Product2Category">
                  <deployment table="prod2catrel" typecode="15000"/>
                  <sourceElement type="Product" qualifier="supercategories" metatype="RelationDescriptor" cardinality="many" ordered="true" collectiontype="list">
                    <modifiers partof="false"/>
                  </sourceElement>
                  <targetElement type="Category" qualifier="products" cardinality="many" navigable="false">
                    <custom-properties>
                      <property name="foo">
                        <value>bar</value>
                      </property>
                    </custom-properties>
                  </targetElement>
                </relation>
              </relations>
            </items>
            """.trimIndent()
        )

        val parsed = parser.parse(path)

        assertEquals(1, parsed.relations.size)
        val relation = parsed.relations.single()
        assertEquals("Product2Category", relation.code.value)
        assertEquals("prod2catrel", relation.deployment?.table?.value)
        assertEquals("15000", relation.deployment?.typeCode?.value)
        assertNotNull(relation.source)
        assertNotNull(relation.target)
        assertEquals(RelationEnd.SOURCE, relation.source.end)
        assertEquals("RelationDescriptor", relation.source.metaType.value)
        assertEquals("many", relation.source.cardinality.value)
        assertEquals(true, relation.source.ordered.value)
        assertEquals("list", relation.source.collectionType.value)
        assertEquals(RelationEnd.TARGET, relation.target.end)
        assertEquals(false, relation.target.navigable.value)
        assertEquals("foo", relation.target.customProperties.single().name.value)
        assertEquals("bar", relation.target.customProperties.single().value.value)
        assertEquals(3, relation.location.line)
        assertEquals(5, relation.source.ordered.location?.line)
        assertEquals(8, relation.target.navigable.location?.line)
    }

    @Test
    fun parse_whenEnumCollectionAndMapTypesExist_extractsClassifierDeclarations() {
        val path = Files.createTempFile("sapcc-classifiers", "-items.xml")
        path.writeText(
            """
            <items>
              <enumtypes>
                <enumtype code="ApprovalStatus" dynamic="true" autocreate="true" generate="false" jaloclass="com.example.ApprovalStatus">
                  <value code="PENDING"/>
                  <value code="APPROVED"/>
                </enumtype>
              </enumtypes>
              <collectiontypes>
                <collectiontype code="ApprovalStatusList" elementtype="ApprovalStatus" type="list" autocreate="true" generate="false"/>
              </collectiontypes>
              <maptypes>
                <maptype code="StringToStatusMap" argumenttype="java.lang.String" returntype="ApprovalStatus" autocreate="true" generate="true"/>
              </maptypes>
            </items>
            """.trimIndent()
        )

        val parsed = parser.parse(path)

        assertEquals(1, parsed.enumTypes.size)
        assertEquals("ApprovalStatus", parsed.enumTypes.single().code.value)
        assertEquals(true, parsed.enumTypes.single().dynamic.value)
        assertEquals(listOf("PENDING", "APPROVED"), parsed.enumTypes.single().values.map { it.code.value })

        assertEquals(1, parsed.collectionTypes.size)
        assertEquals("ApprovalStatusList", parsed.collectionTypes.single().code.value)
        assertEquals("ApprovalStatus", parsed.collectionTypes.single().elementType.value)
        assertEquals("list", parsed.collectionTypes.single().type.value)

        assertEquals(1, parsed.mapTypes.size)
        assertEquals("StringToStatusMap", parsed.mapTypes.single().code.value)
        assertEquals("java.lang.String", parsed.mapTypes.single().argumentType.value)
        assertEquals("ApprovalStatus", parsed.mapTypes.single().returnType.value)
    }

    @Test
    fun parse_whenUnknownNestedElementExists_keepsParsingFollowingSiblings() {
        val path = Files.createTempFile("sapcc-unknown", "-items.xml")
        path.writeText(
            """
            <items>
              <relations>
                <relation code="First">
                  <unknown>
                    <child/>
                  </unknown>
                </relation>
                <relation code="Second">
                  <sourceElement type="Product"/>
                  <targetElement type="Category"/>
                </relation>
              </relations>
            </items>
            """.trimIndent()
        )

        val parsed = parser.parse(path)

        assertEquals(listOf("First", "Second"), parsed.relations.map { it.code.value })
    }

    @Test
    fun parse_whenItemTypeDefinesIndexes_extractsIndexKeys() {
        val path = Files.createTempFile("sapcc-indexes", "-items.xml")
        path.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="IndexedItem" extends="GenericItem">
                  <deployment table="IndexedItem" typecode="12000"/>
                  <indexes>
                    <index name="uidIndex">
                      <key attribute="uid"/>
                    </index>
                    <index name="compositeIndex">
                      <key attribute="catalogVersion"/>
                      <key attribute="code"/>
                    </index>
                  </indexes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val parsed = parser.parse(path)

        val itemType = parsed.itemTypes.single()
        assertEquals(listOf("uidIndex", "compositeIndex"), itemType.indexes.map { it.name.value })
        assertEquals(listOf("uid"), itemType.indexes[0].keys.map { it.attribute.value })
        assertEquals(listOf("catalogVersion", "code"), itemType.indexes[1].keys.map { it.attribute.value })
    }
}
