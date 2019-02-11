package com.swoval.files

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ ArrayBlockingQueue, ConcurrentHashMap, CountDownLatch, TimeUnit }

import com.swoval.files.apple.FileEventMonitorTest
import utest._
import utest.framework.{ HTree, Result }

import scala.util.{ Failure, Success, Try }

object AllTests {
  def main(args: Array[String]): Unit = {
    val count = args.headOption.flatMap(a => Try(a.toInt).toOption).getOrElse(1)
    System.setProperty("swoval.test.timeout",
                       args.lastOption.flatMap(a => Try(a.toInt).toOption).getOrElse(10).toString)
    try {
      1 to count foreach { i =>
        println(s"Iteration $i:")
        try {
          run(i)
        } catch {
          case e: Throwable =>
            System.err.println(s"Tests failed during run $i ($e)")
            e.printStackTrace(System.err)
            System.exit(1)
        }
      }
      println("finished")
      System.exit(0)
    } catch {
      case _: Exception => System.exit(1)
    }
  }
  def run(count: Int): Unit = {
    def test[T <: TestSuite](t: T): (Tests, String) =
      (t.tests, t.getClass.getName.replaceAll("[$]", ""))
    val tests = Seq(
      test(BasicFileCacheTest),
      test(NioBasicFileCacheTest),
      test(FileCacheSymlinkTest),
      test(NioFileCacheSymlinkTest),
      test(FileCacheOverflowTest),
      test(NioFileCacheOverflowTest),
      test(FileEventMonitorTest),
      test(DataViewTest),
      test(CachedFileTreeViewTest),
      test(PathTest),
      test(NioPathWatcherTest),
      test(DirectoryFileTreeViewTest),
      test(ApplePathWatcherTest)
    )
    val queue = new ArrayBlockingQueue[(String, Try[HTree[String, Result]])](tests.size)
    val failure = new AtomicReference[Option[Throwable]](None)
    val threads = tests.map {
      case (t, n) =>
        val thread = new Thread(s"$n test thread") {
          setDaemon(true)
          override def run(): Unit = {
            try {
              queue.add(n -> Try(TestRunner.runAndPrint(t, n)))
            } catch {
              case _: InterruptedException =>
            }
          }
        }
        thread.start()
        thread
    }
    val completed = ConcurrentHashMap.newKeySet[String]
    tests.indices foreach { _ =>
      queue.poll(10, TimeUnit.SECONDS) match {
        case null if completed.size != tests.size =>
          throw new IllegalStateException("Test failed")
        case (n, Success(result)) =>
          completed.add(n)
          result.leaves.map(_.value).foreach {
            case Failure(e) => failure.compareAndSet(None, Some(e))
            case _          =>
          }
        case (n, Failure(e)) =>
          completed.add(n)
          failure.compareAndSet(None, Some(e))
      }
    }
    val now = System.nanoTime
    println(s"joining threads for iteration $count")
    threads.foreach(_.interrupt())
    threads.foreach(_.join(5000))
    val elapsed = System.nanoTime - now
    println(s"finished joining thread for iteration $count in ${elapsed / 1.0e6} ms")
    failure.get.foreach(throw _)
  }
}
