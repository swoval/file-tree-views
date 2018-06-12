package com.swoval.files

import com.swoval.functional.Filter
import java.io.File
import java.io.IOException
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayList
import java.util.HashSet
import java.util.Iterator
import java.util.List
import java.util.Set
import QuickListerImpl._
import scala.beans.{ BeanProperty, BooleanBeanProperty }

/**
 Implementation class for [[QuickList.list]]
 */
trait QuickLister {

  def list(path: Path, maxDepth: Int, followLinks: Boolean): List[QuickFile]

  def list(path: Path,
           maxDepth: Int,
           followLinks: Boolean,
           filter: Filter[_ >: QuickFile]): List[QuickFile]

}

private[files] object QuickListerImpl {

  /*
   * These constants must be kept in sync with the native quick list implementation
   */

  val UNKNOWN: Int = 0

  val DIRECTORY: Int = 1

  val FILE: Int = 2

  val LINK: Int = 4

  val EOF: Int = 8

  val ENOENT: Int = -1

  val EACCES: Int = -2

  val ENOTDIR: Int = -3

  val ESUCCESS: Int = -4

  private val AllPass: Filter[AnyRef] = new Filter[AnyRef]() {
    override def accept(o: AnyRef): Boolean = true
  }

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

}

abstract private[files] class QuickListerImpl extends QuickLister {

  protected def listDir(dir: String, followLinks: Boolean): ListResults

  override def list(path: Path, maxDepth: Int, followLinks: Boolean): List[QuickFile] =
    list(path, maxDepth, followLinks, AllPass)

  override def list(path: Path,
                    maxDepth: Int,
                    followLinks: Boolean,
                    filter: Filter[_ >: QuickFile]): List[QuickFile] = {
    val result: List[QuickFile] = new ArrayList[QuickFile]()
    val visited: Set[String] =
      if ((followLinks && maxDepth > 0)) new HashSet[String]() else null
    listDirImpl(path.toString, 1, maxDepth, followLinks, result, filter, visited)
    result
  }

  private def listDirImpl(dir: String,
                          depth: Int,
                          maxDepth: Int,
                          followLinks: Boolean,
                          result: List[QuickFile],
                          filter: Filter[_ >: QuickFile],
                          visited: Set[String]): Unit = {
    if (visited != null) visited.add(dir)
    val listResults: QuickListerImpl.ListResults = listDir(dir, followLinks)
    val it: Iterator[String] = listResults.getDirectories.iterator()
    while (it.hasNext) {
      val part: String = it.next()
      if (part.!=(".") && part.!=("..")) {
        val name: String = dir + File.separator + part
        val file: QuickFileImpl = new QuickFileImpl(name, DIRECTORY)
        if (filter.accept(file)) {
          result.add(file)
          if (depth < maxDepth) {
            listDirImpl(name, depth + 1, maxDepth, followLinks, result, filter, visited)
          }
        }
      }
    }
    val fileIt: Iterator[String] = listResults.getFiles.iterator()
    while (fileIt.hasNext) {
      val file: QuickFileImpl =
        new QuickFileImpl(dir + File.separator + fileIt.next(), FILE)
      if (filter.accept(file)) {
        result.add(file)
      }
    }
    val symlinkIt: Iterator[String] = listResults.getSymlinks.iterator()
    while (symlinkIt.hasNext) {
      val fileName: String = dir + File.separator + symlinkIt.next()
      val attrs: BasicFileAttributes =
        if (followLinks)
          Files.readAttributes(Paths.get(fileName), classOf[BasicFileAttributes])
        else null
      val kind: Int =
        if (attrs != null && attrs.isDirectory) DIRECTORY | LINK
        else FILE | LINK
      val file: QuickFileImpl = new QuickFileImpl(fileName, kind)
      if (filter.accept(file)) {
        result.add(file)
        if (((kind & DIRECTORY) != 0) && depth < maxDepth && visited != null) {
          if (visited.add(file.toPath().toRealPath().toString)) {
            listDirImpl(fileName, depth + 1, maxDepth, true, result, filter, visited)
          } else {
            throw new FileSystemLoopException(fileName)
          }
        }
      }
    }
  }

}
