package org.beangle.build.util

import java.io.{InputStream, OutputStream}

object IOs {
  private val defaultBufferSize = 1024 * 4

  private val eof = -1

  /** Copy bytes from a <code>InputStream</code> to an <code>OutputStream</code>.
   *
   * @param input  the <code>InputStream</code> to read from
   * @param output the <code>OutputStream</code> to write to
   * @return the number of bytes copied
   * @since 3.1
   */
  def copy(input: InputStream, output: OutputStream): Long = {
    val buffer = new Array[Byte](defaultBufferSize)
    var count = 0
    var n = input.read(buffer)
    while (eof != n) {
      output.write(buffer, 0, n)
      count += n
      n = input.read(buffer)
    }
    close(input)
    count
  }

  /** Close many objects quitely.
   * swallow any exception.
   */
  def close(objs: AutoCloseable*): Unit =
    objs foreach { obj =>
      try
        if (obj != null) obj.close()
      catch {
        case _: Exception =>
      }
    }
}
