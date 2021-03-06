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

package com.github.sadikovi.spark.riff

import scala.collection.JavaConverters._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.hadoop.mapreduce.{Job, TaskAttemptContext}

import org.apache.spark.TaskContext
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow
import org.apache.spark.sql.riff._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.sources.{And, DataSourceRegister, Filter}
import org.apache.spark.sql.types.StructType

import org.slf4j.LoggerFactory

import com.github.sadikovi.hadoop.riff.RiffOutputCommitter
import com.github.sadikovi.riff.{Riff, TypeDescription}
import com.github.sadikovi.riff.Riff.Options
import com.github.sadikovi.riff.row.ProjectionRow

/**
 * Spark SQL datasource for Riff file format.
 */
class DefaultSource
  extends FileFormat
  with DataSourceRegister
  with Serializable {

  import RiffFileFormat._

  // logger for file format, since we cannot use internal Spark logging
  @transient private val log = LoggerFactory.getLogger(classOf[DefaultSource])
  // type description for this source, when inferring schema
  private var typeDescription: TypeDescription = null

  override def shortName(): String = "riff"

  override def toString(): String = "Riff"

  override def hashCode(): Int = getClass.hashCode()

  override def equals(obj: Any): Boolean = obj.isInstanceOf[DefaultSource]

  override def prepareWrite(
      sparkSession: SparkSession,
      job: Job,
      options: Map[String, String],
      dataSchema: StructType): OutputWriterFactory = {
    // create type description and try extracting index fields if any
    // if index fields are not set, pass empty array value
    val indexFields: Array[String] = options.get(INDEX_FIELDS_OPTION) match {
      case Some(value) => parseIndexFields(value)
      case None => Array.empty
    }
    log.info(s"Found optional index fields as ${indexFields.mkString("[", ", ", "]")}")
    val typeDesc = new TypeDescription(dataSchema, indexFields)
    log.info(s"Using type description $typeDesc to write output")

    // set job configuration based on provided Spark options, if not set - noop
    val conf = job.getConfiguration()
    // set compression codec, riff does not have default compression codec value, therefore
    // we just select one ourselves
    conf.set(Options.COMPRESSION_CODEC,
      sparkSession.conf.get(SQL_RIFF_COMPRESSION_CODEC, SQL_RIFF_COMPRESSION_CODEC_DEFAULT))

    // set stripe size
    conf.set(Options.STRIPE_ROWS,
      sparkSession.conf.get(SQL_RIFF_STRIPE_ROWS, s"${Options.STRIPE_ROWS_DEFAULT}"))

    // set column filters
    conf.set(Options.COLUMN_FILTER_ENABLED,
      sparkSession.conf.get(SQL_RIFF_COLUMN_FILTER_ENABLED,
        s"${Options.COLUMN_FILTER_ENABLED_DEFAULT}"))

    // set buffer size for outstream in riff
    conf.set(Options.BUFFER_SIZE,
      sparkSession.conf.get(SQL_RIFF_BUFFER_SIZE, s"${Options.BUFFER_SIZE_DEFAULT}"))

    val committerClass = classOf[RiffOutputCommitter]
    log.info(s"Using output committer for Riff: ${committerClass.getCanonicalName}")
    conf.setClass(SPARK_OUTPUT_COMMITTER_CLASS, committerClass, classOf[RiffOutputCommitter])

    new RiffOutputWriterFactory(typeDesc)
  }

  override def inferSchema(
      sparkSession: SparkSession,
      parameters: Map[String, String],
      files: Seq[FileStatus]): Option[StructType] = {
    // when inferring schema we expect at least one file in directory
    if (files.isEmpty) {
      log.warn("No paths found for schema inference")
      None
    } else {
      // Spark, when returning parsed file paths, does not include metadata files, only files that
      // are either partitioned or do not begin with "_". This forces us to reconstruct metadata
      // path manually.

      // Logic is to use "path" key set in parameters map; this is set by DataFrameReader and
      // contains raw unresolved path to the directory or file. We make path qualified and check
      // if it contains metadata, otherwise fall back to reading single file from the list.

      // Normally "path" key contains single path, we apply the same logic as Spark datasource
      val hadoopConf = sparkSession.sparkContext.hadoopConfiguration

      val metadata = parameters.get("path").map { path =>
        val hdfsPath = new Path(path)
        val fs = hdfsPath.getFileSystem(hadoopConf)
        val qualifiedPath = hdfsPath.makeQualified(fs.getUri, fs.getWorkingDirectory)
        val reader = Riff.metadataReader(fs, hadoopConf, qualifiedPath)
        reader.readMetadataFile(true)
      }

      // metadata can be null, we need to check
      metadata match {
        case Some(value) if value != null =>
          log.info("Infer schema from metadata")
          typeDescription = value.getTypeDescription
        case other =>
          log.info("Failed to load metadata, infer schema from header file")
          // infer schema from the first file in the list
          val headerFileStatus: Option[FileStatus] = files.headOption
          if (headerFileStatus.isEmpty) {
            throw new RuntimeException(s"No header files found in list ${files.toList}")
          }
          val headerFile = headerFileStatus.get.getPath
          val fs = headerFile.getFileSystem(hadoopConf)
          val reader = Riff.reader(fs, hadoopConf, headerFile)
          reader.readFileInfo(readFooter = false)
          typeDescription = reader.getFileHeader().getTypeDescription()
      }
      Option(typeDescription).map(_.toStructType)
    }
  }

  override def isSplitable(
      sparkSession: SparkSession,
      options: Map[String, String],
      path: Path): Boolean = {
    // TODO: update file format to support split
    false
  }

  override def buildReader(
      sparkSession: SparkSession,
      dataSchema: StructType,
      partitionSchema: StructType,
      requiredSchema: StructType,
      filters: Seq[Filter],
      options: Map[String, String],
      hadoopConf: Configuration): PartitionedFile => Iterator[InternalRow] = {
    // right now Spark appends partition values to each row, would be good to do it internally
    // TODO: handle partition values append internally, similar to ParquetFileFormat
    val projectionFields = requiredSchema.fieldNames.distinct.toArray
    // if projection fields are empty, we know that count is requested
    // in this case empty projection records are generated using metadata information
    val metadataCountEnabled = sparkSession.conf.get(SQL_RIFF_METADATA_COUNT,
      SQL_RIFF_METADATA_COUNT_DEFAULT).toBoolean

    // check if filter pushdown disabled
    val filterPushdownEnabled = sparkSession.conf.get(SQL_RIFF_FILTER_PUSHDOWN,
      SQL_RIFF_FILTER_PUSHDOWN_DEFAULT).toBoolean

    val predicate = if (filterPushdownEnabled) {
      val tree = Filters.createRiffFilter(filters)
      if (tree != null) {
        log.info(s"Applying filter tree $tree")
      }
      tree
    } else {
      log.info("Filter pushdown disabled")
      null
    }

    // set buffer size for instream in riff
    hadoopConf.set(Options.BUFFER_SIZE,
      sparkSession.conf.get(SQL_RIFF_BUFFER_SIZE, s"${Options.BUFFER_SIZE_DEFAULT}"))

    val broadcastedHadoopConf =
      sparkSession.sparkContext.broadcast(new SerializableConfiguration(hadoopConf))

    // right now, riff does not support column pruning, hence we ignore required schema and just
    // return iterator of rows
    (file: PartitionedFile) => {
      val path = new Path(file.filePath)
      val hadoopConf = broadcastedHadoopConf.value.value
      val reader = Riff.reader(hadoopConf, path)
      val iter = reader.prepareRead(predicate)
      Option(TaskContext.get()).foreach(_.addTaskCompletionListener(_ => iter.close()))

      // TODO: compare schema with inferred schema for table and merge if necessary
      // currently it breaks processing, when files have different schemas
      val td = reader.getFileHeader().getTypeDescription()
      if (metadataCountEnabled && projectionFields.isEmpty) {
        // only perform optimization if it is enabled
        // TODO: Move it into Riff format, once projection is fixed
        var numRecords = reader.getFileFooter().getNumRecords()

        new Iterator[InternalRow] {
          override def hasNext: Boolean = {
            numRecords > 0
          }

          override def next: InternalRow = {
            numRecords -= 1
            new ProjectionRow(0)
          }
        }
      } else if (projectionFields.length < td.size()) {
        // do projection if we have fewer fields to return
        // TODO: this is inefficient, it would be better to apply projection directly on indexed row
        val ordinals = projectionFields.map { fieldName => td.position(fieldName) }

        new Iterator[InternalRow]() {
          override def hasNext: Boolean = {
            iter.hasNext()
          }

          override def next: InternalRow = {
            val row = iter.next()
            val proj = new ProjectionRow(ordinals.length)
            var i = 0
            while (i < ordinals.length) {
              val spec = td.atPosition(ordinals(i))
              proj.update(i, row.get(spec.position(), spec.dataType()))
              i += 1
            }
            proj
          }
        }
      } else {
        iter.asScala
      }
    }
  }
}

object RiffFileFormat {
  // datasource option for fields to index; must be provided as comma-separated list of values
  val INDEX_FIELDS_OPTION = "index"

  // compression codec to use when writing riff files
  val SQL_RIFF_COMPRESSION_CODEC = "spark.sql.riff.compression.codec"
  // default codec for this file format
  val SQL_RIFF_COMPRESSION_CODEC_DEFAULT = "deflate"
  // number of rows per stripe to write
  val SQL_RIFF_STRIPE_ROWS = "spark.sql.riff.stripe.rows"
  // enable column filters for index fields
  val SQL_RIFF_COLUMN_FILTER_ENABLED = "spark.sql.riff.column.filter.enabled"
  // set buffer size in bytes for instream/outstream
  val SQL_RIFF_BUFFER_SIZE = "spark.sql.riff.buffer.size"
  // enable/disable filter pushdown for the format
  val SQL_RIFF_FILTER_PUSHDOWN = "spark.sql.riff.filterPushdown"
  val SQL_RIFF_FILTER_PUSHDOWN_DEFAULT = "true"
  // enable/disable count using metadata, if enabled avoid reading file for number of records
  val SQL_RIFF_METADATA_COUNT = "spark.sql.riff.metadata.count.enabled"
  val SQL_RIFF_METADATA_COUNT_DEFAULT = "true"

  // internal Spark SQL option for output committer
  val SPARK_OUTPUT_COMMITTER_CLASS = "spark.sql.sources.outputCommitterClass"

  /**
   * Parse index fields string into list of indexed columns.
   * @param fields comma-separated list of field names that exist in data schema
   * @return array of index fields
   */
  def parseIndexFields(fields: String): Array[String] = {
    if (fields == null || fields.length == 0) {
      Array.empty
    } else {
      // remove empty field names
      fields.split(',').map(_.trim).filter(_.nonEmpty)
    }
  }
}
