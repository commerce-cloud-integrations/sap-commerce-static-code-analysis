package ai.nina.labs.sapcclint.itemsxml.model

import java.nio.file.Path

data class ParsedItemsFile(
    val path: Path,
    val itemTypes: List<ItemTypeDecl>,
    val relations: List<RelationDecl>,
    val enumTypes: List<EnumTypeDecl>,
    val collectionTypes: List<CollectionTypeDecl>,
    val mapTypes: List<MapTypeDecl>,
)

data class ItemTypeDecl(
    val code: LocatedValue<String>,
    val extendsType: LocatedValue<String>,
    val jaloClass: LocatedValue<String>,
    val autoCreate: LocatedValue<Boolean>,
    val generate: LocatedValue<Boolean>,
    val isAbstract: LocatedValue<Boolean>,
    val metaType: LocatedValue<String>,
    val deployment: DeploymentDecl?,
    val indexes: List<IndexDecl>,
    val attributes: List<AttributeDecl>,
    val customProperties: List<CustomPropertyDecl>,
    val location: SourcePosition,
)

data class IndexDecl(
    val name: LocatedValue<String>,
    val keys: List<IndexKeyDecl>,
    val location: SourcePosition,
)

data class IndexKeyDecl(
    val attribute: LocatedValue<String>,
    val location: SourcePosition,
)

data class AttributeDecl(
    val qualifier: LocatedValue<String>,
    val type: LocatedValue<String>,
    val metaType: LocatedValue<String>,
    val autoCreate: LocatedValue<Boolean>,
    val generate: LocatedValue<Boolean>,
    val defaultValue: LocatedValue<String>,
    val persistence: PersistenceDecl?,
    val modifiers: ModifiersDecl?,
    val customProperties: List<CustomPropertyDecl>,
    val location: SourcePosition,
)

data class EnumTypeDecl(
    val code: LocatedValue<String>,
    val jaloClass: LocatedValue<String>,
    val autoCreate: LocatedValue<Boolean>,
    val generate: LocatedValue<Boolean>,
    val dynamic: LocatedValue<Boolean>,
    val values: List<EnumValueDecl>,
    val location: SourcePosition,
)

data class EnumValueDecl(
    val code: LocatedValue<String>,
    val location: SourcePosition,
)

data class CollectionTypeDecl(
    val code: LocatedValue<String>,
    val elementType: LocatedValue<String>,
    val type: LocatedValue<String>,
    val autoCreate: LocatedValue<Boolean>,
    val generate: LocatedValue<Boolean>,
    val location: SourcePosition,
)

data class MapTypeDecl(
    val code: LocatedValue<String>,
    val argumentType: LocatedValue<String>,
    val returnType: LocatedValue<String>,
    val autoCreate: LocatedValue<Boolean>,
    val generate: LocatedValue<Boolean>,
    val location: SourcePosition,
)

data class RelationDecl(
    val code: LocatedValue<String>,
    val deployment: DeploymentDecl?,
    val source: RelationEndDecl?,
    val target: RelationEndDecl?,
    val location: SourcePosition,
)

data class RelationEndDecl(
    val end: RelationEnd,
    val type: LocatedValue<String>,
    val qualifier: LocatedValue<String>,
    val metaType: LocatedValue<String>,
    val cardinality: LocatedValue<String>,
    val navigable: LocatedValue<Boolean>,
    val collectionType: LocatedValue<String>,
    val ordered: LocatedValue<Boolean>,
    val modifiers: ModifiersDecl?,
    val customProperties: List<CustomPropertyDecl>,
    val location: SourcePosition,
)

data class DeploymentDecl(
    val table: LocatedValue<String>,
    val typeCode: LocatedValue<String>,
    val propertyTable: LocatedValue<String>,
    val location: SourcePosition,
)

data class PersistenceDecl(
    val type: LocatedValue<String>,
    val attributeHandler: LocatedValue<String>,
    val location: SourcePosition,
)

data class ModifiersDecl(
    val read: LocatedValue<Boolean>,
    val write: LocatedValue<Boolean>,
    val search: LocatedValue<Boolean>,
    val optional: LocatedValue<Boolean>,
    val initial: LocatedValue<Boolean>,
    val unique: LocatedValue<Boolean>,
    val partOf: LocatedValue<Boolean>,
    val doNotOptimize: LocatedValue<Boolean>,
    val location: SourcePosition,
)

data class CustomPropertyDecl(
    val name: LocatedValue<String>,
    val value: LocatedValue<String>,
    val location: SourcePosition,
)

data class LocatedValue<T>(
    val value: T?,
    val location: SourcePosition?,
)

data class SourcePosition(
    val line: Int,
    val column: Int,
)

enum class RelationEnd {
    SOURCE,
    TARGET
}
