package com.cci.sapcclint.core

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyzerApplicationTest {

    @Test
    fun run_whenNoArguments_returnsUsageError() {
        val result = AnalyzerApplication().run(emptyList())

        assertEquals(1, result)
    }

    @Test
    fun run_whenScanCommandHasRequiredRepo_returnsSuccess() {
        val repo = Files.createTempDirectory("sapcc-lint-app-success")
        val itemsFile = repo.resolve("custom/core/resources/custom-items.xml")
        itemsFile.parent.createDirectories()
        itemsFile.writeText("<items/>")

        val result = AnalyzerApplication().run(listOf("scan", "--repo", repo.toString()))

        assertEquals(0, result)
    }

    @Test
    fun run_whenRepositoryHasDefaultConfig_appliesItWithoutExplicitFlag() {
        val repo = Files.createTempDirectory("sapcc-lint-app")
        val includedFile = repo.resolve("custom/include/resources/a-items.xml")
        val excludedFile = repo.resolve("custom/excluded/resources/b-items.xml")
        includedFile.parent.createDirectories()
        excludedFile.parent.createDirectories()
        includedFile.writeText("<items/>")
        excludedFile.writeText("<items/>")
        repo.resolve(".sapcc-lint.yml").writeText(
            """
            paths:
              exclude:
                - custom/excluded/**
            """.trimIndent()
        )

        val originalOut = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))

        try {
            val result = AnalyzerApplication().run(listOf("scan", "--repo", repo.toString()))

            assertEquals(0, result)
        } finally {
            System.setOut(originalOut)
        }

        assertTrue(buffer.toString().contains("Scanned 1 file(s) across 1 domain(s)"))
        assertTrue(buffer.toString().contains("type-system: 1 file(s)"))
    }
}
