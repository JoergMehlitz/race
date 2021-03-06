/*
 * Copyright (c) 2016, United States Government, as represented by the 
 * Administrator of the National Aeronautics and Space Administration. 
 * All rights reserved.
 * 
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed 
 * under the Apache License, Version 2.0 (the "License"); you may not use 
 * this file except in compliance with the License. You may obtain a copy 
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 * 
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */

package gov.nasa.race

import java.io.{File, FileInputStream}
import java.net.{Inet4Address, InetAddress, NetworkInterface}
import java.util

import scala.collection.JavaConversions._
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import gov.nasa.race.common._
import gov.nasa.race.common.FileUtils._
import gov.nasa.race.common.NetUtils._
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.common.ClassLoaderUtils
import gov.nasa.race.common.ConsoleIO._
import gov.nasa.race.core.RaceActorSystem
import org.joda.time.DateTime

import scala.collection.mutable

/**
 * common functions for all xxMain objects
 */
trait MainBase {

  //var logLevel: Option[String] = None

  // here so that it can be overridden in contexts that run embedded, i.e. are not allowed to kill the process
  def systemExit() = System.exit(0)

  def addShutdownHook(action: => Any): Thread = {
    val thread = new Thread {
      override def run(): Unit = action
    }
    Runtime.getRuntime.addShutdownHook(thread)
    thread
  }

  def removeShutdownHook (hook: Thread) = Runtime.getRuntime.removeShutdownHook(hook)

  /**
   * override this to interactively control behavior
   */
  def shutDown(ras: RaceActorSystem): Unit = {
    if (!ras.terminate) { // try graceful termination first
      ras.kill
    }
  }

  def start(ras: RaceActorSystem): Unit = ras.startActors

  def setConsoleUserInfoFactory = UserInfoFactory.factory = Some(ConsoleUserInfoAdapter)

  //--- Config and RaceActorSystem instantiation

  def instantiateRaceActorSystems(configFiles: Seq[File], logLevel: Option[String]): Seq[RaceActorSystem] = {
    tryCatchAllWith(Seq.empty[RaceActorSystem]) {
      getUniverseConfigs(configFiles, logLevel).map(new RaceActorSystem(_))
    }
  }

  def getUniverseConfigs(configFiles: Seq[File], logLevel: Option[String]): Seq[Config] = {
    val configs = if (configFiles.isEmpty) { // if no config file was specified, run the generic satellite config
      Seq(ConfigFactory.load("satellite.conf"))
    } else {
      configFiles.flatMap(existingFile(_, ConfigExt)) map { f =>
        processGlobalConfig(ConfigFactory.load(ConfigFactory.parseFile(f)))
      }
    }

    var nUniverses = -1
    configs.foldLeft(Seq.empty[Config]) { (universeConfigs, c) =>
      nUniverses += 1
      if (c.hasPath("universes")) { // we have a list of universes wrapped in "universes [..]"
        universeConfigs ++ c.getConfigList("universes").map(processUniverseConfig(_, nUniverses, logLevel))
      } else if (c.hasPath("universe")) { // a single universe, wrapped in "universe {..}"
        universeConfigs :+ processUniverseConfig(c.getConfig("universe"), nUniverses, logLevel)
      } else { // a single universe, nothing to unwrap
        universeConfigs :+ processUniverseConfig(c, nUniverses, logLevel)
      }
    }
  }

  def processGlobalConfig(conf: Config): Config = {
    ifSome(conf.getOptionalString("classpath")) { ClassLoaderUtils.extendGlobalClasspath }
    conf
  }

  def setSystemProperties(o: MainOpts): Unit = {
    setSystemProperties(o.propertyFile, o.setHost.flatMap(getInetAddress), o.logLevel, o.logConsoleURI)
  }

  def setSystemProperties(
    propertyFile: Option[File],
    inetAddress: Option[InetAddress],
    optLogLevel: Option[String],
    logConsoleURI: Option[String]
  ): Unit = {
    ifSome(propertyFile) { propFile =>
      using(new FileInputStream(propFile)) { fis => System.getProperties.load(fis) }
    }

    ifSome(inetAddress) { iaddr =>
      System.setProperty("race.host", iaddr.getHostAddress)
    }

    if (System.getProperty("race.date") == null) {
      System.setProperty("race.date", DateTime.now().toString("yyyy-MM-dd'T'HH:mm:ss"))
    }

    // logging related system properties (logback)
    ifSome(optLogLevel) { level => System.setProperty("root-level", level.toString) }

    ifSome(logConsoleURI) { uri =>
      val (host, port) = HostPortRE.findFirstIn(uri) match {
        case Some(HostPortRE(host, p)) => (host, if (p == null) DefaultLogConsolePort else p)
        case None => (DefaultLogConsoleHost, DefaultLogConsolePort)
      }
      System.setProperty("log.console.host", host)
      System.setProperty("log.console.port", port)
      System.setProperty("logback.configurationFile", "logback-console.xml")
    }
  }

  def getInetAddress(ifcName: String): Option[InetAddress] = {
    val ifc = NetworkInterface.getByName(ifcName)
    ifc.getInetAddresses.find(_.isInstanceOf[Inet4Address]).map(_.asInstanceOf[Inet4Address])
  }

  //--- vault initialization

  def initConfigVault (opts: MainOpts): Unit = {
    ifSome(opts.vault) { vaultFile =>
      if (opts.keyStore.isDefined) { // use the provided keystore to get the vault key
        for (
          ksFile <- opts.keyStore;
          pw <- ConsoleIO.promptPassword(s"enter password for keystore $ksFile: ");
          ks <- CryptUtils.loadKeyStore(ksFile,pw);
          alias <- opts.alias;
          key <- withSubsequent(CryptUtils.getKey(ks,alias,pw)){ util.Arrays.fill(pw,' ') };
          cipher <- CryptUtils.getDecryptionCipher(key)
        ) ConfigVault.initialize(vaultFile,cipher)

      } else { // ask for the vault key
        ifSome(ConsoleIO.promptPassword(s"enter password for config vault $vaultFile: ")) { pw=>
          try {ConfigVault.initialize(vaultFile,pw)} finally { util.Arrays.fill(pw,' ') }
        }
      }
    }
    // if there is no vault option set we don't have anything to do
  }

  //--- config manipulation

  def processUniverseConfig(conf: Config, universeNumber: Int, logLevel: Option[String]): Config = {
    addNameConfig(addRemotingConfig(addLogLevelConfig(conf, logLevel)), universeNumber)
  }

  def addNameConfig(conf: Config, universeNumber: Int): Config = {
    if (!conf.hasPath("name")) conf.withStringValue("name", s"universe-$universeNumber")
    else conf
  }

  def addRemotingConfig(conf: Config): Config = {
    val masterName = conf.getString("name")
    val remotes = mutable.Set.empty[String]

    conf.getOptionalConfigList("actors").foldLeft(conf) { (universeConf, actorConf) =>
      if (actorConf.hasPath("remote")) {
        var confʹ = universeConf
        val actorName = actorConf.getString("name")
        val remoteUri = actorConf.getString("remote")
        val v = ConfigValueFactory.fromAnyRef(remoteUri)
        addIfAbsent(remotes, remoteUri) { // add the remote master too
          val remoteMasterName = userInUrl(remoteUri).get
          confʹ = confʹ.withValue(s"""akka.actor.deployment."/$masterName/$remoteMasterName".remote""", v)
        }
        confʹ.withValue(s"""akka.actor.deployment."/$masterName/$actorName".remote""", v)
      } else universeConf
    }
  }

  def addLogLevelConfig(conf: Config, logLevel: Option[String]): Config = {
    if (logLevel.isDefined) conf.withValue("akka.loglevel", ConfigValueFactory.fromAnyRef(logLevel.get))
    else conf
  }
}
