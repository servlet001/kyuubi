/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.engine.spark

import java.io.IOException
import java.nio.file.{Files, Path, Paths}

import scala.collection.mutable.ArrayBuffer

import org.apache.hadoop.security.UserGroupInformation

import org.apache.kyuubi._
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf.ENGINE_SPARK_MAIN_RESOURCE
import org.apache.kyuubi.engine.ProcBuilder

class SparkProcessBuilder(
    override val proxyUser: String,
    override val conf: KyuubiConf,
    override val env: Map[String, String] = sys.env)
  extends ProcBuilder with Logging {

  import SparkProcessBuilder._

  override protected val executable: String = {
    val path = env.get("SPARK_HOME").map { sparkHome =>
      Paths.get(sparkHome, "bin", SPARK_SUBMIT_FILE).toAbsolutePath
    } getOrElse {
      val sparkVer = SPARK_COMPILE_VERSION
      val hadoopVer = HADOOP_COMPILE_VERSION.take(3)
      val kyuubiPattern = "/kyuubi/"
      val cwd = getClass.getProtectionDomain.getCodeSource.getLocation.getPath
      val idx = kyuubiPattern.length + cwd.lastIndexOf(kyuubiPattern)
      val kyuubiDevHome = cwd.substring(0, idx)
      Paths.get(
        kyuubiDevHome,
        "externals",
        "kyuubi-download",
        "target",
        s"spark-$sparkVer-bin-hadoop$hadoopVer",
        "bin", SPARK_SUBMIT_FILE)
    }
    path.toAbsolutePath.toFile.getCanonicalPath
  }

  override def mainClass: String = "org.apache.kyuubi.engine.spark.SparkSQLEngine"

  override def mainResource: Option[String] = {
    // 1. get the main resource jar for user specified config first
    val jarName = s"$module-$KYUUBI_VERSION.jar"
    conf.get(ENGINE_SPARK_MAIN_RESOURCE).filter { userSpecified =>
      Files.exists(Paths.get(userSpecified))
    }.orElse {
      // 2. get the main resource jar from system build default
      env.get(KyuubiConf.KYUUBI_HOME)
        .map { Paths.get(_, "externals", "engines", "spark", jarName) }
        .filter(Files.exists(_)).map(_.toAbsolutePath.toFile.getCanonicalPath)
    }.orElse {
      // 3. get the main resource from dev environment
      Option(Paths.get("externals", module, "target", jarName))
        .filter(Files.exists(_)).orElse {
        Some(Paths.get("..", "externals", module, "target", jarName))
      }.map(_.toAbsolutePath.toFile.getCanonicalPath)
    }
  }

  override protected val workingDir: Path = {
    env.get("KYUUBI_WORK_DIR_ROOT").map { root =>
      val workingRoot = Paths.get(root).toAbsolutePath
      if (!Files.exists(workingRoot)) {
        debug(s"Creating KYUUBI_WORK_DIR_ROOT at $workingRoot")
        Files.createDirectories(workingRoot)
      }
      if (Files.isDirectory(workingRoot)) {
        workingRoot.toString
      } else null
    }.map { rootAbs =>
      val working = Paths.get(rootAbs, proxyUser)
      if (!Files.exists(working)) {
        debug(s"Creating $proxyUser's working directory at $working")
        Files.createDirectories(working)
      }
      if (Files.isDirectory(working)) {
        working
      } else {
        Utils.createTempDir(rootAbs, proxyUser)
      }
    }.getOrElse {
      Utils.createTempDir(namePrefix = proxyUser)
    }
  }

  override protected def commands: Array[String] = {
    val buffer = new ArrayBuffer[String]()
    buffer += executable
    buffer += CLASS
    buffer += mainClass
    conf.toSparkPrefixedConf.foreach { case (k, v) =>
      buffer += CONF
      buffer += s"$k=$v"
    }
    // iff the keytab is specified, PROXY_USER is not supported
    if (!useKeytab()) {
      buffer += PROXY_USER
      buffer += proxyUser
    }

    mainResource.foreach { r => buffer += r }

    buffer.toArray
  }

  override def toString: String = commands.map {
    case arg if arg.startsWith("--") => s"\\\n\t$arg"
    case arg => arg
  }.mkString(" ")

  override protected def module: String = "kyuubi-spark-sql-engine"

  private def useKeytab(): Boolean = {
    val principal = conf.getOption(PRINCIPAL)
    val keytab = conf.getOption(KEYTAB)
    if (principal.isEmpty || keytab.isEmpty) {
      false
    } else {
      try {
        val ugi = UserGroupInformation
          .loginUserFromKeytabAndReturnUGI(principal.get, keytab.get)
        ugi.getShortUserName == proxyUser
      } catch {
        case e: IOException =>
          error(s"Failed to login for ${principal.get}", e)
          false
      }
    }
  }
}

object SparkProcessBuilder {
  final val APP_KEY = "spark.app.name"
  final val TAG_KEY = "spark.yarn.tags"

  private final val CONF = "--conf"
  private final val CLASS = "--class"
  private final val PROXY_USER = "--proxy-user"
  private final val PRINCIPAL = "spark.kerberos.principal"
  private final val KEYTAB = "spark.kerberos.keytab"
  // Get the appropriate spark-submit file
  private final val SPARK_SUBMIT_FILE = if (Utils.isWindows) "spark-submit.cmd" else "spark-submit"
}
