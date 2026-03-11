package com.cci.sapcclint.cli

import com.cci.sapcclint.core.AnalyzerApplication
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(AnalyzerApplication().run(args.toList()))
}
