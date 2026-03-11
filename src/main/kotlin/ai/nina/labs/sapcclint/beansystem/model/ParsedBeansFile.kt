package ai.nina.labs.sapcclint.beansystem.model

import ai.nina.labs.sapcclint.itemsxml.model.SourcePosition
import java.nio.file.Path

data class ParsedBeansFile(
    val path: Path,
    val beans: List<BeanDecl>,
    val enums: List<EnumDecl>,
)

data class BeanDecl(
    val clazz: BeanLocatedText,
    val extendsClass: BeanLocatedText,
    val type: BeanLocatedText,
    val properties: List<BeanPropertyDecl>,
    val location: SourcePosition,
)

data class BeanPropertyDecl(
    val name: BeanLocatedText,
    val type: BeanLocatedText,
    val location: SourcePosition,
)

data class EnumDecl(
    val clazz: BeanLocatedText,
    val values: List<EnumValueDecl>,
    val location: SourcePosition,
)

data class EnumValueDecl(
    val value: BeanLocatedText,
    val location: SourcePosition,
)

data class BeanLocatedText(
    val value: String?,
    val rawValue: String?,
    val location: SourcePosition?,
)
