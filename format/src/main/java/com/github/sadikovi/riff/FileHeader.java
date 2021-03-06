/*
 * Copyright (c) 2017 sadikovi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.sadikovi.riff;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;

import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.sadikovi.riff.io.ByteBufferStream;
import com.github.sadikovi.riff.io.OutputBuffer;
import com.github.sadikovi.riff.stats.Statistics;

/**
 * Header information for Riff file.
 * All format checks should be done here.
 */
public class FileHeader {
  private static final Logger LOG = LoggerFactory.getLogger(FileHeader.class);
  // state length in bytes
  private static final int STATE_LENGTH = 8;

  // byte array of file state
  private final byte[] state;
  // type description for file
  private final TypeDescription td;
  // custom file properties
  private final HashMap<String, String> properties;

  /**
   * Initialize file header with state and type description.
   * @param state file state
   * @param td file type description
   * @param properties custom file properties, can be null
   */
  public FileHeader(byte[] state, TypeDescription td, HashMap<String, String> properties) {
    if (state.length != STATE_LENGTH) {
      throw new IllegalArgumentException("Invalid state length, " +
        state.length + " != " + STATE_LENGTH);
    }
    this.state = state;
    this.td = td;
    this.properties = properties;
  }

  /**
   * Initialize file header with default state and type description.
   * State can be modified using `setState` method.
   * @param td type description
   * @param properties file properties, can be null
   */
  public FileHeader(TypeDescription td, HashMap<String, String> properties) {
    this(new byte[STATE_LENGTH], td, properties);
  }

  /**
   * Initialize file header with default state and type description.
   * State can be modified using `setState` method.
   * @param td type description
   */
  public FileHeader(TypeDescription td) {
    this(new byte[STATE_LENGTH], td, null);
  }

  /**
   * Set state for position in byte array.
   * @param pos position in array
   * @param flag value to set
   */
  public void setState(int pos, byte flag) {
    state[pos] = flag;
  }

  /**
   * Get type description.
   * @return type description
   */
  public TypeDescription getTypeDescription() {
    return td;
  }

  /**
   * Get state flag for posiiton.
   * @param pos position of the flag
   * @return state value
   */
  public byte state(int pos) {
    return state[pos];
  }

  /**
   * Get property.
   * @param key property key
   * @return value as String or null, if properties are not set, or no such key exists
   */
  public String getProperty(String key) {
    if (properties == null) return null;
    return properties.get(key);
  }

  /**
   * Write header into output stream.
   * Stream is not closed after this operation is complete.
   * @param out output stream
   * @throws IOException
   */
  public void writeTo(FSDataOutputStream out) throws IOException {
    OutputBuffer buffer = new OutputBuffer();
    // record file header
    buffer.write(state);
    td.writeTo(buffer);
    // write properties map, size -1 means that properties is null
    if (properties == null) {
      buffer.writeInt(-1);
    } else {
      byte[] bytes = null;
      buffer.writeInt(properties.size());
      for (String key : properties.keySet()) {
        // write key
        bytes = key.getBytes();
        buffer.writeInt(bytes.length);
        buffer.writeBytes(bytes);
        // write value
        bytes = properties.get(key).getBytes();
        buffer.writeInt(bytes.length);
        buffer.writeBytes(bytes);
      }
    }
    buffer.align();
    LOG.debug("Write header content of {} bytes", buffer.bytesWritten());
    // write magic 4 bytes + buffer length 4 bytes into output stream
    out.writeLong(((long) Riff.MAGIC << 32) + buffer.bytesWritten());
    // write buffer data
    buffer.writeExternal(out);
  }

  /**
   * Read header from input stream.
   * Stream is not closed after operation is complete.
   * @param in input stream
   * @throws IOException
   */
  public static FileHeader readFrom(FSDataInputStream in) throws IOException {
    // Read first 8 bytes: magic 4 bytes and length of the header 4 bytes
    long meta = in.readLong();
    int magic = (int) (meta >>> 32);
    if (magic != Riff.MAGIC) throw new IOException("Wrong magic: " + magic + " != " + Riff.MAGIC);
    int len = (int) (meta & 0x7fffffff);
    LOG.debug("Read header content of {} bytes", len);
    // read full header bytes
    ByteBuffer buffer = ByteBuffer.allocate(len);
    in.readFully(buffer.array(), buffer.arrayOffset(), buffer.limit());
    // no flip - we have not reset position
    // read byte state
    byte[] state = new byte[STATE_LENGTH];
    buffer.get(state);
    // read type description
    // currently type description supports serde from stream, therefore we create stream that
    // wraps byte buffer, stream updates position of buffer
    ByteBufferStream byteStream = new ByteBufferStream(buffer);
    TypeDescription td = TypeDescription.readFrom(byteStream);
    // read properties
    int propertiesLen = buffer.getInt();
    HashMap<String, String> properties = null;
    if (propertiesLen >= 0) {
      properties = new HashMap<String, String>();
      byte[] bytes = null;
      while (propertiesLen > 0) {
        // read key
        bytes = new byte[buffer.getInt()];
        buffer.get(bytes);
        String key = new String(bytes);
        // read value
        bytes = new byte[buffer.getInt()];
        buffer.get(bytes);
        String value = new String(bytes);
        properties.put(key, value);
        --propertiesLen;
      }
    }
    return new FileHeader(state, td, properties);
  }
}
