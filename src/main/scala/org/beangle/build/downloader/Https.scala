/*
 * Copyright (C) 2005, The Beangle Software.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.beangle.build.downloader

import java.net.HttpURLConnection._
import java.net.{HttpURLConnection, Socket, URL, URLConnection}
import java.security.cert.X509Certificate
import javax.net.ssl._

object Https {
  private val statusMap = Map(
    HTTP_OK -> "OK",
    HTTP_FORBIDDEN -> "Access denied!",
    HTTP_NOT_FOUND -> "Not Found",
    HTTP_UNAUTHORIZED -> "Access denied")

  def toString(httpCode: Int): String =
    statusMap.getOrElse(httpCode, String.valueOf(httpCode))

  def access(url: URL): ResourceStatus = {
    val hc = followRedirect(url.openConnection(), "HEAD")
    val rc = hc.getResponseCode
    rc match {
      case HTTP_OK =>
        val supportRange = "bytes" == hc.getHeaderField("Accept-Ranges")
        ResourceStatus(rc, hc.getURL, hc.getHeaderFieldLong("Content-Length", 0), hc.getLastModified, supportRange)
      case _ => ResourceStatus(rc, hc.getURL, -1, -1, supportRange = false)
    }
  }

  @scala.annotation.tailrec
  final def followRedirect(c: URLConnection, method: String): HttpURLConnection = {
    val conn = c.asInstanceOf[HttpURLConnection]
    conn.setRequestMethod(method)
    conn.setInstanceFollowRedirects(false)
    Https.noverify(conn)
    val rc = conn.getResponseCode
    rc match {
      case HTTP_OK => conn
      case HTTP_MOVED_TEMP | HTTP_MOVED_PERM =>
        val newLoc = conn.getHeaderField("location")
        followRedirect(Networks.url(newLoc).openConnection, method)
      case _ => conn
    }
  }

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

case class ResourceStatus(status: Int, target: URL, length: Long, lastModified: Long, supportRange: Boolean) {
  def isOk: Boolean =
    200 == status
}
