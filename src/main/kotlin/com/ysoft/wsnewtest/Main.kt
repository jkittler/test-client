package com.ysoft.wsnewtest

import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("com.ysoft.wsnewtest.Main")

    val stepArg = args.firstOrNull { !it.startsWith("-") }
    val step = Step.parse(stepArg)
    if (step == null) {
        log.error("Unknown step '{}'. Valid steps: {}", stepArg, Step.names())
        exitProcess(Exit.CONFIG)
    }

    val cfg = try {
        ClientConfig.load()
    } catch (e: IllegalArgumentException) {
        log.error("Config error: {}", e.message)
        exitProcess(Exit.CONFIG)
    }

    val jwtSource = when {
        cfg.jwt != null -> "configured"
        cfg.login.enabled -> "auto-login"
        else -> "none"
    }
    log.info("wsnew test client -> {} (step={}, useHmac={}, jwt={}, sendRemoteDelivery={})",
        cfg.wsUrl, step.cliName, cfg.useHmac, jwtSource, cfg.sendRemoteDelivery)
    log.debug(cfg.describe())

    val ws = WsClient(cfg.wsUrl, trustAllCerts = cfg.trustAllCerts)
    val flow = Flow(cfg, ws)

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down...")
        flow.stop()
        ws.close()
    })

    val code = try {
        flow.runUpTo(step)
    } catch (e: Exception) {
        log.error("Unexpected error", e)
        Exit.CONNECT
    } finally {
        ws.close()
    }
    exitProcess(code)
}
