package com.filispeen.Wellcam.transport

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalAddresses {
    private val wifiPrefixes = listOf("wlan", "swlan", "ap")

    fun find(): String? {
        val raw = try {
            NetworkInterface.getNetworkInterfaces()?.toList()
        } catch (e: Exception) {
            null
        }
        val interfaces = raw ?: return null
        return interfaces
            .filter { nif ->
                val usable = try {
                    nif.isUp && !nif.isLoopback
                } catch (e: Exception) {
                    false
                }
                usable && wifiPrefixes.any { nif.name.startsWith(it) }
            }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }
}
