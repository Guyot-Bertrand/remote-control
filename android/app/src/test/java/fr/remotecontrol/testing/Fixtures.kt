package fr.remotecontrol.testing

import java.io.File

object Fixtures {
    fun text(name: String): String = File("src/test/resources/fixtures/$name").readText()
}
