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

package com.github.sadikovi.riff

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

import com.github.sadikovi.riff.tree.FilterApi._
import com.github.sadikovi.testutil.implicits._
import com.github.sadikovi.testutil.UnitTestSuite

class FileReaderSuite extends UnitTestSuite {
  val schema = StructType(
    StructField("col1", IntegerType) ::
    StructField("col2", StringType) ::
    StructField("col3", LongType) :: Nil)

  val td = new TypeDescription(schema, Array("col2"))

  private def statistics(min: Int, max: Int, nulls: Boolean): Statistics = {
    val stats = Statistics.sqlTypeToStatistics(IntegerType)
    if (nulls) stats.update(InternalRow(null), 0)
    stats.update(InternalRow(min), 0)
    stats.update(InternalRow(max), 0)
    stats
  }

  private def statistics(min: Long, max: Long, nulls: Boolean): Statistics = {
    val stats = Statistics.sqlTypeToStatistics(LongType)
    if (nulls) stats.update(InternalRow(null), 0)
    stats.update(InternalRow(min), 0)
    stats.update(InternalRow(max), 0)
    stats
  }

  private def statistics(min: String, max: String, nulls: Boolean): Statistics = {
    val stats = Statistics.sqlTypeToStatistics(StringType)
    if (nulls) stats.update(InternalRow(null), 0)
    stats.update(InternalRow(UTF8String.fromString(min)), 0)
    stats.update(InternalRow(UTF8String.fromString(max)), 0)
    stats
  }

  test("initialize file reader for non-existent path") {
    withTempDir { dir =>
      val path = dir / "file"
      val reader = new FileReader(fs, new Configuration(), path)
      reader.headerPath() should be (new Path(s"file:$path"))
      reader.dataPath() should be (new Path(s"file:$path.data"))
      reader.bufferSize() should be (Riff.Options.BUFFER_SIZE_DEFAULT)
    }
  }

  test("initialize file reader with different buffer size") {
    withTempDir { dir =>
      val path = dir / "file"
      val conf = new Configuration()
      conf.setInt(Riff.Options.BUFFER_SIZE, Riff.Options.BUFFER_SIZE_MAX)
      val reader = new FileReader(fs, conf, path)
      reader.headerPath() should be (new Path(s"file:$path"))
      reader.dataPath() should be (new Path(s"file:$path.data"))
      reader.bufferSize() should be (Riff.Options.BUFFER_SIZE_MAX)
    }
  }

  test("check assertBytes") {
    var msg = intercept[AssertionError] {
      FileReader.assertBytes(null, null, "Test")
    }.getMessage
    msg should be ("Test: null != null")

    msg = intercept[AssertionError] {
      FileReader.assertBytes(new Array[Byte](4), new Array[Byte](2), "Test")
    }.getMessage
    msg should be ("Test: [0, 0, 0, 0] != [0, 0]")

    msg = intercept[AssertionError] {
      FileReader.assertBytes(Array[Byte](4), Array[Byte](2), "Test")
    }.getMessage
    msg should be ("Test: [4] != [2]")

    // should be okay
    FileReader.assertBytes(Array[Byte](1, 2, 3, 4), Array[Byte](1, 2, 3, 4), "Test")
  }

  test("evaluate stripes for null predicate state") {
    val stripes = Array(
      new StripeInformation(1.toByte, 0L, 100, null),
      new StripeInformation(2.toByte, 101L, 100, null),
      new StripeInformation(3.toByte, 202L, 100, null))
    val res = FileReader.evaluateStripes(stripes, null)
    res should be (stripes)
  }

  test("evaluate stripes for predicate state and no statistics") {
    val stripes = Array(
      new StripeInformation(2.toByte, 101L, 100, null),
      new StripeInformation(1.toByte, 0L, 100, null),
      new StripeInformation(3.toByte, 202L, 100, null))
    val state = new PredicateState(nvl("col1"), td)
    val res = FileReader.evaluateStripes(stripes, state)
    // must be sorted by offset
    res should be (Array(
      new StripeInformation(1.toByte, 0L, 100, null),
      new StripeInformation(2.toByte, 101L, 100, null),
      new StripeInformation(3.toByte, 202L, 100, null)))
  }

  test("evaluate stripes for predicate state - remove some stripes") {
    val stripes = Array(
      new StripeInformation(2.toByte, 101L, 100, Array(
        statistics("a", "z", false),
        statistics(1, 3, false),
        statistics(1L, 3L, false)
      )),
      new StripeInformation(1.toByte, 0L, 100, Array(
        statistics("a", "z", false),
        statistics(4, 5, false),
        statistics(1L, 3L, false)
      )),
      new StripeInformation(3.toByte, 202L, 100, Array(
        statistics("a", "z", false),
        statistics(1, 3, false),
        statistics(1L, 3L, false)
      )))
    val state = new PredicateState(eqt("col1", 5), td)
    val res = FileReader.evaluateStripes(stripes, state)
    // must be sorted by offset
    res should be (Array(stripes(1)))
  }
}