package com.cci.sapcclint.core

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScanOptionsParserTest {

    private val parser = ScanOptionsParser()

    @Test
    fun parse_whenRepoOnly_producesDefaultConsoleOptions() {
        val options = parser.parse(listOf("--repo", "."))

        assertEquals(Paths.get(".").normalize(), options?.repo)
        assertEquals(setOf(OutputFormat.CONSOLE), options?.formats)
        assertEquals(emptySet(), options?.domains)
        assertNull(options?.reportPathsFile)
        assertNull(options?.htmlOut)
        assertNull(options?.csvOut)
        assertNull(options?.sarifOut)
        assertNull(options?.rdjsonlOut)
    }

    @Test
    fun parse_whenReportPathsFileIsProvided_keepsThatPath() {
        val options = parser.parse(
            listOf(
                "--repo",
                ".",
                "--report-paths-file",
                "build/changed-files.txt",
            )
        )

        assertEquals(Paths.get("build/changed-files.txt").normalize(), options?.reportPathsFile)
    }

    @Test
    fun parse_whenHtmlRequestedWithoutOutput_returnsNull() {
        val options = parser.parse(listOf("--repo", ".", "--format", "html"))

        assertNull(options)
    }

    @Test
    fun parse_whenHtmlOutputIsProvided_keepsHtmlOutputPath() {
        val options = parser.parse(
            listOf(
                "--repo",
                ".",
                "--format",
                "console",
                "--format",
                "html",
                "--html-out",
                "build/report.html",
            )
        )

        assertEquals(setOf(OutputFormat.CONSOLE, OutputFormat.HTML), options?.formats)
        assertEquals(Paths.get("build/report.html").normalize(), options?.htmlOut)
    }

    @Test
    fun parse_whenCsvRequestedWithoutOutput_returnsNull() {
        val options = parser.parse(listOf("--repo", ".", "--format", "csv"))

        assertNull(options)
    }

    @Test
    fun parse_whenCsvOutputIsProvided_keepsCsvOutputPath() {
        val options = parser.parse(
            listOf(
                "--repo",
                ".",
                "--format",
                "console",
                "--format",
                "csv",
                "--csv-out",
                "build/report.csv",
            )
        )

        assertEquals(setOf(OutputFormat.CONSOLE, OutputFormat.CSV), options?.formats)
        assertEquals(Paths.get("build/report.csv").normalize(), options?.csvOut)
    }

    @Test
    fun parse_whenSarifRequestedWithoutOutput_returnsNull() {
        val options = parser.parse(listOf("--repo", ".", "--format", "sarif"))

        assertNull(options)
    }

    @Test
    fun parse_whenMultipleFormatsRequested_keepsBoth() {
        val options = parser.parse(
            listOf(
                "--repo",
                ".",
                "--format",
                "console",
                "--format",
                "sarif",
                "--sarif-out",
                "build/report.sarif"
            )
        )

        assertEquals(setOf(OutputFormat.CONSOLE, OutputFormat.SARIF), options?.formats)
        assertEquals(Paths.get("build/report.sarif").normalize(), options?.sarifOut)
    }

    @Test
    fun parse_whenHtmlAndCsvAreRequestedTogether_keepsBothOutputPaths() {
        val options = parser.parse(
            listOf(
                "--repo",
                ".",
                "--format",
                "html",
                "--html-out",
                "build/report.html",
                "--format",
                "csv",
                "--csv-out",
                "build/report.csv",
            )
        )

        assertEquals(setOf(OutputFormat.HTML, OutputFormat.CSV), options?.formats)
        assertEquals(Paths.get("build/report.html").normalize(), options?.htmlOut)
        assertEquals(Paths.get("build/report.csv").normalize(), options?.csvOut)
    }

    @Test
    fun parse_whenRdjsonlRequestedWithoutOutput_returnsNull() {
        val options = parser.parse(listOf("--repo", ".", "--format", "rdjsonl"))

        assertNull(options)
    }

    @Test
    fun parse_whenRdjsonlOutputIsProvided_keepsStructuredOutputPath() {
        val options = parser.parse(
            listOf(
                "--repo",
                ".",
                "--format",
                "console",
                "--format",
                "rdjsonl",
                "--rdjsonl-out",
                "build/report.rdjsonl",
            )
        )

        assertEquals(setOf(OutputFormat.CONSOLE, OutputFormat.RDJSONL), options?.formats)
        assertEquals(Paths.get("build/report.rdjsonl").normalize(), options?.rdjsonlOut)
    }

    @Test
    fun parse_whenDomainFiltersAreProvided_keepsRequestedDomains() {
        val options = parser.parse(
            listOf(
                "--repo",
                ".",
                "--domain",
                "type-system",
                "--domain",
                "impex",
            )
        )

        assertEquals(setOf(AnalysisDomain.TYPE_SYSTEM, AnalysisDomain.IMPEX), options?.domains)
    }
}
