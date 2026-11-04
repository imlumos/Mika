package com.wangzan101.mika.backup

import android.content.Context
import android.content.Intent
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import java.io.File

/** 真机上的备份环境。[context] 可以是还没 attach 完的 base context:启动最早阶段换数据时用的就是它。 */
internal class AndroidBackupEnv(private val context: Context) : BackupEnv {
    override val filesDir: File get() = context.filesDir

    override fun cipher(alias: String): KeyCipher = KeystoreKeyCipher(alias)

    override fun readPrefs(name: String): Map<String, Any?> = context.getSharedPreferences(name, Context.MODE_PRIVATE).all

    override fun writePrefs(name: String, values: Map<String, Any?>) {
        val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
                is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
            }
        }
        check(edit.commit()) { "cannot write preferences $name" }
    }
}

/** 结束当前进程并重新打开 Mika;下次启动的最早阶段会换上准备好的数据。 */
internal fun restartApp(context: Context) {
    val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
    context.startActivity(Intent.makeRestartActivityTask(launch.component))
    Runtime.getRuntime().exit(0)
}
