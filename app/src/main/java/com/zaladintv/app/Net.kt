package com.zaladintv.app

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** DNS choices of the settings page. */
object DnsChoice {
    const val SYSTEM = "system"
    const val ADGUARD = "adguard"
    const val ADGUARD_FAMILY = "adguard_family"
    const val ADGUARD_UNFILTERED = "adguard_unfiltered"
    const val CLOUDFLARE = "cloudflare"
}

/**
 * DNS for every request the app makes (catalogues, images, video streams), switchable at runtime from the settings.
 * Uses DNS-over-HTTPS so the network cannot override the choice. Android does not let a normal app change the DNS of
 * the whole device without a VPN, so this only covers ZaladinTV's own traffic.
 */
class AppDns(private val choice: () -> String) : Dns {
    private data class Provider(val url: String, val bootstrap: List<String>)

    private val providers = mapOf(
        DnsChoice.ADGUARD to Provider("https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15")),
        DnsChoice.ADGUARD_FAMILY to Provider("https://family.adguard-dns.com/dns-query", listOf("94.140.14.15", "94.140.15.16")),
        DnsChoice.ADGUARD_UNFILTERED to Provider("https://unfiltered.adguard-dns.com/dns-query", listOf("94.140.14.140", "94.140.14.141")),
        DnsChoice.CLOUDFLARE to Provider("https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
    )

    /** Plain client for the DoH requests themselves (it must not use this resolver: it would loop). */
    private val bootstrapClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val resolvers = ConcurrentHashMap<String, Dns>()

    private fun resolver(key: String): Dns {
        val p = providers[key] ?: return Dns.SYSTEM
        return resolvers.getOrPut(key) {
            DnsOverHttps.Builder().client(bootstrapClient).url(p.url.toHttpUrl())
                .bootstrapDnsHosts(p.bootstrap.map { InetAddress.getByName(it) }).includeIPv6(false).build()
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // Local addresses (a box on the home network) never go to a public resolver.
        if (hostname == "localhost" || hostname.endsWith(".local") || IP.matches(hostname)) return Dns.SYSTEM.lookup(hostname)
        return resolver(choice()).lookup(hostname)
    }

    companion object { private val IP = Regex("""^\d{1,3}(\.\d{1,3}){3}$""") }
}

/** The one HTTP client of the app: catalogues, updates, images (Coil) and video (Media3) all go through it. */
object Net {
    @Volatile var dns: String = DnsChoice.SYSTEM

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder().dns(AppDns { dns }).connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
    }

    /** GET [url]; the caller closes the response (use { }). */
    fun get(url: String, connectMs: Long = 12_000, readMs: Long = 25_000, follow: Boolean = true, headers: Map<String, String> = emptyMap()): Response {
        val c = client.newBuilder().connectTimeout(connectMs, TimeUnit.MILLISECONDS).readTimeout(readMs, TimeUnit.MILLISECONDS)
            .followRedirects(follow).followSslRedirects(follow).build()
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        return c.newCall(rb.build()).execute()
    }
}
