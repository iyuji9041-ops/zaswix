package com.zaswis.play.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Cloudflare DNS over HTTPS (DoH) configuration for OkHttp.
 * Points to https://cloudflare-dns.com/dns-query with bootstrap hosts 1.1.1.1 and 1.0.0.1
 * to bypass ISP DNS blocking and domain filtering.
 */
object CloudflareDns {

    private val dohDns: Dns by lazy {
        try {
            val bootstrapClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("1.0.0.1")
                )
                .build()
        } catch (e: Exception) {
            e.printStackTrace()
            Dns.SYSTEM
        }
    }

    /**
     * Resilient DNS provider: Tries Cloudflare DoH first; falls back to system DNS
     * if DoH resolution encounters an unexpected failure.
     */
    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                dohDns.lookup(hostname)
            } catch (e: Exception) {
                try {
                    Dns.SYSTEM.lookup(hostname)
                } catch (systemEx: Exception) {
                    throw e
                }
            }
        }
    }
}
