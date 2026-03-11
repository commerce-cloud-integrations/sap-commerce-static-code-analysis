package com.cci.sapcclint.manifest.parser

import com.cci.sapcclint.itemsxml.model.SourcePosition
import com.cci.sapcclint.manifest.model.ManifestStringRef
import com.cci.sapcclint.manifest.model.ParsedManifestFile
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import java.nio.file.Files
import java.nio.file.Path

class ManifestParser(
    private val jsonFactory: JsonFactory = JsonFactory(),
) {

    fun parse(path: Path): ParsedManifestFile {
        val extensionReferences = mutableListOf<ManifestStringRef>()
        val templateReferences = mutableListOf<ManifestStringRef>()
        val extensionPackReferences = mutableListOf<ManifestStringRef>()

        Files.newInputStream(path).use { input ->
            jsonFactory.createParser(input).use { parser ->
                val firstToken = parser.nextToken() ?: throw IllegalArgumentException("Expected JSON content in $path")
                require(firstToken == JsonToken.START_OBJECT) { "Expected JSON object root in $path" }

                val rootLocation = parser.currentPosition()
                parseValue(
                    parser = parser,
                    path = emptyList(),
                    extensionReferences = extensionReferences,
                    templateReferences = templateReferences,
                    extensionPackReferences = extensionPackReferences,
                )

                return ParsedManifestFile(
                    path = path,
                    extensionReferences = extensionReferences,
                    templateReferences = templateReferences,
                    extensionPackReferences = extensionPackReferences,
                    location = rootLocation,
                )
            }
        }
    }

    private fun parseValue(
        parser: JsonParser,
        path: List<String>,
        extensionReferences: MutableList<ManifestStringRef>,
        templateReferences: MutableList<ManifestStringRef>,
        extensionPackReferences: MutableList<ManifestStringRef>,
    ) {
        when (parser.currentToken()) {
            JsonToken.START_OBJECT -> parseObject(parser, path, extensionReferences, templateReferences, extensionPackReferences)
            JsonToken.START_ARRAY -> parseArray(parser, path, extensionReferences, templateReferences, extensionPackReferences)
            JsonToken.VALUE_STRING -> captureStringValue(
                parser = parser,
                path = path,
                extensionReferences = extensionReferences,
                templateReferences = templateReferences,
                extensionPackReferences = extensionPackReferences,
            )

            else -> Unit
        }
    }

    private fun parseObject(
        parser: JsonParser,
        path: List<String>,
        extensionReferences: MutableList<ManifestStringRef>,
        templateReferences: MutableList<ManifestStringRef>,
        extensionPackReferences: MutableList<ManifestStringRef>,
    ) {
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            val fieldName = parser.currentName()
            parser.nextToken()
            parseValue(
                parser = parser,
                path = path + fieldName,
                extensionReferences = extensionReferences,
                templateReferences = templateReferences,
                extensionPackReferences = extensionPackReferences,
            )
        }
    }

    private fun parseArray(
        parser: JsonParser,
        path: List<String>,
        extensionReferences: MutableList<ManifestStringRef>,
        templateReferences: MutableList<ManifestStringRef>,
        extensionPackReferences: MutableList<ManifestStringRef>,
    ) {
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            parseValue(
                parser = parser,
                path = path,
                extensionReferences = extensionReferences,
                templateReferences = templateReferences,
                extensionPackReferences = extensionPackReferences,
            )
        }
    }

    private fun captureStringValue(
        parser: JsonParser,
        path: List<String>,
        extensionReferences: MutableList<ManifestStringRef>,
        templateReferences: MutableList<ManifestStringRef>,
        extensionPackReferences: MutableList<ManifestStringRef>,
    ) {
        val reference = ManifestStringRef(
            value = parser.valueAsString,
            location = parser.currentPosition(),
        )

        when {
            isExtensionReference(path) -> extensionReferences += reference
            isTemplateReference(path) -> templateReferences += reference
            isExtensionPackReference(path) -> extensionPackReferences += reference
        }
    }

    private fun isExtensionReference(path: List<String>): Boolean {
        return path == listOf("extensions") ||
            path == listOf("storefrontAddons", "addon") ||
            path == listOf("storefrontAddons", "storefront") ||
            path == listOf("storefrontAddons", "webapps", "name")
    }

    private fun isTemplateReference(path: List<String>): Boolean {
        return path == listOf("storefrontAddons", "template") ||
            path == listOf("storefrontAddons", "addons") ||
            path == listOf("storefrontAddons", "storefronts")
    }

    private fun isExtensionPackReference(path: List<String>): Boolean {
        return path == listOf("extensionPacks", "name")
    }

    private fun JsonParser.currentPosition(): SourcePosition {
        val location = currentTokenLocation()
        return SourcePosition(
            line = location.lineNr,
            column = location.columnNr,
        )
    }
}
