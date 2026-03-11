package com.cci.sapcclint.impex.model

import com.cci.sapcclint.itemsxml.model.LocatedValue
import com.cci.sapcclint.itemsxml.model.SourcePosition
import java.nio.file.Path

data class ParsedImpexFile(
    val path: Path,
    val macroDeclarations: List<ImpexMacroDeclaration>,
    val headerBlocks: List<ImpexHeaderBlock>,
    val orphanValueLines: List<ImpexValueLine>,
)

data class ImpexHeaderBlock(
    val header: ImpexHeader,
    val valueLines: List<ImpexValueLine>,
)

data class ImpexHeader(
    val mode: ImpexHeaderMode,
    val typeName: LocatedValue<String>,
    val modifiers: List<ImpexModifier>,
    val parameters: List<ImpexHeaderParameter>,
    val missingParameterSeparators: List<SourcePosition>,
    val location: SourcePosition,
)

data class ImpexHeaderParameter(
    val name: ImpexReference,
    val leadingParameters: List<ImpexParameter>,
    val modifiers: List<ImpexModifier>,
    val trailingParameters: List<ImpexParameter>,
    val location: SourcePosition,
)

data class ImpexParameter(
    val name: ImpexReference,
    val parameters: List<ImpexParameter>,
    val suffixReference: ImpexReference?,
    val modifiers: List<ImpexModifier>,
    val separator: ImpexParameterSeparator?,
    val location: SourcePosition,
)

data class ImpexModifier(
    val name: LocatedValue<String>,
    val value: LocatedValue<String>,
    val valueQuoted: Boolean,
    val location: SourcePosition,
)

data class ImpexMacroDeclaration(
    val name: LocatedValue<String>,
    val rawName: String,
    val rawValue: String,
    val references: List<ImpexReference>,
    val location: SourcePosition,
)

data class ImpexValueLine(
    val subType: LocatedValue<String>,
    val valueGroups: List<ImpexValueGroup>,
    val location: SourcePosition,
)

data class ImpexValueGroup(
    val columnIndex: Int,
    val rawValue: String,
    val references: List<ImpexReference>,
    val location: SourcePosition,
)

data class ImpexReference(
    val text: String,
    val kind: ImpexReferenceKind,
    val location: SourcePosition,
)

enum class ImpexHeaderMode {
    INSERT,
    INSERT_UPDATE,
    UPDATE,
    REMOVE,
}

enum class ImpexReferenceKind {
    TYPE,
    HEADER_PARAMETER,
    SPECIAL_PARAMETER,
    MACRO_USAGE,
    DOCUMENT_ID,
    FUNCTION,
    VALUE_SUBTYPE,
}

enum class ImpexParameterSeparator {
    COMMA,
    ALTERNATIVE,
}
