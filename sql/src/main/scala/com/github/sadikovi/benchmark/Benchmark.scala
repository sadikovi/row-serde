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

package com.github.sadikovi.benchmark

import java.io.{OutputStream, PrintStream}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.sys.process.Process
import scala.util.Try

import org.apache.commons.io.output.TeeOutputStream
import org.apache.commons.lang3.SystemUtils

/**
 * Copy from "apache/spark/master/core/src/main/scala/org/apache/spark/util/Benchmark.scala" with
 * some minor changes, because class is private to spark package.
 *
 * Utility class to benchmark components. An example of how to use this is:
 *  val benchmark = new Benchmark("My Benchmark", valuesPerIteration)
 *   benchmark.addCase("V1")(<function>)
 *   benchmark.addCase("V2")(<function>)
 *   benchmark.run
 * This will output the average time to run each function and the rate of each function.
 */
private[sadikovi] class Benchmark(
    name: String,
    valuesPerIteration: Long,
    minNumIters: Int = 5,
    warmupTime: FiniteDuration = 2.seconds,
    minTime: FiniteDuration = 2.seconds,
    outputPerIteration: Boolean = false,
    output: Option[OutputStream] = None) {
  import Benchmark._
  val benchmarks = mutable.ArrayBuffer.empty[Benchmark.Case]

  val out = if (output.isDefined) {
    new PrintStream(new TeeOutputStream(System.out, output.get))
  } else {
    System.out
  }

  /**
   * Adds a case to run when run() is called. The given function will be run for several
   * iterations to collect timing statistics.
   *
   * @param name of the benchmark case
   * @param numIters if non-zero, forces exactly this many iterations to be run
   */
  def addCase(name: String, numIters: Int = 0)(f: Int => Unit): Unit = {
    addTimerCase(name, numIters) { timer =>
      timer.startTiming()
      f(timer.iteration)
      timer.stopTiming()
    }
  }

  /**
   * Adds a case with manual timing control. When the function is run, timing does not start
   * until timer.startTiming() is called within the given function. The corresponding
   * timer.stopTiming() method must be called before the function returns.
   *
   * @param name of the benchmark case
   * @param numIters if non-zero, forces exactly this many iterations to be run
   */
  def addTimerCase(name: String, numIters: Int = 0)(f: Benchmark.Timer => Unit): Unit = {
    benchmarks += Benchmark.Case(name, f, numIters)
  }

  /**
   * Runs the benchmark and outputs the results to stdout. This should be copied and added as
   * a comment with the benchmark. Although the results vary from machine to machine, it should
   * provide some baseline.
   */
  def run(): Unit = {
    require(benchmarks.nonEmpty)
    // scalastyle:off
    println("Running benchmark: " + name)

    val results = benchmarks.map { c =>
      println("  Running case: " + c.name)
      measure(valuesPerIteration, c.numIters)(c.fn)
    }
    println

    val firstBest = results.head.bestMs
    // The results are going to be processor specific so it is useful to include that.
    out.println(Benchmark.getJVMOSInfo())
    out.println(Benchmark.getProcessorName())
    out.printf("%-40s %16s %12s %13s %10s\n", name + ":", "Best/Avg Time(ms)", "Rate(M/s)",
      "Per Row(ns)", "Relative")
    out.println("-" * 96)
    results.zip(benchmarks).foreach { case (result, benchmark) =>
      out.printf("%-40s %16s %12s %13s %10s\n",
        benchmark.name,
        "%5.0f / %4.0f" format (result.bestMs, result.avgMs),
        "%10.1f" format result.bestRate,
        "%6.1f" format (1000 / result.bestRate),
        "%3.1fX" format (firstBest / result.bestMs))
    }
    out.println
    // scalastyle:on
  }

  /**
   * Runs a single function `f` for iters, returning the average time the function took and
   * the rate of the function.
   */
  def measure(num: Long, overrideNumIters: Int)(f: Timer => Unit): Result = {
    System.gc()  // ensures garbage from previous cases don't impact this one
    val warmupDeadline = warmupTime.fromNow
    while (!warmupDeadline.isOverdue) {
      f(new Benchmark.Timer(-1))
    }
    val minIters = if (overrideNumIters != 0) overrideNumIters else minNumIters
    val minDuration = if (overrideNumIters != 0) 0 else minTime.toNanos
    val runTimes = mutable.ArrayBuffer[Long]()
    var i = 0
    while (i < minIters || runTimes.sum < minDuration) {
      val timer = new Benchmark.Timer(i)
      f(timer)
      val runTime = timer.totalTime()
      runTimes += runTime

      if (outputPerIteration) {
        // scalastyle:off
        println(s"Iteration $i took ${runTime / 1000} microseconds")
        // scalastyle:on
      }
      i += 1
    }
    // scalastyle:off
    println(s"  Stopped after $i iterations, ${runTimes.sum / 1000000} ms")
    // scalastyle:on
    val best = runTimes.min
    val avg = runTimes.sum / runTimes.size
    Result(avg / 1000000.0, num / (best / 1000.0), best / 1000000.0)
  }
}

private[sadikovi] object Benchmark {

  /**
   * Object available to benchmark code to control timing e.g. to exclude set-up time.
   *
   * @param iteration specifies this is the nth iteration of running the benchmark case
   */
  class Timer(val iteration: Int) {
    private var accumulatedTime: Long = 0L
    private var timeStart: Long = 0L

    def startTiming(): Unit = {
      assert(timeStart == 0L, "Already started timing.")
      timeStart = System.nanoTime
    }

    def stopTiming(): Unit = {
      assert(timeStart != 0L, "Have not started timing.")
      accumulatedTime += System.nanoTime - timeStart
      timeStart = 0L
    }

    def totalTime(): Long = {
      assert(timeStart == 0L, "Have not stopped timing.")
      accumulatedTime
    }
  }

  case class Case(name: String, fn: Timer => Unit, numIters: Int)
  case class Result(avgMs: Double, bestRate: Double, bestMs: Double)

  /** Execute command as sequence, block until get result and return output */
  private def executeAndGetOutput(command: Seq[String]): String = {
    Process(command).!!.trim()
  }

  /**
   * This should return a user helpful processor information. Getting at this depends on the OS.
   * This should return something like "Intel(R) Core(TM) i7-4870HQ CPU @ 2.50GHz"
   */
  def getProcessorName(): String = {
    val cpu = if (SystemUtils.IS_OS_MAC_OSX) {
      executeAndGetOutput(Seq("/usr/sbin/sysctl", "-n", "machdep.cpu.brand_string"))
    } else if (SystemUtils.IS_OS_LINUX) {
      Try {
        val grepPath = executeAndGetOutput(Seq("which", "grep")).stripLineEnd
        executeAndGetOutput(Seq(grepPath, "-m", "1", "model name", "/proc/cpuinfo"))
          .stripLineEnd.replaceFirst("model name[\\s*]:[\\s*]", "")
      }.getOrElse("Unknown processor")
    } else {
      System.getenv("PROCESSOR_IDENTIFIER")
    }
    cpu
  }

  /**
   * This should return a user helpful JVM & OS information.
   * This should return something like
   * "OpenJDK 64-Bit Server VM 1.8.0_65-b17 on Linux 4.1.13-100.fc21.x86_64"
   */
  def getJVMOSInfo(): String = {
    val vmName = System.getProperty("java.vm.name")
    val runtimeVersion = System.getProperty("java.runtime.version")
    val osName = System.getProperty("os.name")
    val osVersion = System.getProperty("os.version")
    s"${vmName} ${runtimeVersion} on ${osName} ${osVersion}"
  }
}
