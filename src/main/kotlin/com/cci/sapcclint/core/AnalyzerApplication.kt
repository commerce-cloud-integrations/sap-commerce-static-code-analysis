package com.cci.sapcclint.core

/**
 * Main application entrypoint for the SAP Commerce static analyzer CLI.
 */
class AnalyzerApplication {

    fun run(args: List<String>): Int {
        return when {
            args.isEmpty() -> {
                printUsage()
                1
            }

            args.first() == "scan" -> {
                val command = ScanCommand()
                command.run(args.drop(1))
            }

            else -> {
                System.err.println("Unknown command: ${args.first()}")
                printUsage()
                1
            }
        }
    }

    private fun printUsage() {
        println(
            """
            Usage:
              sapcc-lint scan --repo <path> [--config <path>] [--domain <type-system|project|manifest|impex|bean-system|cockpit-ng|business-process>] [--format <console|html|csv|sarif|rdjsonl>] [--html-out <path>] [--csv-out <path>] [--sarif-out <path>] [--rdjsonl-out <path>]
            """.trimIndent()
        )
    }
}
