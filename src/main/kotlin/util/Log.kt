package `fun`.eternalblue.util

import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.LocalTime

object Log
{
    // Multi-account runs write from several threads; Windows rejects concurrent append.
    private val lock = Any()

    @Volatile
    private var logFile = File("logs/log.log")

    @Volatile
    var debugEnabled = false
        private set

    fun init(baseDir: File, debug: Boolean)
    {
        debugEnabled = debug
        logFile = File(File(baseDir, "logs"), "log.log")
        try
        {
            if(logFile.exists())
            {
                logFile.delete()
            }
            //如果log.log的父文件(logs)不存在，创建logs和log.log
            if (!logFile.parentFile.exists())
            {
                logFile.parentFile.mkdirs()
            }
            //如果log.log不存在，创建log.log
            if (!logFile.exists())
            {
                logFile.createNewFile()
            }
        }catch (e:Exception)
        {
            println(e.message)
        }
    }

    fun log(level:String, message:String)
    {
        val time = LocalTime.now()
        val logMessage = "[${level} - $time] : $message \n"
        try
        {
            synchronized(lock) {
                FileWriter(logFile,true).use { it -> it.write(logMessage) }
            }
        }catch (e:IOException)
        {
            println(e.message)
        }
    }

    fun info(message: String)
    {
        log("INFO",message)
    }

    fun debug(message: String)
    {
        if (debugEnabled) log("DEBUG",message)
    }

    fun error(message: String)
    {
        log("ERROR",message)
    }
}
