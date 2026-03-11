package ai.nina.labs.sapcclint.cockpitng.model

import ai.nina.labs.sapcclint.itemsxml.model.LocatedValue
import ai.nina.labs.sapcclint.itemsxml.model.SourcePosition
import java.nio.file.Path

data class ParsedCockpitFile(
    val path: Path,
    val rootTag: String?,
    val contexts: List<CockpitContextDecl>,
    val rootNamespaces: List<CockpitNamespaceDecl>,
    val localNamespaces: List<CockpitNamespaceDecl>,
)

data class CockpitContextDecl(
    val attributes: Map<String, LocatedValue<String>>,
    val location: SourcePosition,
)

data class CockpitNamespaceDecl(
    val prefix: String,
    val uri: String,
    val elementName: String,
    val location: SourcePosition,
)
