package net.espargos.espsdr

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var usb: UsbManager
    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var generation = 0
    private val permAction = "net.espargos.espsdr.USB_PERMISSION"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                permAction -> js("window.__serialPerm&&window.__serialPerm(${i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)})")
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { closePort(); js("window.__serialClosed&&window.__serialClosed()") }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> js("window.__serialEvent&&window.__serialEvent('connect')")
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val f = IntentFilter(permAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, f)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "AndroidSerial")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(r.url)

            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (r.url.host == "appassets.androidplatform.net") return false
                startActivity(Intent(Intent.ACTION_VIEW, r.url))
                return true
            }
        }
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        if (i.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) js("window.__serialEvent&&window.__serialEvent('connect')")
    }

    override fun onDestroy() {
        closePort()
        unregisterReceiver(receiver)
        web.destroy()
        super.onDestroy()
    }

    private fun js(code: String) { runOnUiThread { web.evaluateJavascript(code, null) } }

    private fun driver(): UsbSerialDriver? {
        UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull()?.let { return it }
        val d = usb.deviceList.values.firstOrNull { it.vendorId == 0x303A } ?: return null
        return CdcAcmSerialDriver(d)
    }

    private fun closePort() {
        generation++
        try { port?.close() } catch (_: Exception) {}
        port = null
    }

    inner class Bridge {
        @JavascriptInterface fun hasDevice(): Boolean = driver() != null

        @JavascriptInterface fun devices(): String =
            usb.deviceList.values.joinToString { "%04x:%04x".format(it.vendorId, it.productId) }

        @JavascriptInterface fun hasPermitted(): Boolean =
            driver()?.let { usb.hasPermission(it.device) } ?: false

        @JavascriptInterface fun info(): String =
            driver()?.device?.let { """{"usbVendorId":${it.vendorId},"usbProductId":${it.productId}}""" } ?: "{}"

        @JavascriptInterface fun share(name: String, text: String) {
            runOnUiThread {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, name)
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(i, name))
            }
        }

        @JavascriptInterface fun request() {
            val d = driver()?.device
            if (d == null) { js("window.__serialPerm(false)"); return }
            if (usb.hasPermission(d)) { js("window.__serialPerm(true)"); return }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(d, PendingIntent.getBroadcast(this@MainActivity, 0, Intent(permAction).setPackage(packageName), flags))
        }

        @JavascriptInterface fun open(baud: Int): String {
            closePort()
            val drv = driver() ?: return "No USB serial device found"
            val conn = usb.openDevice(drv.device) ?: return "USB permission denied"
            return try {
                val p = drv.ports[0]
                p.open(conn)
                p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                port = p
                val gen = ++generation
                Thread { readLoop(p, gen) }.apply { isDaemon = true }.start()
                ""
            } catch (e: Exception) { e.message ?: "Open failed" }
        }

        @JavascriptInterface fun write(b64: String): String {
            val p = port ?: return "Port not open"
            return try {
                p.write(Base64.decode(b64, Base64.DEFAULT), 2000)
                ""
            } catch (e: Exception) { e.message ?: "Write failed" }
        }

        @JavascriptInterface fun setSignals(dtr: Int, rts: Int) {
            val p = port ?: return
            try {
                if (dtr >= 0) p.setDTR(dtr == 1)
                if (rts >= 0) p.setRTS(rts == 1)
            } catch (_: Exception) {}
        }

        @JavascriptInterface fun close() = closePort()
    }

    private fun readLoop(p: UsbSerialPort, gen: Int) {
        val buf = ByteArray(16384)
        val out = ByteArrayOutputStream()
        val lock = Any()
        // Sender: forwards collected bytes to the page every ~12 ms
        Thread {
            try {
                while (gen == generation) {
                    Thread.sleep(12)
                    val data: ByteArray? = synchronized(lock) {
                        if (out.size() > 0) { val b = out.toByteArray(); out.reset(); b } else null
                    }
                    if (data != null) js("window.__serialRx('${Base64.encodeToString(data, Base64.NO_WRAP)}')")
                }
            } catch (_: InterruptedException) {}
        }.apply { isDaemon = true }.start()
        // Reader: blocking read without timeout, so no bytes are lost on timeouts
        try {
            while (gen == generation) {
                val n = p.read(buf, 0)
                if (n > 0) synchronized(lock) { out.write(buf, 0, n) }
            }
        } catch (_: Exception) {
            if (gen == generation) js("window.__serialClosed&&window.__serialClosed()")
        }
    }
}
