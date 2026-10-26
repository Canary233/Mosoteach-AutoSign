package `fun`.eternalblue

import `fun`.eternalblue.data.AccountOptions
import `fun`.eternalblue.data.AppConfig
import `fun`.eternalblue.service.AutoSignService
import `fun`.eternalblue.util.Debug
import `fun`.eternalblue.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) = runBlocking {
    System.setProperty("file.encoding", "UTF-8")
    System.setProperty("stdout.encoding", "UTF-8")
    System.setProperty("stderr.encoding", "UTF-8")

    val configFile = AppConfig.defaultConfigFile()
    val options = try {
        AppConfig.load(args, configFile)
    } catch (e: Exception) {
        println("配置或参数错误：${e.message}")
        println(AppConfig.USAGE)
        exitProcess(2)
    }

    if (options.help) {
        println(AppConfig.USAGE)
        return@runBlocking
    }
    if (options.version) {
        println("YunBanKe-AutoSign 2.0.0")
        return@runBlocking
    }

    val exitCode = try {
        val baseDir = configFile.parentFile ?: File(".")
        Log.init(baseDir, options.debug)
        Debug.configure(options.debug, baseDir)
        println("配置文件：${configFile.path}${if (configFile.exists()) "" else "（未找到）"}")
        println("检查间隔：${options.intervalSeconds} 秒${if (options.once) "（仅检查一轮）" else ""}")
        if (options.debug) {
            println("调试模式：已开启，抓包写入 ${File(File(baseDir, "logs"), "debug").path}")
        }

        val accounts = options.effectiveAccounts()
        if (accounts.isEmpty()) {
            println("config.toml 中没有配置任何账户，请至少添加一个 [[accounts]]。")
            println(AppConfig.USAGE)
            exitProcess(2)
        }
        println("账户：${accounts.size} 个，并发运行")
        accounts.forEachIndexed { index, item ->
            println(
                "  ${index + 1}. ${item.label}（${AutoSignService.maskAccount(item.account)}）" +
                    " 定位=${item.locationText}" +
                    if (item.locations.isEmpty()) "" else "，班课定位=${item.locations.size} 个"
            )
        }

        coroutineScope {
            accounts.map { account ->
                async(Dispatchers.IO) { runAccount(account) }
            }.awaitAll().maxOrNull() ?: 0
        }
    } catch (e: Exception) {
        val message = e.message ?: e::class.simpleName ?: "未知错误"
        println("运行失败：$message")
        Log.error(message)
        1
    }
    if (exitCode != 0) exitProcess(exitCode)
}

private suspend fun runAccount(account: AccountOptions): Int = try {
    AutoSignService(account).run()
} catch (e: Exception) {
    val reason = e.message ?: e::class.simpleName ?: "未知错误"
    println("[${account.label}] 运行失败：$reason")
    Log.error("[${account.label}] 运行失败：$reason")
    1
}
