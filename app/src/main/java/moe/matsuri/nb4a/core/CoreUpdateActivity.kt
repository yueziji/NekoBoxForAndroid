package moe.matsuri.nb4a.core

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.triggerFullRestart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class CoreUpdateActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val buttons = mutableListOf<Button>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).build()
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法打开内核包" }
                CoreLoader.install(input)
            }
            "内核包已校验并安装。点击“重启并应用”生效。"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "内核更新"
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        status = TextView(this).apply { textSize = 16f }
        column.addView(TextView(this).apply { text = "NekoBox 动态内核"; textSize = 24f })
        column.addView(status)
        column.addView(TextView(this).apply {
            text = "\n更新源：yueziji/NekoBoxForAndroid Releases\n仅接受签名可信且接口兼容的内核包。切换需要重启 App，会中断当前连接。\n"
        })
        fun button(label: String, action: () -> Unit) {
            val b = Button(this).apply { text = label; setOnClickListener { action() } }
            buttons.add(b)
            column.addView(b)
        }
        button("检查并下载内核更新") { perform { download(); "内核包已校验并安装。重启后生效。" } }
        button("导入本地内核包") { pick.launch(arrayOf("application/zip", "application/octet-stream")) }
        button("恢复内置内核") { perform { CoreLoader.useBuiltin(); "已选择内置内核。重启后生效。" } }
        button("重启并应用") {
            MaterialAlertDialogBuilder(this).setTitle("重启并应用内核？")
                .setMessage("当前代理连接将断开，App 重启后请重新连接。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> triggerFullRestart(this) }.show()
        }
        button("返回") { finish() }
        setContentView(ScrollView(this).apply { addView(column) })
        refresh()
    }

    private fun refresh(message: String = "") {
        status.text = runCatching { CoreLoader.status() }.getOrElse { "无法读取内核状态" } + "\n" + message
    }

    private fun perform(action: () -> String) {
        // Staging while an old background process is being restarted could mix generations.
        if (DataStore.serviceState.started) {
            refresh("请先断开代理，再安装或切换内核。")
            return
        }
        buttons.forEach { it.isEnabled = false }
        refresh("正在处理…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(action) }
            buttons.forEach { it.isEnabled = true }
            refresh(result.getOrElse { it.message ?: "内核更新失败，当前内核保持不变" })
        }
    }

    private fun download() {
        val request = Request.Builder()
            .url("https://api.github.com/repos/yueziji/NekoBoxForAndroid/releases?per_page=30")
            .header("Accept", "application/vnd.github+json").build()
        val releases = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "无法查询更新（HTTP ${response.code}）" }
            JSONArray(response.body.string())
        }
        val assetName = "nekobox-core-${CoreLoader.abi()}.zip"
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft") || !release.getString("tag_name").startsWith("dynamic-core-")) continue
            val assets = release.getJSONArray("assets")
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                if (asset.getString("name") != assetName) continue
                require(asset.getLong("size") in 1..(CoreBundle.MAX_LIBRARY + 65536)) { "内核包大小异常" }
                val url = asset.getString("browser_download_url")
                require(url.startsWith("https://github.com/yueziji/NekoBoxForAndroid/releases/download/")) { "内核下载来源无效" }
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    check(response.isSuccessful) { "内核下载失败（HTTP ${response.code}）" }
                    CoreLoader.install(response.body.byteStream())
                }
                return
            }
        }
        error("暂时没有为此架构发布的动态内核包。")
    }
}
