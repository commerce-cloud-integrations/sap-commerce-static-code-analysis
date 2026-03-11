package com.cci.sapcclint.rules

import com.cci.sapcclint.core.AnalysisDomain
import com.cci.sapcclint.itemsxml.model.SourcePosition
import java.nio.file.Path

data class Finding(
    val ruleId: String,
    val severity: FindingSeverity,
    val message: String,
    val location: FindingLocation,
    val entityKey: String? = null,
    val domain: AnalysisDomain = AnalysisDomain.TYPE_SYSTEM,
)

data class FindingLocation(
    val file: Path,
    val position: SourcePosition,
)

enum class FindingSeverity {
    ERROR,
    WARNING
}
