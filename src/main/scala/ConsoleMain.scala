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

/**
 * RACE main class which starts a actor system per configuration file `universe`
 * entry
 */

package gov.nasa.race

import java.io.File

import akka.event.Logging
import gov.nasa.race.common.ConsoleIO._
import gov.nasa.race.common._
import gov.nasa.race.common.FileUtils._
import gov.nasa.race.core.RaceActorSystem

/**
 * application object that runs RACE interactively from the command line,
 * parsing command line options, reading config file arguments, and instantiating
 * RaceActorSystems
 */
object ConsoleMain extends MainBase {

  def main(args: Array[String]) {
    ifSome(MainOpts().parse(args)) { opts =>
      setSystemProperties(opts)
      setConsoleUserInfoFactory
      initConfigVault(opts)

      val universes = instantiateRaceActorSystems(opts.configFiles, opts.logLevel)
      if (universes.nonEmpty) {
        runUniverses(universes, opts.delayStart)
      } else println("no RaceActorSystem to execute, exiting")
    }
  }

  //--- main control loop functions

  /**
    *  interactive run (menu) loop that controls the simulation
    */
  def runUniverses(universes: Seq[RaceActorSystem], delayStart: Boolean): Unit = {
    val vmShutdownHook = addShutdownHook(universes.foreach(shutDown)) // ctrl-C (user) termination
    RaceActorSystem.addTerminationListener(systemExit)

    if (!delayStart) universes.foreach { ras => if (!ras.delayStart) start(ras) }

    menu("enter command [1:show universes, 2:show actors, 3:show channels, 4:send message, 5:set loglevel, 7: pause/resume, 8:start, 9:exit]\n") {
      case "1" | "universes" => showUniverses(universes)
        repeatMenu

      case "2" | "actors" => runOnSelectedUniverse(universes) { showActors }
        repeatMenu

      case "3" | "channels" => runOnSelectedUniverse(universes) { showChannels }
        repeatMenu

      case "4" | "message" => runOnSelectedUniverse(universes) { sendMessage }
        repeatMenu

      case "5" | "log" => runOnSelectedUniverse(universes) { setLogLevel }
        repeatMenu

      case "7" | "pause" | "resume" => // not yet
        repeatMenu

      case "8" | "start" => universes.foreach(start)
        repeatMenu

      case "9" | "exit" => // don't use System.exit here, it would break MultiNodeJVM tests
        removeShutdownHook(vmShutdownHook)
        RaceActorSystem.removeTerminationListener(systemExit)
        universes.foreach(shutDown)
    }
  }

  def showUniverses(universes: Seq[RaceActorSystem]): Unit = {
    for ((u, i) <- universes.zip(Stream from 1)) {
      println(f"${i}%3d: ${u.master.path}")
    }
  }

  def showActors(ras: RaceActorSystem): Unit = {
    println(s"actors of universe ${ras.name}:")
    ras.showActors
  }

  def showChannels(ras: RaceActorSystem): Unit = {
    println(s"channels of universe ${ras.name}:")
    ras.showChannels
  }

  final val channelPattern = """\| *(\S+)""".r

  /**
    * for testing purposes
    * fixme - this should not be in the production system
    */
  def sendMessage(ras: RaceActorSystem): Unit = {
    ConsoleIO.prompt("  enter channel (|<channel-name>) or actor (<actor-name>): ").foreach { targetSpec =>
      ConsoleIO.prompt("  enter message content or file: ").foreach { contentSpec =>
        getMessageContent(contentSpec) match {
          case Some(msg) =>
            targetSpec match {
              case channelPattern(channel) => ras.publish(channel, msg)
              case actorSpec: String => ras.send(actorSpec, msg)
            }
          case None =>
        }
      }
    }
  }

  def getMessageContent(contentSpec: String): Option[Any] = {
    if (contentSpec.nonEmpty) {
      if (contentSpec.startsWith("file://")) {
        fileContentsAsUTF8String(new File(contentSpec.substring(7))) match {
          case content @ Some(s) => content
          case None => None
        }
      } else Some(contentSpec)
    } else None
  }

  def setLogLevel(ras: RaceActorSystem): Unit = {
    ConsoleIO.prompt("  enter log level (off,error,warning,info,debug): ").foreach { level =>
      Logging.levelFor(level) match {
        case Some(logLevel) =>
          println(s"changing log level of universe ${ras.name} to $logLevel")
          ras.setLogLevel(logLevel)
        case None => println("invalid log level")
      }
    }
  }

  /**
   * override to give user an option in case graceful termination does not work
   */
  override def shutDown(ras: RaceActorSystem): Unit = {
    if (!ras.terminate) {
      menu(s"universe termination of ${ras.name} timed out: [1: kill, 2: continue]\n") {
        case "1" | "kill" => ras.kill
        case "2" | "continue" =>
      }
    }
  }


  def runOnSelectedUniverse(universes: Seq[RaceActorSystem])(f: (RaceActorSystem) => Any): Unit = {
    if (universes.length == 1) { // no need to ask
      f(universes.head)
    } else {
      ConsoleIO.promptInt("  enter universe number") match {
        case Some(n) =>
          if (n >= 0 && n < universes.length) f(universes(n))
          else println("invalid universe index")
        case None =>
      }
    }
  }
}

