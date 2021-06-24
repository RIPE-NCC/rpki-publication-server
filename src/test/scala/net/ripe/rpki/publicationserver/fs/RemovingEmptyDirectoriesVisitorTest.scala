package net.ripe.rpki.publicationserver.fs

import net.ripe.rpki.publicationserver.PublicationServerBaseTest

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

class RemovingEmptyDirectoriesVisitorTest extends PublicationServerBaseTest {

  test("should remove empty directories recursively and leave non-empty") {

    val rootDir = Files.createTempDirectory("test_remove_empty_dir_visitor")
    deleteOnExit(rootDir)

    Files.createDirectory(Paths.get(rootDir.toString, "x"))
    Files.createDirectory(Paths.get(rootDir.toString, "x", "y"))
    Files.createDirectory(Paths.get(rootDir.toString, "x", "y", "foo"))
    Files.createDirectory(Paths.get(rootDir.toString, "x", "y", "foo", "bar"))
    Files.createDirectory(Paths.get(rootDir.toString, "z"))

    Files.write(Paths.get(rootDir.toString, "z", "file.txt"), "something".getBytes(StandardCharsets.UTF_8))

    Files.walkFileTree(rootDir, new RemoveEmptyDirectoriesVisitor())

    Paths.get(rootDir.toString, "x").toFile.exists() should be(false)
    Paths.get(rootDir.toString, "z").toFile.exists() should be(true)
    Paths.get(rootDir.toString, "z", "file.txt").toFile.exists() should be(true)
  }

}
