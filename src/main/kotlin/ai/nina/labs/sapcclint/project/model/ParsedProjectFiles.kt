package ai.nina.labs.sapcclint.project.model

import ai.nina.labs.sapcclint.itemsxml.model.LocatedValue
import ai.nina.labs.sapcclint.itemsxml.model.SourcePosition
import java.nio.file.Path

data class ParsedExtensionInfoFile(
    val path: Path,
    val extensionName: LocatedValue<String>,
    val requiredExtensions: List<RequiredExtensionDecl>,
    val location: SourcePosition,
)

data class RequiredExtensionDecl(
    val name: LocatedValue<String>,
    val location: SourcePosition,
)

data class ParsedLocalExtensionsFile(
    val path: Path,
    val extensions: List<LocalExtensionDecl>,
    val location: SourcePosition,
)

data class LocalExtensionDecl(
    val name: LocatedValue<String>,
    val location: SourcePosition,
)
