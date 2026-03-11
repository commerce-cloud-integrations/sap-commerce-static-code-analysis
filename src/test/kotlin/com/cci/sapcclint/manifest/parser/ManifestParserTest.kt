package com.cci.sapcclint.manifest.parser

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class ManifestParserTest {

    private val parser = ManifestParser()

    @Test
    fun parse_whenManifestContainsSupportedReferenceShapes_collectsEachRuleTargetWithLocations() {
        val repo = Files.createTempDirectory("sapcc-lint-manifest-parser")
        val manifest = repo.resolve("core-customize/manifest.json")
        manifest.parent.createDirectories()
        manifest.writeText(
            """
            {
              "extensions": ["core"],
              "storefrontAddons": [
                {
                  "addon": "myaddon",
                  "storefront": "mystorefront",
                  "template": "templateext",
                  "addons": ["addonext"],
                  "storefronts": ["storefrontext"],
                  "webapps": [
                    {
                      "name": "webappext"
                    }
                  ]
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

        val parsed = parser.parse(manifest)

        assertEquals(listOf("core", "myaddon", "mystorefront", "webappext"), parsed.extensionReferences.map { it.value })
        assertEquals(listOf("templateext", "addonext", "storefrontext"), parsed.templateReferences.map { it.value })
        assertEquals(listOf("media-telco"), parsed.extensionPackReferences.map { it.value })
        assertEquals(2, parsed.extensionReferences.first().location.line)
        assertEquals(7, parsed.templateReferences.first().location.line)
        assertEquals(19, parsed.extensionPackReferences.first().location.line)
    }
}
