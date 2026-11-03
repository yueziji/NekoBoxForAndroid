package moe.matsuri.nb4a.core

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl

/** Synthetic configurations only: no real nodes or user credentials. */
object CoreConfigProbe {
    @JvmStatic fun verify(): Int {
        val profiles = listOf(
            SOCKSBean(),
            ShadowsocksBean().apply { password = "ci-placeholder" },
            VMessBean().apply { uuid = "00000000-0000-4000-8000-000000000001" },
            VMessBean().apply { alterId = -1; uuid = "00000000-0000-4000-8000-000000000001" },
            TrojanBean().apply { password = "ci-placeholder" },
            HysteriaBean().apply { authPayload = "ci-placeholder" }
        )
        val oldMode = DataStore.serviceMode
        val oldFakeDns = DataStore.enableFakeDns
        var count = 0
        try {
            DataStore.serviceMode = Key.MODE_VPN
            for (fakeDns in listOf(false, true)) {
                DataStore.enableFakeDns = fakeDns
                for (bean in profiles) {
                    bean.initializeDefaultValues()
                    bean.serverAddress = "192.0.2.1"
                    bean.serverPort = 443
                    val profile = ProxyEntity(id = 100).putBean(bean)
                    try {
                        // Construct the real GUI configuration, including TUN / DNS / routing.
                        // No network connection or Android VPN permission is needed until start().
                        val box = Libcore.newSingBoxInstance(buildConfig(profile).config, LocalResolverImpl)
                        box.close()
                        count++
                    } catch (error: Exception) {
                        throw IllegalStateException("Configuration rejected: ${bean.javaClass.simpleName}, fakeDNS=$fakeDns (${error.javaClass.simpleName})")
                    }
                }
            }
        } finally {
            DataStore.serviceMode = oldMode
            DataStore.enableFakeDns = oldFakeDns
        }
        return count
    }
}
