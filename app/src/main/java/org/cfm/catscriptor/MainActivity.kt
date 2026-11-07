package org.cfm.catscriptor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.cfm.catscriptor.app.CatscriptorApp
import org.cfm.catscriptor.ui.navigation.StenografApp
import org.cfm.catscriptor.ui.theme.StenografTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as CatscriptorApp).graph
        setContent {
            StenografTheme(dynamicColor = graph.useDynamicColor) {
                StenografApp(graph)
            }
        }
    }

    /** Реализовано в нативной библиотеке 'catscriptor' (app/src/main/cpp/native-lib.cpp). */
    external fun stringFromJNI(): String

    companion object {
        init {
            System.loadLibrary("catscriptor")
        }
    }
}
