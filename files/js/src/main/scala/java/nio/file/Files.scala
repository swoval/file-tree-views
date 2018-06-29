package java.nio.file

import java.io.{ File, IOException }
import java.nio.file.attribute.{
  BasicFileAttributes,
  BasicFileAttributesImpl,
  FileAttribute,
  FileTime
}
import java.util

import com.swoval.files.Platform
import io.scalajs.nodejs.fs.Fs

import scala.util.Try

object Files {
  def createDirectory(path: Path, attrs: Array[FileAttribute[_]] = Array.empty): Path = {
    if (!path.toFile().mkdir()) throw new IOException(s"Couldn't create path $path")
    path
  }
  def createDirectories(path: Path, attrs: Array[FileAttribute[_]] = Array.empty): Path = {
    path.toFile().mkdirs()
    path
  }
  def createFile(path: Path, attrs: Array[FileAttribute[_]] = Array.empty): Path = {
    if (!path.toFile().createNewFile()) throw new IOException(s"Couldn't create file $path")
    path
  }
  def createSymbolicLink(path: Path,
                         target: Path,
                         attrs: Array[FileAttribute[_]] = Array.empty): Path = {
    val tpe = if (Platform.isWin && Files.isDirectory(target)) "dir" else "file"
    Fs.symlinkSync(target.toString, path.toString, tpe)
    path
  }
  def createTempDirectory(path: Path,
                          prefix: String,
                          attrs: Array[FileAttribute[_]] = Array.empty): Path =
    new JSPath(Fs.realpathSync(Fs.mkdtempSync(path.resolve(prefix).toString())))
  def createTempFile(dir: Path,
                     prefix: String,
                     suffix: String,
                     attrs: Array[FileAttribute[_]] = Array.empty): Path = {
    val random = new scala.util.Random().alphanumeric.take(10).mkString
    val path = s"$dir${File.separator}$prefix${random}${Option(suffix).getOrElse("")}"
    Fs.closeSync(Fs.openSync(path, "w"))
    new JSPath(path)
  }
  def delete(path: Path): Unit = path.toFile.delete()
  def deleteIfExists(path: Path): Boolean =
    try {
      path.toFile.delete()
    } catch { case e: Exception => false }
  def exists(path: Path, options: LinkOption*): Boolean = exists(path, options.toArray)
  def exists(path: Path, options: Array[LinkOption] = Array.empty): Boolean = path.toFile.exists
  def isDirectory(path: Path, linkOptions: Array[LinkOption] = Array.empty): Boolean = {
    Try(path.toFile.isDirectory).getOrElse(false)
  }
  def isRegularFile(path: Path, linkOptions: Array[LinkOption] = Array.empty): Boolean = {
    Try(path.toFile.isFile).getOrElse(false)
  }
  def isSymbolicLink(path: Path): Boolean = {
    Try(
      readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS).isSymbolicLink)
      .getOrElse(false)
  }

  def move(src: Path, target: Path, options: Array[CopyOption] = Array.empty): Path = {
    if (!src.toFile.renameTo(target.toFile()))
      throw new IOException(s"Couldn't move $src to $target")
    target
  }
  def getLastModifiedTime(path: Path, linkOptions: Array[LinkOption] = Array.empty): FileTime =
    FileTime.fromMillis(path.toFile.lastModified)
  def readAttributes[T <: BasicFileAttributes](path: Path,
                                               clazz: Class[T],
                                               options: LinkOption*): T =
    readAttributes(path, clazz, options.toArray)
  def readAttributes[T <: BasicFileAttributes](path: Path,
                                               clazz: Class[T],
                                               options: Array[LinkOption]): T = {
    clazz match {
      case c if classOf[BasicFileAttributes].isAssignableFrom(c) =>
        new BasicFileAttributesImpl(path, options).asInstanceOf[T]
      case _ => ???
    }
  }
  def readAllBytes(path: Path): Array[Byte] = {
    val buf = Fs.readFileSync(path.toRealPath().toString)
    buf.values.map(b => (b & 0xFF).toByte).toArray
  }
  def readSymbolicLink(path: Path): Path = {
    Paths.get(Fs.readlinkSync(path.toString()))
  }
  def setLastModifiedTime(path: Path, fileTime: FileTime): Path = {
    path.toFile.setLastModified(fileTime.toMillis)
    path
  }
  def walkFileTree(path: Path,
                   options: java.util.Set[FileVisitOption],
                   depth: Int,
                   fileVisitor: FileVisitor[_ >: Path]): Path = {
    val linkOptions = Array[LinkOption](LinkOption.NOFOLLOW_LINKS)
    val files = try {
      Fs.readdirSync(path.toAbsolutePath.toString())
    } catch { case ex: Exception => Errors.rethrow(path, ex) }
    files.foreach { f =>
      val p = path.resolve(f)
      try {
        Try(readAttributes(p, classOf[BasicFileAttributes], linkOptions)) foreach { attrs =>
          if (attrs.isDirectory) {
            var ioException: IOException = null

            fileVisitor.preVisitDirectory(p, attrs) match {
              case FileVisitResult.TERMINATE | FileVisitResult.SKIP_SIBLINGS => return path
              case FileVisitResult.CONTINUE =>
                try {
                  if (depth > 1) {
                    walkFileTree(p, options, depth - 1, fileVisitor)
                  } else {
                    fileVisitor.visitFile(p, attrs)
                  }
                } catch {
                  case e: IOException => ioException = e
                }
              case _ =>
            }
            fileVisitor.postVisitDirectory(p, ioException)
          } else {
            try {
              fileVisitor.visitFile(p, attrs) match {
                case FileVisitResult.TERMINATE | FileVisitResult.SKIP_SIBLINGS => return path
                case _                                                         =>
              }
            } catch {
              case e: IOException => fileVisitor.visitFileFailed(p, e)
            }
          }
        }
      } catch {
        case e: IOException =>
          fileVisitor.visitFileFailed(p, e)
      }
    }
    path
  }
  def walkFileTree(path: Path, fileVisitor: FileVisitor[_ >: Path]): Path =
    walkFileTree(path, new util.HashSet(), java.lang.Integer.MAX_VALUE, fileVisitor)
  def write(path: Path, bytes: Array[Byte], options: Array[OpenOption] = Array.empty): Path = {
    Fs.writeFileSync(path.toString, new String(bytes))
    path
  }
}
