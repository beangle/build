package org.beangle.build.util


import java.net.{HttpURLConnection, Socket}
import java.security.cert.X509Certificate
import javax.net.ssl.*

object Https {

  def noverify(connection: HttpURLConnection): Unit =
    connection match {
      case conn: HttpsURLConnection =>
        conn.setHostnameVerifier(TrustAllHosts)
        val sslContext = SSLContext.getInstance("SSL", "SunJSSE");
        sslContext.init(null, Array(NullTrustManager), new java.security.SecureRandom());
        val ssf = sslContext.getSocketFactory()
        conn.setSSLSocketFactory(ssf)
      case _ =>
    }

  object NullTrustManager extends X509ExtendedTrustManager {
    override def checkClientTrusted(c: Array[X509Certificate], at: String): Unit = {
    }

    override def checkClientTrusted(c: Array[X509Certificate], at: String, engine: SSLEngine): Unit = {
    }

    override def checkClientTrusted(c: Array[X509Certificate], at: String, socket: Socket): Unit = {
    }

    override def checkServerTrusted(c: Array[X509Certificate], s: String): Unit = {
    }

    override def checkServerTrusted(c: Array[X509Certificate], s: String, engine: SSLEngine): Unit = {
    }

    override def checkServerTrusted(c: Array[X509Certificate], s: String, socket: Socket): Unit = {
    }

    override def getAcceptedIssuers(): Array[X509Certificate] =
      null
  }

  object TrustAllHosts extends HostnameVerifier {
    def verify(arg0: String, arg1: SSLSession): Boolean =
      true
  }
}
