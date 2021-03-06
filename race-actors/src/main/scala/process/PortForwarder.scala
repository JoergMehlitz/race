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

package gov.nasa.race.actors.process

import akka.actor.ActorRef
import com.jcraft.jsch.JSch
import com.typesafe.config.Config
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.common.NetUtils._
import gov.nasa.race.common._
import gov.nasa.race.core.Messages.RaceCheck
import gov.nasa.race.core._

object PortForwarder {
}

/**
  * an actor that forwards ports ala "ssh -L", i.e. can map ports on remote machines
  * (gateways) into local ports
  *
  * Note that this requires sync user authentication during init if we don't
  * provide credentials via (encrypted) config (which is normally not a good idea)
  *
  * Note also that interactive authentication is the reason why we can't automatically
  * reconnect (we don't want to store user credentials here)
  */
class PortForwarder (val config: Config) extends MonitoredRaceActor {
  implicit val client = getClass

  var connectTimeout = config.getIntOrElse("connect-timeout", 5000)
  // msec between alive messages if nothing received from server
  val aliveInterval = config.getIntOrElse("alive-interval", 5000)
  // number of un-answered alive msgs before disconnect
  val aliveMaxCount = config.getIntOrElse("alive-maxcount", 1)
  val strictHostKey = config.getBooleanOrElse("strict-hostkey", false) // should probably default to true

  val user = config.getVaultableStringOrElse("user", System.getProperty("user.name"))
  val host = config.getVaultableString("host")
  val forwardL = config.getOptionalVaultableString("forward")
  val forwardR = config.getOptionalVaultableString("reverse-forward")

  val jsch = new JSch
  val session = jsch.getSession(user, host)

  if (forwardL.nonEmpty || forwardR.nonEmpty) {
    session.setDaemonThread(true)
    session.setServerAliveCountMax(aliveMaxCount)
    session.setServerAliveInterval(aliveInterval)

    // avoid the Kerberos double prompts
    session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
    // shall we bypass confirmation for non-authenticating hosts
    if (!strictHostKey) session.setConfig("StrictHostKeyChecking", "no")

    info(s"$name connecting as $user@$host ..")
    ifSome(config.getOptionalString("pw")) { s => session.setPassword(s) }
    ifSome(UserInfoFactory.factory) { f =>
      val ui = f.getUserInfo
      session.setUserInfo(ui)
      ui.showMessage(s"port forwarder connecting as $user@$host")
    }

    session.connect(connectTimeout)

    if (session.isConnected) {
      ifSome(forwardL){setPortForward(_,"forward", (lp,h,rp)=> session.setPortForwardingL(lp,h,rp))}
      ifSome(forwardR){setPortForward(_,"reverse forward", (lp,h,rp)=> session.setPortForwardingR(lp,h,rp))}

      startMonitoring
      info(s"$name connected and forwarding")
    } else failDuringConstruction(s"$name failed to connect as $user@$host")
  } else  failDuringConstruction(s"$name no forwards specified")

  def setPortForward (spec: String, action: String, f: (Int,String,Int)=>Unit) = {
    spec.split("[,; ]+").foreach {
        case PortHostPortRE(lport, host, rport) =>
          f(lport.toInt,host,rport.toInt)
          info(s"$name $action $lport:$host:$rport")
        case other => failDuringConstruction(s"$name invalid forward spec: $other")
    }
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    super.onTerminateRaceActor(originator)

    if (session.isConnected) {
      session.disconnect()
      info(s"$name stopped port forwarding to $host")
    }
  }

  override def handleMessage = {
    case RaceCheck => checkConnected
  }

  def checkConnected = {
    if (!session.isConnected) {
      commitSuicide(s"$name detected lost connection to $host, committing suicide")
    }
  }
}