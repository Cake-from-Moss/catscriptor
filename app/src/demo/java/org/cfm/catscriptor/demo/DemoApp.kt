package org.cfm.catscriptor.demo

import org.cfm.catscriptor.app.AppGraph
import org.cfm.catscriptor.app.CatscriptorApp

/** Приложение демо-сборки: подменяет только граф зависимостей, экраны те же. */
class DemoApp : CatscriptorApp() {
    override fun createGraph(): AppGraph = DemoAppGraph(this)
}
