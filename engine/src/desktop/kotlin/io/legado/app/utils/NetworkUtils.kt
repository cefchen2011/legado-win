// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 io/legado/app/utils/NetworkUtils.kt 中，真正被引擎使用的只有
// getAbsoluteURL / getBaseUrl / getDomain / getSubDomain 四个纯 URL 函数，
// 其余部分是 Android 连通性判断。这里忠实移植纯函数，网络可用性改为
// 桌面端语义（默认可用，可由应用外壳探测后设置）。
package io.legado.app.utils

import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.URL
import java.util.BitSet
import java.util.Enumeration

@Suppress("unused", "MemberVisibilityCanBePrivate")
object NetworkUtils {

    /**
     * 桌面端没有 ConnectivityManager，默认认为网络可用；
     * 应用外壳可以在探测到断网时把它设为 false，行为与上游一致。
     */
    @Volatile
    var available: Boolean = true

    fun isAvailable(): Boolean = available

    private val notNeedEncodingQuery: BitSet by lazy {
        val bitSet = BitSet(256)
        for (i in 'a'.code..'z'.code) bitSet.set(i)
        for (i in 'A'.code..'Z'.code) bitSet.set(i)
        for (i in '0'.code..'9'.code) bitSet.set(i)
        for (char in "!$&()*+,-./:;=?@[\\]^_`{|}~") bitSet.set(char.code)
        bitSet
    }

    private val notNeedEncodingForm: BitSet by lazy {
        val bitSet = BitSet(256)
        for (i in 'a'.code..'z'.code) bitSet.set(i)
        for (i in 'A'.code..'Z'.code) bitSet.set(i)
        for (i in '0'.code..'9'.code) bitSet.set(i)
        for (char in "*-._") bitSet.set(char.code)
        bitSet
    }

    /**
     * 支持 JAVA 的 URLEncoder.encode 出来的 string 做判断，即把 ' ' 转成 '+'。
     * 0-9a-zA-Z 保留，! * ' ( ) ; : @ & = + $ , / ? # [ ] 保留，
     * 其他字符转成 %XX 格式。
     */
    fun encodedQuery(str: String): Boolean {
        var needEncode = false
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (notNeedEncodingQuery.get(c.code)) {
                i++
                continue
            }
            if (c == '%' && i + 2 < str.length) {
                val c1 = str[++i]
                val c2 = str[++i]
                if (isDigit16Char(c1) && isDigit16Char(c2)) {
                    i++
                    continue
                }
            }
            needEncode = true
            break
        }
        return !needEncode
    }

    fun encodedForm(str: String): Boolean {
        var needEncode = false
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (notNeedEncodingForm.get(c.code)) {
                i++
                continue
            }
            if (c == '%' && i + 2 < str.length) {
                val c1 = str[++i]
                val c2 = str[++i]
                if (isDigit16Char(c1) && isDigit16Char(c2)) {
                    i++
                    continue
                }
            }
            needEncode = true
            break
        }
        return !needEncode
    }

    private fun isDigit16Char(c: Char): Boolean {
        return c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f'
    }

    /** 获取绝对地址 */
    fun getAbsoluteURL(baseURL: String?, relativePath: String): String {
        if (baseURL.isNullOrEmpty()) return relativePath.trim()
        var absoluteUrl: URL? = null
        try {
            absoluteUrl = URL(baseURL.substringBefore(","))
        } catch (e: Exception) {
            e.printOnDebug()
        }
        return getAbsoluteURL(absoluteUrl, relativePath)
    }

    /** 获取绝对地址 */
    fun getAbsoluteURL(baseURL: URL?, relativePath: String): String {
        val relativePathTrim = relativePath.trim()
        if (baseURL == null) return relativePathTrim
        if (relativePathTrim.isAbsUrl()) return relativePathTrim
        if (relativePathTrim.isDataUrl()) return relativePathTrim
        if (relativePathTrim.startsWith("javascript")) return ""
        var relativeUrl = relativePathTrim
        try {
            relativeUrl = URL(baseURL, relativePath).toString()
            return relativeUrl
        } catch (e: Exception) {
            // 上游此处写 AppLog；桌面端改用日志，语义不变
            LogUtils.e("NetworkUtils", "网址拼接出错 ${e.localizedMessage}")
        }
        return relativeUrl
    }

    fun getBaseUrl(url: String?): String? {
        url ?: return null
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            val index = url.indexOf("/", 9)
            return if (index == -1) url else url.substring(0, index)
        }
        return null
    }

    /**
     * 获取域名，供 cookie 保存和读取，处理失败返回传入的 url
     * http://1.2.3.4 => 1.2.3.4
     * https://www.example.com => example.com
     * http://www.biquge.com.cn => biquge.com.cn
     * http://www.content.example.com => example.com
     */
    fun getSubDomain(url: String): String {
        val baseUrl = getBaseUrl(url) ?: return url
        return runCatching {
            val host = URL(baseUrl).host
            if (isIPAddress(host)) return host
            effectiveTldPlusOne(host) ?: host
        }.getOrDefault(baseUrl)
    }

    fun getSubDomainOrNull(url: String): String? {
        val baseUrl = getBaseUrl(url) ?: return null
        return runCatching {
            val host = URL(baseUrl).host
            if (isIPAddress(host)) return host
            effectiveTldPlusOne(host) ?: host
        }.getOrDefault(null)
    }

    fun getDomain(url: String): String {
        val baseUrl = getBaseUrl(url) ?: return url
        return runCatching { URL(baseUrl).host }.getOrDefault(baseUrl)
    }

    /**
     * 上游用 okhttp 内部的 PublicSuffixDatabase 取有效顶级域名+1。
     * 桌面端改为等价的内置实现：按公共后缀规则取"注册域名"部分，
     * 覆盖 legado 的实际使用场景（普通域名；多级公共后缀按已知列表处理）。
     */
    private val multiPartSuffixes = setOf(
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "net.uk", "sch.uk",
        "com.hk", "org.hk", "edu.hk", "gov.hk", "net.hk", "idv.hk",
        "com.tw", "org.tw", "edu.tw", "gov.tw", "net.tw",
        "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp",
        "com.au", "net.au", "org.au", "edu.au", "gov.au",
        "co.kr", "or.kr", "com.sg", "com.my", "com.br", "com.mx", "com.tr",
        "co.in", "com.ru", "com.ua", "com.pl"
    )

    private fun effectiveTldPlusOne(host: String): String? {
        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return null
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in multiPartSuffixes) {
            if (labels.size < 3) host else labels.takeLast(3).joinToString(".")
        } else {
            lastTwo
        }
    }

    /** Get local Ip address. */
    fun getLocalIPAddress(): List<InetAddress> {
        val enumeration: Enumeration<NetworkInterface>
        try {
            enumeration = NetworkInterface.getNetworkInterfaces()
        } catch (e: SocketException) {
            e.printOnDebug()
            return emptyList()
        }
        val addressList = mutableListOf<InetAddress>()
        while (enumeration.hasMoreElements()) {
            val nif = enumeration.nextElement()
            val addresses = nif.inetAddresses ?: continue
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && isIPv4Address(address.hostAddress)) {
                    addressList.add(address)
                }
            }
        }
        return addressList
    }

    private val ipv4Regex = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")

    fun isIPv4Address(input: String?): Boolean {
        return input != null && input.isNotEmpty() &&
            input[0] in '1'..'9' &&
            input.count { it == '.' } == 3 &&
            ipv4Regex.matches(input)
    }

    fun isIPv6Address(input: String?): Boolean {
        if (input == null || !input.contains(":")) return false
        return runCatching {
            // 借助 InetAddress 的解析能力做校验，等价于上游 Validator.isIpv6
            InetAddress.getByName(input) is java.net.Inet6Address
        }.getOrDefault(false)
    }

    fun isIPAddress(input: String?): Boolean {
        return isIPv4Address(input) || isIPv6Address(input)
    }
}
