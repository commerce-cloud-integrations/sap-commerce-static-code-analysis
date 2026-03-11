package com.cci.sapcclint.reporting

import com.cci.sapcclint.core.AnalysisResult
import com.cci.sapcclint.rules.Finding
import java.nio.file.Path

internal fun AnalysisResult.scannedDomains() = domainResults
    .filter { it.analyzedFileCount > 0 }
    .sortedBy { it.domain.cliValue }

internal fun relativizeReportPath(repo: Path, file: Path): String {
    val normalizedRepo = repo.toAbsolutePath().normalize()
    val normalizedFile = file.toAbsolutePath().normalize()
    return if (normalizedFile.startsWith(normalizedRepo)) {
        normalizedRepo.relativize(normalizedFile).toString().replace('\\', '/')
    } else {
        normalizedFile.toString().replace('\\', '/')
    }
}

internal fun Finding.relativePath(repo: Path): String = relativizeReportPath(repo, location.file)

internal fun String.escapeHtml(): String = buildString(length) {
    for (character in this@escapeHtml) {
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> character.toString()
            }
        )
    }
}
