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

package gov.nasa.race.core

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.event.Logging.LogLevel
import akka.event.LoggingAdapter
import akka.pattern.ask
import com.typesafe.config.Config
import gov.nasa.race.common
import gov.nasa.race.common.NetUtils._
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.common.Status._
import gov.nasa.race.common.{ ClassLoaderUtils, SettableClock, _ }
import gov.nasa.race.core.Messages._
import org.joda.time.DateTime

import scala.collection._
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.ListMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

object RaceActorSystem { // aka RAS

  private val liveSystems = TrieMap[ActorSystem, RaceActorSystem]()
  // all RaceActors live in RaceActorSystems, no need to use Option
  def apply(system: ActorSystem): RaceActorSystem = liveSystems.get(system).get

  var terminationListeners = Set.empty[() => Unit]
  def addTerminationListener(listener: () => Unit) = terminationListeners = terminationListeners + listener
  def addTerminationAction(action: => Unit) = terminationListeners = terminationListeners + (() => action)
  def removeTerminationListener(listener: () => Unit) = terminationListeners = terminationListeners - listener

  def hasLiveSystems = liveSystems.nonEmpty
  def numberOfLiveSystems = liveSystems.size
  def addLiveSystem(race: RaceActorSystem) = liveSystems += (race.system -> race)
  def removeLiveSystem(race: RaceActorSystem) = {
    liveSystems -= race.system
    if (liveSystems.isEmpty) {
      // we need to do this sync since the logging might not show anymore
      println("last actor system did shut down, exiting RACE\n")
      terminationListeners.foreach(_())
      // don't do a System.exit() here because it would break multi-jvm tests
    }
  }

  def shutdownLiveSystems = {
    liveSystems.values.foreach(_.terminate)
  }
}

/**
 * RaceActorSystem (RAS) instances represent a Akka actor system comprised of RaceActors, managed
 * by a single (non-RaceActor) Master, communicating through a Bus that allows for local and
 * remote publish/subscribe.
 *
 * RaceActorSystems are instantiated by the respective Main, providing a Config object
 * specifying its RaceActors.
 *
 * At runtime, a RAS is mostly used to aggregate information that is needed by its
 * RaceActors, i.e. it doesn't play an active role except of controlling termination
 * policies.
 *
 * NOTE - the RaceActorSystem instance is shared between all local actors. Make sure
 * this doesn't create race conditions (exposed data should be invariant after init,
 * or at least thread safe)
 *
 * We can't easily turn this into a Akka extension since we have to be in control of
 * when&where RAS instances are created. The RAS owns the Akka 'system', not the other way
 */
class RaceActorSystem(val config: Config) extends LogController with VerifiableAsker {
  import gov.nasa.race.core.RaceActorSystem._

  protected var status = Initializing
  val name = config.getString("name")
  val system = createActorSystem(name, config)
  implicit val log = getLoggingAdapter(system)
  val classLoader = ClassLoaderUtils.setRaceClassloader(system, config.getOptionalString("classpath"))
  val bus = createBus(system)
  val simClock = createSimClock

  val wallStartTime = wallClockStartTime
  val delayStart = config.getBooleanOrElse("delay-start", wallStartTime.isDefined)

  // do we allow external (remote) termination
  val allowRemoteTermination = config.getBooleanOrElse("remote-termination", false)
  // do we allow our own actors to trigger termination
  val allowSelfTermination = config.getBooleanOrElse("self-termination", false)

  addLiveSystem(this)
  system.whenTerminated.onSuccess {
    case _ => removeLiveSystem(this)
  }

  RaceLogger.logController = this
  debug(s"initializing RaceActorSystem for config:\n${showConfig(config)})")

  // those are set during master initialization
  // Note that master init involves round-trips, i.e. can cause exceptions in other threads
  // that can go unnoticed here, hence we have to turn this into explicit state
  var actors = ListMap.empty[ActorRef, Config]
  var satellites = Map.empty[UrlString, ActorRef]

  val master = system.actorOf(Props(getMasterClass, this), name)
  waitForActor(master) {
    case e =>
      error(s"error instantiating master of $name: $e")
      throw new RaceInitializeException("no master for $name")
  }

  // this needs to be here so that all local actors can get it (before they get created!)
  val localRaceContext = createRaceContext(master, bus)

  createActors
  initializeActors
  if (status != Initialized) {
    system.terminate()
    throw new RaceInitializeException("race actor system did not initialize")
  }

  ifSome(wallStartTime) { scheduleStart }

  // done with initialization

  //--- those can be overridden by subclasses

  def createActorSystem(name: String, conf: Config): ActorSystem = ActorSystem(name, config)

  def getLoggingAdapter(sys: ActorSystem): LoggingAdapter = sys.log

  def createBus(sys: ActorSystem): Bus = new Bus(sys)

  def getMasterClass: Class[_ <: Actor] = classOf[MasterActor]

  def createSimClock: SettableClock = {
    val date = config.getDateTimeOrElse("start-time", DateTime.now)
    val timeScale = config.getDoubleOrElse("time-scale", 1.0)
    new SettableClock(date, timeScale, isStopped = true)
  }

  def wallClockStartTime: Option[DateTime] = {
    val startAt = config.getOptionalDateTime("start-at")
    if (startAt.isDefined) return startAt

    ifSome(config.getOptionalFiniteDuration("start-in")) { dur =>
      return Some(DateTime.now.plusMillis(dur.toMillis.toInt))
    }

    None
  }
  def wallClockEndTime: Option[DateTime] = {
    ifSome(config.getOptionalDateTime("end-time")) { date => // value is sim time
      return Some(simClock.wallTime(date))
    }
    ifSome(config.getOptionalFiniteDuration("run-for")) { dur => // again, in sim time
      return Some(simClock.wallTime(simClock.base.plus(dur.toMillis)))
    }
    None
  }

  def scheduleStart(date: DateTime) = {
    info(s"scheduling start of universe $name at $date")
    val dur = FiniteDuration(date.getMillis - System.currentTimeMillis(), MILLISECONDS)
    system.scheduler.scheduleOnce(dur, new Runnable {
      override def run: Unit = {
        ifSome(wallClockEndTime) { scheduleTermination }
        startActors
      }
    })
  }
  def scheduleTermination(date: DateTime) = {
    info(s"scheduling termination of universe $name at $date")
    val dur = FiniteDuration(date.getMillis - System.currentTimeMillis(), MILLISECONDS)
    system.scheduler.scheduleOnce(dur, new Runnable {
      override def run: Unit = terminate
    })
  }

  def createRaceContext(master: ActorRef, bus: Bus): RaceContext = RaceContext(master, bus)

  def getActorConfigs = config.getOptionalConfigList("actors")

  def createActors = {
    info(s"creating actors of universe $name ..")
    askVerifiableForResult(master, RaceCreate) {
      case RaceCreated => info(s"universe $name created")
      case TimedOut => error(s"creating universe $name timed out")
      case e => error(s"invalid response creating universe $name: $e")
    }
  }

  def initializeActors = {
    info(s"initializing actors of universe $name ..")
    askVerifiableForResult(master, RaceInitialize) {
      case RaceInitialized =>
        status = Initialized
        info(s"universe $name initialized")
      case TimedOut => error(s"initializing universe $name timed out")
      case e => error(s"invalid response initializing universe $name: $e")
    }
  }

  def startActors = {
    if (status == Initialized) {
      info(s"starting actors of universe $name ..")
      status = Started
      askVerifiableForResult(master, RaceStart) {
        case RaceStarted =>
          status = Running
          info(s"universe $name running")
        case TimedOut => warning(s"starting universe $name timed out")
        case e => error(s"invalid response starting universe $name: $e")
      }
    } else warning(s"universe $name cannot be started in state $status")
  }

  //--- actor termination

  def stoppedRaceActor(actorRef: ActorRef): Unit = {
    info(s"unregister stopped ${actorRef.path}")
    actors = actors.filter(_ != actorRef)
  }

  /**
   * graceful shutdown that synchronously processes terminateRaceActor() actions
   */
  def terminate: Boolean = {
    if (status == Initialized || status == Running) {
      info(s"universe $name terminating..")
      status = Terminating
      askVerifiableForResult(master, RaceTerminate) {
        case RaceTerminated =>
          raceTerminated
          true
        case TimedOut =>
          warning(s"universe $name termination timeout")
          false
      }
    } else { // nothing to shut down
      true
    }
  }

  def isTerminating = status == Terminating

  // internal (overridable) method to clean up *after* successful termination of all actors
  protected def raceTerminated: Unit = {
    info(s"universe $name terminated")
    status = common.Status.Terminated
    system.terminate
  }
  // some actor asked for termination
  def terminationRequest(actorRef: ActorRef) = {
    if (!isTerminating) { // avoid recursive termination
      if ((allowSelfTermination && isManagedActor(actorRef)) ||
        (allowRemoteTermination && isRemoteActor(actorRef))) terminate
      else warning(s"universe ignoring termination request from ${actorRef.path}")
    }
  }

  final val systemPrefix = s"akka://$name" // <2do> check managed remote actor paths
  def isRemoteActor(actorRef: ActorRef) = !actorRef.path.toString.startsWith(systemPrefix)

  def isManagedActor(actorRef: ActorRef) = actors.contains(actorRef)

  /**
   * hard shutdown command issued from outside the RaceActorSystem
   * NOTE - this might loose data since actors are not processing their terminateRaceActor()
   */
  def kill = {
    info(s"universe $name killed")
    status = common.Status.Terminated // there is no coming back from here
    system.terminate
  }

  override def logLevel: LogLevel = system.eventStream.logLevel
  override def setLogLevel(logLevel: LogLevel) = system.eventStream.setLogLevel(logLevel)

  def publish(channel: String, msg: Any) = bus.publish(BusEvent(channel, msg, master))

  def send(path: String, msg: Any) = system.actorSelection(path) ! msg

  //--- state query & display

  // NOTE - this performs a sync query
  def showActors = {
    for (((actorRef, _), i) <- actors.zipWithIndex) {
      print(f"  $i%2d: ${actorRef.path} ")

      askForResult(actorRef ? PingRaceActor()) {
        case PingRaceActor(tSent, tReceived) =>
          val tReturned = System.nanoTime()
          println(f"  ${tReceived - tSent}ns / ${tReturned - tReceived}ns")
        case TimedOut => println("  TIMEOUT")
        case other => println("  INVALID RESPONSE")
      }
    }
  }

  def showChannels = {
    println(bus.showChannelSubscriptions)
  }

}
