package `fun`.eternalblue

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
        println("配置文件：${configFile.path}${if (configFile.exists()) "" else "（未找到，使用默认配置）"}")
        println("检查间隔：${options.intervalSeconds} 秒${if (options.once) "（仅检查一轮）" else ""}")
        if (options.debug) {
            println("调试模式：已开启，抓包写入 ${File(File(baseDir, "logs"), "debug").path}")
        }
        val multiAccount = options.isMultiAccount
        val accounts = options.effectiveAccounts().map { resolveCredentials(it, multiAccount) }
        if (accounts.size == 1) {
            val only = accounts.single()
            println(
                "签到定位：${only.locationText}" +
                    if (only.locations.isEmpty()) ""
                    else "，另有 ${only.locations.size} 个班课单独配置"
            )
        } else {
            println("多账户：${accounts.size} 个，并发运行")
            accounts.forEachIndexed { index, item ->
                println(
                    "  ${index + 1}. ${item.label}（${AutoSignService.maskAccount(item.account!!)}）" +
                        " 定位=${item.locationText}" +
                        if (item.locations.isEmpty()) "" else "，班课定位=${item.locations.size} 个"
                )
            }
        }
        if (accounts.size == 1) {
            AutoSignService(accounts.single()).run()
        } else {
            coroutineScope {
                accounts.map { account ->
                    async(Dispatchers.IO) {
                        try {
                            AutoSignService(account).run()
                        } catch (e: Exception) {
                            println("[${account.label}] 运行失败：${e.message ?: e::class.simpleName}")
                            Log.error("[${account.label}] 运行失败：${e.message ?: e::class.simpleName}")
                            1
                        }
                    }
                }.awaitAll().maxOrNull() ?: 0
            }
        }
    } catch (e: Exception) {
        val message = e.message ?: e::class.simpleName ?: "未知错误"
        println("运行失败：$message")
        Log.error(message)
        1
    }
    if (exitCode != 0) exitProcess(exitCode)
}

private fun resolveCredentials(options: AppConfig, multiAccount: Boolean = false): AppConfig {
    // Environment fallbacks belong to the single-account flow only; multi-account
    // entries must carry their own credentials.
    val allowEnv = !multiAccount
    val account = options.account?.trim()?.takeIf { it.isNotEmpty() }
        ?: if (allowEnv) System.getenv("YBK_ACCOUNT")?.trim()?.takeIf { it.isNotEmpty() } else null
    val password = options.password?.takeIf { it.isNotEmpty() }
        ?: if (allowEnv) System.getenv("YBK_PASSWORD")?.takeIf { it.isNotEmpty() } else null

    if (!multiAccount && account == null) {
        print("请输入云班课账户名(手机号或邮箱账号)：")
        return resolveCredentials(
            options.copy(account = readlnOrNull()?.trim(), password = password),
            multiAccount
        )
    }
    if (!multiAccount && password == null) {
        print("请输入云班课密码：")
        val input = System.console()?.readPassword()?.concatToString() ?: readlnOrNull()
        return resolveCredentials(options.copy(account = account, password = input), multiAccount)
    }
    require(!account.isNullOrEmpty()) { "${options.label ?: "账号"} 的账号不能为空" }
    require(!password.isNullOrEmpty()) { "${options.label ?: "账号"} 的密码不能为空" }
    return options.copy(account = account, password = password)
}
