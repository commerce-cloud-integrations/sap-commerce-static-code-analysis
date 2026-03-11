package com.cci.sapcclint.core

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanCommandTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun run_whenFindingsContainErrors_writesConsoleAndSarifAndReturnsFailureCode() {
        val repo = Files.createTempDirectory("sapcc-lint-scan-errors")
        val itemsFile = repo.resolve("custom/core/resources/custom-items.xml")
        itemsFile.parent.createDirectories()
        itemsFile.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="FirstType">
                  <deployment table="firsttype" typecode="12000"/>
                </itemtype>
                <itemtype code="SecondType">
                  <deployment table="secondtype" typecode="12000"/>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val sarifOut = repo.resolve("build/reports/findings.sarif")
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exitCode = withRedirectedStreams(stdout, stderr) {
            ScanCommand().run(
                listOf(
                    "--repo", repo.toString(),
                    "--format", "console",
                    "--format", "sarif",
                    "--sarif-out", sarifOut.toString(),
                )
            )
        }

        assertEquals(1, exitCode)
        val consoleOutput = stdout.toString()
        assertTrue(consoleOutput.contains("Scanned 1 file(s) across 1 domain(s)"))
        assertTrue(consoleOutput.contains("type-system: 1 file(s)"))
        assertTrue(consoleOutput.contains("Findings: 2 error(s), 0 warning(s)"))
        assertTrue(consoleOutput.contains("custom/core/resources/custom-items.xml"))
        assertTrue(consoleOutput.contains("[type-system] TSDeploymentTypeCodeMustBeUnique"))
        assertTrue(Files.exists(sarifOut))

        val sarif = objectMapper.readTree(sarifOut.toFile())
        assertEquals("2.1.0", sarif["version"].asText())
        assertEquals("sapcc-lint", sarif["runs"][0]["tool"]["driver"]["name"].asText())
        assertEquals(2, sarif["runs"][0]["results"].size())
        assertEquals("type-system", sarif["runs"][0]["results"][0]["properties"]["tags"][0].asText())
        assertEquals(
            "custom/core/resources/custom-items.xml",
            sarif["runs"][0]["results"][0]["locations"][0]["physicalLocation"]["artifactLocation"]["uri"].asText()
        )
        assertTrue(stderr.toString().isBlank())
    }

    @Test
    fun run_whenFindingsContainOnlyWarnings_returnsSuccess() {
        val repo = Files.createTempDirectory("sapcc-lint-scan-warnings")
        val itemsFile = repo.resolve("custom/core/resources/custom-items.xml")
        itemsFile.parent.createDirectories()
        itemsFile.writeText(
            """
            <items>
              <relations>
                <relation code="WarnRelation">
                  <deployment table="warnrel" typecode="15000"/>
                  <sourceElement type="SourceType" qualifier="targets" cardinality="many" collectiontype="list" ordered="true"/>
                  <targetElement type="TargetType" cardinality="many" navigable="false"/>
                </relation>
              </relations>
            </items>
            """.trimIndent()
        )

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exitCode = withRedirectedStreams(stdout, stderr) {
            ScanCommand().run(listOf("--repo", repo.toString()))
        }

        assertEquals(0, exitCode)
        val consoleOutput = stdout.toString()
        assertTrue(consoleOutput.contains("Findings: 0 error(s), 2 warning(s)"))
        assertTrue(consoleOutput.contains("[type-system] TSOrderingOfRelationShouldBeAvoided"))
        assertTrue(consoleOutput.contains("[type-system] TSListsInRelationShouldBeAvoided"))
        assertTrue(stderr.toString().isBlank())
    }

    @Test
    fun run_whenRdjsonlIsRequested_writesStructuredDiagnostics() {
        val repo = Files.createTempDirectory("sapcc-lint-scan-rdjsonl")
        val itemsFile = repo.resolve("custom/core/resources/custom-items.xml")
        itemsFile.parent.createDirectories()
        itemsFile.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="FirstType">
                  <deployment table="firsttype" typecode="12000"/>
                </itemtype>
                <itemtype code="SecondType">
                  <deployment table="secondtype" typecode="12000"/>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val rdjsonlOut = repo.resolve("build/reports/findings.rdjsonl")

        val exitCode = ScanCommand().run(
            listOf(
                "--repo", repo.toString(),
                "--format", "rdjsonl",
                "--rdjsonl-out", rdjsonlOut.toString(),
            )
        )

        assertEquals(1, exitCode)
        assertTrue(Files.exists(rdjsonlOut))
        val diagnostics = Files.readAllLines(rdjsonlOut).filter { it.isNotBlank() }
        assertEquals(2, diagnostics.size)

        val first = objectMapper.readTree(diagnostics.first())
        assertEquals("sapcc-lint", first["source"]["name"].asText())
        assertEquals("TSDeploymentTypeCodeMustBeUnique", first["code"]["value"].asText())
        assertEquals("ERROR", first["severity"].asText())
        assertEquals("custom/core/resources/custom-items.xml", first["location"]["path"].asText())
        assertEquals(4, first["location"]["range"]["start"]["line"].asInt())
    }

    @Test
    fun run_whenReportPathsFileIsProvided_filtersArtifactsAndExitCodeToMatchingFiles() {
        val repo = Files.createTempDirectory("sapcc-lint-scan-report-paths")
        val firstItemsFile = repo.resolve("custom/core/resources/first-items.xml")
        val secondItemsFile = repo.resolve("custom/core/resources/second-items.xml")
        val reportPathsFile = repo.resolve("build/changed-files.txt")
        val sarifOut = repo.resolve("build/reports/findings.sarif")
        firstItemsFile.parent.createDirectories()
        reportPathsFile.parent.createDirectories()
        firstItemsFile.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="FirstType">
                  <deployment table="firsttype" typecode="12000"/>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        secondItemsFile.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="SecondType">
                  <deployment table="secondtype" typecode="12000"/>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        reportPathsFile.writeText("custom/core/resources/first-items.xml\n")

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exitCode = withRedirectedStreams(stdout, stderr) {
            ScanCommand().run(
                listOf(
                    "--repo", repo.toString(),
                    "--report-paths-file", reportPathsFile.toString(),
                    "--format", "console",
                    "--format", "sarif",
                    "--sarif-out", sarifOut.toString(),
                )
            )
        }

        assertEquals(1, exitCode)
        val consoleOutput = stdout.toString()
        assertTrue(consoleOutput.contains("Findings: 1 error(s), 0 warning(s)"))
        assertTrue(consoleOutput.contains("custom/core/resources/first-items.xml"))
        assertTrue(!consoleOutput.contains("custom/core/resources/second-items.xml"))

        val sarif = objectMapper.readTree(sarifOut.toFile())
        assertEquals(1, sarif["runs"][0]["results"].size())
        assertEquals(
            "custom/core/resources/first-items.xml",
            sarif["runs"][0]["results"][0]["locations"][0]["physicalLocation"]["artifactLocation"]["uri"].asText()
        )
        assertTrue(stderr.toString().isBlank())
    }

    @Test
    fun run_whenReportPathsFileOmitsFailingFiles_returnsSuccess() {
        val repo = Files.createTempDirectory("sapcc-lint-scan-report-paths-success")
        val firstFailingItemsFile = repo.resolve("custom/core/resources/first-failing-items.xml")
        val secondFailingItemsFile = repo.resolve("custom/core/resources/second-failing-items.xml")
        val cleanItemsFile = repo.resolve("custom/core/resources/clean-items.xml")
        val reportPathsFile = repo.resolve("build/changed-files.txt")
        firstFailingItemsFile.parent.createDirectories()
        reportPathsFile.parent.createDirectories()
        firstFailingItemsFile.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="FirstType">
                  <deployment table="firsttype" typecode="12000"/>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        secondFailingItemsFile.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="SecondType">
                  <deployment table="secondtype" typecode="12000"/>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        cleanItemsFile.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="CleanType">
                  <deployment table="cleantype" typecode="13000"/>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        reportPathsFile.writeText("custom/core/resources/clean-items.xml\n")

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exitCode = withRedirectedStreams(stdout, stderr) {
            ScanCommand().run(
                listOf(
                    "--repo", repo.toString(),
                    "--report-paths-file", reportPathsFile.toString(),
                )
            )
        }

        assertEquals(0, exitCode)
        assertTrue(stdout.toString().contains("No findings."))
        assertTrue(stderr.toString().isBlank())
    }

    @Test
    fun run_whenHtmlAndCsvAreRequested_writesReadableAndFlatReports() {
        val repo = Files.createTempDirectory("sapcc-lint-scan-html-csv")
        val itemsFile = repo.resolve("custom/core/resources/custom-items.xml")
        itemsFile.parent.createDirectories()
        itemsFile.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="FirstType">
                  <deployment table="firsttype" typecode="12000"/>
                </itemtype>
                <itemtype code="SecondType">
                  <deployment table="secondtype" typecode="12000"/>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val htmlOut = repo.resolve("build/reports/findings.html")
        val csvOut = repo.resolve("build/reports/findings.csv")

        val exitCode = ScanCommand().run(
            listOf(
                "--repo", repo.toString(),
                "--format", "html",
                "--html-out", htmlOut.toString(),
                "--format", "csv",
                "--csv-out", csvOut.toString(),
            )
        )

        assertEquals(1, exitCode)
        assertTrue(Files.exists(htmlOut))
        assertTrue(Files.exists(csvOut))

        val html = Files.readString(htmlOut)
        assertTrue(html.contains("<h1>sapcc-lint report</h1>"))
        assertTrue(html.contains("TSDeploymentTypeCodeMustBeUnique"))
        assertTrue(html.contains("custom/core/resources/custom-items.xml"))
        assertTrue(html.contains("Findings by rule"))

        val csvLines = Files.readAllLines(csvOut)
        assertEquals("severity,domain,rule_id,file,line,column,message,entity_key", csvLines.first())
        assertEquals(3, csvLines.size)
        assertTrue(csvLines[1].contains("\"TSDeploymentTypeCodeMustBeUnique\""))
        assertTrue(csvLines[1].contains("\"custom/core/resources/custom-items.xml\""))
    }

    @Test
    fun run_whenParsingFails_returnsInternalErrorCode() {
        val repo = Files.createTempDirectory("sapcc-lint-scan-invalid")
        val itemsFile = repo.resolve("custom/core/resources/custom-items.xml")
        itemsFile.parent.createDirectories()
        itemsFile.writeText("<items><itemtypes><itemtype code=\"Broken\"></items>")

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exitCode = withRedirectedStreams(stdout, stderr) {
            ScanCommand().run(listOf("--repo", repo.toString()))
        }

        assertEquals(2, exitCode)
        assertTrue(stdout.toString().isBlank())
        assertTrue(stderr.toString().contains("Scan failed:"))
    }

    @Test
    fun run_whenFilteredToImpex_reportsImpexDomainScanWithoutRunningTypeSystemRules() {
        val repo = Files.createTempDirectory("sapcc-lint-scan-impex-domain")
        val itemsFile = repo.resolve("custom/core/resources/custom-items.xml")
        val impexFile = repo.resolve("resources/projectdata.impex")
        itemsFile.parent.createDirectories()
        impexFile.parent.createDirectories()
        itemsFile.writeText("<items/>")
        impexFile.writeText("INSERT Product;code[unique=true]\n;demo")

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exitCode = withRedirectedStreams(stdout, stderr) {
            ScanCommand().run(listOf("--repo", repo.toString(), "--domain", "impex"))
        }

        assertEquals(0, exitCode)
        val consoleOutput = stdout.toString()
        assertTrue(consoleOutput.contains("Scanned 1 file(s) across 1 domain(s)"))
        assertTrue(consoleOutput.contains("impex: 1 file(s)"))
        assertTrue(consoleOutput.contains("No findings."))
        assertTrue(stderr.toString().isBlank())
    }

    @Test
    fun run_whenFilteredToImpexAndSarifRequested_omitsTypeSystemRuleInventory() {
        val repo = Files.createTempDirectory("sapcc-lint-scan-impex-sarif")
        val itemsFile = repo.resolve("custom/core/resources/custom-items.xml")
        val impexFile = repo.resolve("resources/projectdata.impex")
        itemsFile.parent.createDirectories()
        impexFile.parent.createDirectories()
        itemsFile.writeText("<items/>")
        impexFile.writeText("INSERT Product;code[unique=true]\n;demo")
        val sarifOut = repo.resolve("build/reports/findings.sarif")

        val exitCode = ScanCommand().run(
            listOf(
                "--repo", repo.toString(),
                "--domain", "impex",
                "--format", "sarif",
                "--sarif-out", sarifOut.toString(),
            )
        )

        assertEquals(0, exitCode)
        val sarif = objectMapper.readTree(sarifOut.toFile())
        val ruleIds = sarif["runs"][0]["tool"]["driver"]["rules"].map { it["id"].asText() }
        assertTrue(ruleIds.isNotEmpty())
        assertTrue(ruleIds.all { it.startsWith("ImpEx") || it.startsWith("Impex") })
        assertEquals(0, sarif["runs"][0]["results"].size())
    }

    @Test
    fun run_whenExtensionInfoContainsDuplicateRequires_reportsProjectFinding() {
        val repo = Files.createTempDirectory("sapcc-lint-scan-project-duplicate")
        val extensionInfo = repo.resolve("custom/core/extensioninfo.xml")
        extensionInfo.parent.createDirectories()
        extensionInfo.writeText(
            """
            <extensioninfo>
              <extension name="customcore">
                <requires-extension name="cms2"/>
                <requires-extension name="CMS2"/>
              </extension>
            </extensioninfo>
            """.trimIndent()
        )

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = withRedirectedStreams(stdout, stderr) {
            ScanCommand().run(listOf("--repo", repo.toString(), "--domain", "project"))
        }

        assertEquals(1, exitCode)
        val consoleOutput = stdout.toString()
        assertTrue(consoleOutput.contains("project: 1 file(s)"))
        assertTrue(consoleOutput.contains("[project] EiDuplicateExtensionDefinition"))
        assertTrue(stderr.toString().isBlank())
    }

    @Test
    fun run_whenProjectContextIsComplete_reportsUnknownExtensions() {
        val repo = Files.createTempDirectory("sapcc-lint-scan-project-unknown")
        repo.resolve("bin/platform").createDirectories()
        val known = repo.resolve("custom/core/extensioninfo.xml")
        val localExtensions = repo.resolve("config/localextensions.xml")
        known.parent.createDirectories()
        localExtensions.parent.createDirectories()
        known.writeText(
            """
            <extensioninfo>
              <extension name="customcore">
                <requires-extension name="missingext"/>
              </extension>
            </extensioninfo>
            """.trimIndent()
        )
        localExtensions.writeText(
            """
            <hybrisconfig>
              <extensions>
                <extension name="missinglocal"/>
              </extensions>
            </hybrisconfig>
            """.trimIndent()
        )

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = withRedirectedStreams(stdout, stderr) {
            ScanCommand().run(listOf("--repo", repo.toString(), "--domain", "project"))
        }

        assertEquals(1, exitCode)
        val consoleOutput = stdout.toString()
        assertTrue(consoleOutput.contains("[project] EiUnknownExtensionDefinition"))
        assertTrue(consoleOutput.contains("[project] LeUnknownExtensionDefinition"))
        assertTrue(stderr.toString().isBlank())
    }

    @Test
    fun run_whenProjectContextIsPartial_skipsUnknownExtensionFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-scan-project-partial")
        val known = repo.resolve("custom/core/extensioninfo.xml")
        val localExtensions = repo.resolve("config/localextensions.xml")
        known.parent.createDirectories()
        localExtensions.parent.createDirectories()
        known.writeText(
            """
            <extensioninfo>
              <extension name="customcore">
                <requires-extension name="missingext"/>
              </extension>
            </extensioninfo>
            """.trimIndent()
        )
        localExtensions.writeText(
            """
            <hybrisconfig>
              <extensions>
                <extension name="missinglocal"/>
              </extensions>
            </hybrisconfig>
            """.trimIndent()
        )

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = withRedirectedStreams(stdout, stderr) {
            ScanCommand().run(listOf("--repo", repo.toString(), "--domain", "project"))
        }

        assertEquals(0, exitCode)
        assertTrue(stdout.toString().contains("No findings."))
        assertTrue(stderr.toString().isBlank())
    }

    @Test
    fun run_whenProjectEntriesHaveNoName_skipsUnknownExtensionRules() {
        val repo = Files.createTempDirectory("sapcc-lint-scan-project-missing-name")
        repo.resolve("bin/platform").createDirectories()
        val extensionInfo = repo.resolve("custom/core/extensioninfo.xml")
        val localExtensions = repo.resolve("config/localextensions.xml")
        extensionInfo.parent.createDirectories()
        localExtensions.parent.createDirectories()
        extensionInfo.writeText(
            """
            <extensioninfo>
              <extension name="customcore">
                <requires-extension/>
              </extension>
            </extensioninfo>
            """.trimIndent()
        )
        localExtensions.writeText(
            """
            <hybrisconfig>
              <extensions>
                <extension/>
              </extensions>
            </hybrisconfig>
            """.trimIndent()
        )

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = withRedirectedStreams(stdout, stderr) {
            ScanCommand().run(listOf("--repo", repo.toString(), "--domain", "project"))
        }

        assertEquals(0, exitCode)
        assertTrue(stdout.toString().contains("No findings."))
        assertTrue(stderr.toString().isBlank())
    }

    @Test
    fun run_whenManifestContainsUnknownReferences_reportsManifestFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-scan-manifest-errors")
        repo.resolve("bin/platform").createDirectories()
        val extensionInfo = repo.resolve("custom/core/extensioninfo.xml")
        val manifest = repo.resolve("core-customize/manifest.json")
        extensionInfo.parent.createDirectories()
        manifest.parent.createDirectories()
        extensionInfo.writeText(
            """
            <extensioninfo>
              <extension name="customcore"/>
            </extensioninfo>
            """.trimIndent()
        )
        manifest.writeText(
            """
            {
              "extensions": ["missingext"],
              "storefrontAddons": [
                {
                  "template": "missingtemplate"
                }
              ],
              "extensionPacks": [
                {
                  "name": "missing-pack"
                }
              ]
            }
            """.trimIndent()
        )

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = withRedirectedStreams(stdout, stderr) {
            ScanCommand().run(listOf("--repo", repo.toString(), "--domain", "manifest"))
        }

        assertEquals(1, exitCode)
        val consoleOutput = stdout.toString()
        assertTrue(consoleOutput.contains("manifest: 1 file(s)"))
        assertTrue(consoleOutput.contains("[manifest] ManifestUnknownExtensionInspection"))
        assertTrue(consoleOutput.contains("[manifest] ManifestUnknownTemplateExtensionInspection"))
        assertTrue(consoleOutput.contains("[manifest] ManifestUnknownExtensionPackInspection"))
        assertTrue(stderr.toString().isBlank())
    }

    @Test
    fun run_whenManifestIsScannedWithoutProjectDomainStillResolvesKnownExtensions() {
        val repo = Files.createTempDirectory("sapcc-lint-scan-manifest-support")
        repo.resolve("bin/platform").createDirectories()
        val extensionInfo = repo.resolve("custom/core/extensioninfo.xml")
        val manifest = repo.resolve("core-customize/manifest.json")
        extensionInfo.parent.createDirectories()
        manifest.parent.createDirectories()
        extensionInfo.writeText(
            """
            <extensioninfo>
              <extension name="knownext"/>
            </extensioninfo>
            """.trimIndent()
        )
        manifest.writeText(
            """
            {
              "extensions": ["knownext"],
              "storefrontAddons": [
                {
                  "template": "knownext"
                }
              ],
              "extensionPacks": [
                {
                  "name": "media-telco"
                }
              ]
            }
            """.trimIndent()
        )

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = withRedirectedStreams(stdout, stderr) {
            ScanCommand().run(listOf("--repo", repo.toString(), "--domain", "manifest"))
        }

        assertEquals(0, exitCode)
        assertTrue(stdout.toString().contains("No findings."))
        assertTrue(stderr.toString().isBlank())
    }

    @Test
    fun run_whenManifestContextIsPartial_skipsUnknownExtensionAndTemplateFindings() {
        val repo = Files.createTempDirectory("sapcc-lint-scan-manifest-partial")
        val manifest = repo.resolve("core-customize/manifest.json")
        manifest.parent.createDirectories()
        manifest.writeText(
            """
            {
              "extensions": ["missingext"],
              "storefrontAddons": [
                {
                  "template": "missingtemplate"
                }
              ]
            }
            """.trimIndent()
        )

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = withRedirectedStreams(stdout, stderr) {
            ScanCommand().run(listOf("--repo", repo.toString(), "--domain", "manifest"))
        }

        assertEquals(0, exitCode)
        assertTrue(stdout.toString().contains("No findings."))
        assertTrue(stderr.toString().isBlank())
    }

    private fun withRedirectedStreams(
        stdout: ByteArrayOutputStream,
        stderr: ByteArrayOutputStream,
        action: () -> Int,
    ): Int {
        val originalOut = System.out
        val originalErr = System.err
        System.setOut(PrintStream(stdout))
        System.setErr(PrintStream(stderr))

        return try {
            action()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }
}
