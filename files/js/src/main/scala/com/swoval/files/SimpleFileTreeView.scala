// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import com.swoval.functional.Filter
import java.io.File
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayList
import java.util.HashSet
import java.util.Iterator
import java.util.List
import java.util.Set
import SimpleFileTreeView._
import scala.beans.{ BeanProperty, BooleanBeanProperty }

object SimpleFileTreeView {

  /*
   * These constants must be kept in sync with the native quick list implementation
   */

  val UNKNOWN: Int = Entries.UNKNOWN

  val DIRECTORY: Int = Entries.DIRECTORY

  val FILE: Int = Entries.FILE

  val LINK: Int = Entries.LINK

  val NONEXISTENT: Int = Entries.NONEXISTENT

  private val VERBOSE: Boolean =
    System.getProperty("swoval.verbose", "false").==("true")

  class ListResults {

    @BeanProperty
    val directories: List[String] = new ArrayList()

    @BeanProperty
    val files: List[String] = new ArrayList()

    @BeanProperty
    val symlinks: List[String] = new ArrayList()

    def addDir(dir: String): Unit = {
      directories.add(dir)
    }

    def addFile(file: String): Unit = {
      files.add(file)
    }

    def addSymlink(link: String): Unit = {
      symlinks.add(link)
    }

    override def toString(): String =
      "ListResults(\n  directories = " + directories + ",\n  files = " +
        files +
        ", \n  symlinks = " +
        symlinks +
        "\n)"

  }

  private def getSymbolicLinkTargetKind(path: Path, followLinks: Boolean): Int =
    if (followLinks) {
      try {
        val attrs: BasicFileAttributes = NioWrappers.readAttributes(path)
        LINK |
          (if (attrs.isDirectory) DIRECTORY
           else if (attrs.isRegularFile) FILE
           else UNKNOWN)
      } catch {
        case e: NoSuchFileException => NONEXISTENT

      }
    } else {
      LINK
    }

  private def decrement(maxDepth: Int): Int =
    if (maxDepth == java.lang.Integer.MAX_VALUE) maxDepth else maxDepth - 1

}

class SimpleFileTreeView(private val directoryLister: DirectoryLister,
                         private val followLinks: Boolean,
                         private val ignoreExceptions: Boolean)
    extends FileTreeView {

  def this(directoryLister: DirectoryLister, followLinks: Boolean) =
    this(directoryLister, followLinks, false)

  override def list(path: Path, maxDepth: Int, filter: Filter[_ >: TypedPath]): List[TypedPath] = {
    val result: List[TypedPath] = new ArrayList[TypedPath]()
    if (maxDepth >= 0) {
      new Lister(filter, result, followLinks, ignoreExceptions)
        .fillResults(path, maxDepth)
    } else {
      val typedPath: TypedPath = TypedPaths.get(path)
      if (filter.accept(typedPath)) result.add(typedPath)
    }
    result
  }

  override def close(): Unit = {}

  private class Lister(val filter: Filter[_ >: TypedPath],
                       val result: List[TypedPath],
                       val followLinks: Boolean,
                       val ignoreExceptions: Boolean) {

    val visited: Set[Path] = new HashSet()

    def fillResults(dir: Path, maxDepth: Int): Unit = {
      try impl(dir, maxDepth)
      finally visited.clear()
    }

    private def impl(dir: Path, maxDepth: Int): Unit = {
      try {
        val listResults: SimpleFileTreeView.ListResults =
          directoryLister.apply(dir.toAbsolutePath().toString, followLinks)
        visited.add(dir)
        val it: Iterator[String] = listResults.getDirectories.iterator()
        while (it.hasNext) {
          val part: String = it.next()
          if (part.!=(".") && part.!=("..")) {
            val path: Path = Paths.get(dir + File.separator + part)
            val file: TypedPath = TypedPaths.get(path, DIRECTORY)
            if (filter.accept(file)) {
              result.add(file)
            }
            if (maxDepth > 0) {
              fillResults(path, decrement(maxDepth))
            }
          }
        }
        val fileIt: Iterator[String] = listResults.getFiles.iterator()
        while (fileIt.hasNext) {
          val typedPath: TypedPath =
            TypedPaths.get(Paths.get(dir + File.separator + fileIt.next()), FILE)
          if (filter.accept(typedPath)) {
            result.add(typedPath)
          }
        }
        val symlinkIt: Iterator[String] = listResults.getSymlinks.iterator()
        while (symlinkIt.hasNext) {
          val fileName: Path =
            Paths.get(dir + File.separator + symlinkIt.next())
          val typedPath: TypedPath =
            TypedPaths.get(fileName, getSymbolicLinkTargetKind(fileName, followLinks))
          if (filter.accept(typedPath)) {
            result.add(typedPath)
          }
          if (typedPath.isDirectory && maxDepth > 0) {
            if (visited.add(typedPath.getPath.toRealPath())) {
              fillResults(fileName, decrement(maxDepth))
            } else {
              if (VERBOSE)
                System.err.println("Detected symlink loop for path " + typedPath.getPath)
            }
          }
        }
      } catch {
        case e: IOException => if (!ignoreExceptions) throw e

      }
    }

  }

}
