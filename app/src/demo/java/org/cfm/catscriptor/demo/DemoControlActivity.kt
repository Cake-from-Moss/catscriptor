package org.cfm.catscriptor.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.cfm.catscriptor.MainActivity

/**
 * Запуск экрана в заданном сценарии для проверки состояний:
 * adb shell am start -S -n org.cfm.catscriptor.demo/org.cfm.catscriptor.demo.DemoControlActivity -e scenario empty
 * Флаг -S обязателен: без перезапуска Android вернёт уже открытую задачу и сценарий не применится.
 */
class DemoControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("scenario")
        val scenario = DemoScenario.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: DemoScenario.DEFAULT
        (application as DemoApp).let { (it.graph as DemoAppGraph).applyScenario(scenario) }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
