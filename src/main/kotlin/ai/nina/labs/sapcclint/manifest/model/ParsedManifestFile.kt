package ai.nina.labs.sapcclint.manifest.model

import ai.nina.labs.sapcclint.itemsxml.model.SourcePosition
import java.nio.file.Path

data class ParsedManifestFile(
    val path: Path,
    val extensionReferences: List<ManifestStringRef>,
    val templateReferences: List<ManifestStringRef>,
    val extensionPackReferences: List<ManifestStringRef>,
    val location: SourcePosition,
)

data class ManifestStringRef(
    val value: String,
    val location: SourcePosition,
)
