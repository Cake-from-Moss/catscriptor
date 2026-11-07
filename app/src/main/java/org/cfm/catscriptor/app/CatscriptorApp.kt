package org.cfm.catscriptor.app

import android.app.Application

/** Держит граф зависимостей на всё время жизни процесса. Демо-сборка подменяет [createGraph]. */
open class CatscriptorApp : Application() {
    val graph: AppGraph by lazy { createGraph() }

    protected open fun createGraph(): AppGraph = ProductionAppGraph(this)
}
