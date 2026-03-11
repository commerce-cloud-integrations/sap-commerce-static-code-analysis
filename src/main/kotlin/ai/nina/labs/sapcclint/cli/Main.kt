package ai.nina.labs.sapcclint.cli

import ai.nina.labs.sapcclint.core.AnalyzerApplication
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(AnalyzerApplication().run(args.toList()))
}
