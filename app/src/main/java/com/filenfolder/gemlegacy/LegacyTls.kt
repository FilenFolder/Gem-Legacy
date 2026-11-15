package com.filenfolder.gemlegacy

import org.conscrypt.Conscrypt
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

object LegacyTls {

    @Volatile
    private var cached: SSLSocketFactory? = null

    fun socketFactory(): SSLSocketFactory {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val factory = try {
                conscryptFactory()
            } catch (t: Throwable) {
                platformFactory()
            }
            cached = factory
            return factory
        }
    }

    private fun conscryptFactory(): SSLSocketFactory {
        val provider = Conscrypt.newProvider()
        val trustManager = Conscrypt.getDefaultX509TrustManager()
        val context = SSLContext.getInstance("TLS", provider)
        context.init(null, arrayOf<TrustManager>(trustManager), null)
        return ProtocolFactory(context.socketFactory)
    }

    private fun platformFactory(): SSLSocketFactory {
        val context = SSLContext.getInstance("TLSv1.2")
        context.init(null, null, null)
        return ProtocolFactory(context.socketFactory)
    }

    private class ProtocolFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(): Socket = enable(delegate.createSocket())

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            enable(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String, port: Int): Socket =
            enable(delegate.createSocket(host, port))

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            enable(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress, port: Int): Socket =
            enable(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            enable(delegate.createSocket(address, port, localAddress, localPort))

        private fun enable(socket: Socket): Socket {
            if (socket is SSLSocket) {
                val usable = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
                if (usable.isNotEmpty()) socket.enabledProtocols = usable.toTypedArray()
            }
            return socket
        }
    }
}
