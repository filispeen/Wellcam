package com.filispeen.WinCamServer.transport

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalAddresses {
    private val wifiPrefixes = listOf("wlan", "swlan", "ap")
    private val usbPrefixes = listOf("usb", "rndis", "ncm", "eth")

    fun find(connection: String): String? =
        find(if (connection == "USB") usbPrefixes else wifiPrefixes)

    private fun find(prefixes: List<String>): String? {
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
                usable && prefixes.any { nif.name.startsWith(it) }
            }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }
}
