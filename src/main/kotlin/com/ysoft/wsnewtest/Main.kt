package com.ysoft.wsnewtest

import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

fun main() {
    val log = LoggerFactory.getLogger("com.ysoft.wsnewtest.Main")

    val cfg = try {
        ClientConfig.load()
    } catch (e: IllegalArgumentException) {
        log.error("Config error: {}", e.message)
        exitProcess(Exit.CONFIG)
    }

    log.info("wsnew test client -> {} (useHmac={}, jwt={}, sendRemoteDelivery={})",
        cfg.wsUrl, cfg.useHmac, cfg.jwt != null, cfg.sendRemoteDelivery)

    val ws = WsClient(cfg.wsUrl, trustAllCerts = cfg.trustAllCerts)
    val flow = Flow(cfg, ws)

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down...")
        flow.stop()
        ws.close()
    })

    val code = try {
        flow.run()
    } catch (e: Exception) {
        log.error("Unexpected error", e)
        Exit.CONNECT
    } finally {
        ws.close()
    }
    exitProcess(code)
}
