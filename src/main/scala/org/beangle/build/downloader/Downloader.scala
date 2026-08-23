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

import java.util.concurrent.atomic.AtomicLong
import java.net.URL

object Downloader {
  class Status(var total: Long) {
    val count = new AtomicLong(0)
  }
}

trait Downloader {

  def url: URL

  def start(): Unit

  def downloaded: Long

  def contentLength: Long
}
