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

package gov.nasa.race.actors.injectors

import akka.actor.{ActorRef, Cancellable}
import com.typesafe.config.Config
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.common._
import gov.nasa.race.core.{BusEvent, ContinuousTimeRaceActor, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.data.{FlightCsChanged, FlightCompleted, FlightDropped, FlightPos}

import scala.collection.mutable.{HashMap => MHashMap}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
  * actor that publishes FlightDropped messages for FlightPos objects which haven't been
  * updated for a configurable amount of (sim-)time
  */
class FlightDropperActor(val config: Config) extends PublishingRaceActor
  with SubscribingRaceActor with ContinuousTimeRaceActor {

  case object CheckFlightPos

  val writeTo = config.getString("write-to")
  val map = MHashMap.empty[String,FlightPos]  // only updated/accessed from handleMessage

  val dropAfterMillis = config.getFiniteDurationOrElse("drop-after", 60.seconds).toMillis // this is sim time
  val intervalSec = config.getIntOrElse("interval-sec", 60) // this is wall time

  var schedule: Option[Cancellable] = None

  //--- end init

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)
    schedule = Some(context.system.scheduler.schedule(0.seconds, intervalSec.seconds, self, CheckFlightPos))
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    super.onTerminateRaceActor(originator)
    ifSome(schedule){ _.cancel }
  }

  override def handleMessage = {
    case BusEvent(_,csChanged:FlightCsChanged,_) => changeCS(csChanged.oldCS, csChanged.cs)
    case BusEvent(_,fpos: FlightPos,_) => map.update(fpos.cs, fpos)
    case BusEvent(_,fcompleted: FlightCompleted,_) => map -= fcompleted.cs
    case BusEvent(_,fdropped: FlightDropped,_) => // we could check if we were the originator
    case CheckFlightPos => checkMap
  }

  def changeCS (oldCS: String, newCS: String) = {
    ifSome(map.get(oldCS)) { fpos =>
      map.remove(oldCS)
      map.update(newCS, fpos.copy(cs=newCS))
    }
  }

  def checkMap = {
    val now = updatedSimTime
    val cut = dropAfterMillis

    map foreach { e =>    // make sure we don't allocate per entry
      val cs = e._1
      val fpos = e._2
      val dt = elapsedSimTimeMillisSince(fpos.date)
      if (dt > cut){
        map -= cs
        publish(writeTo, FlightDropped(fpos.flightId, fpos.cs, now))
        log.info(s"$name dropping $cs after $dt msec")
      }
    }
  }
}
