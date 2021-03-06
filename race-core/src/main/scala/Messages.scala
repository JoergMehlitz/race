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

import akka.actor.ActorRef
import com.typesafe.config.Config
import org.joda.time.DateTime

import scala.concurrent.duration.FiniteDuration

/**
 * RACE specific messages
 */
object Messages {

  trait RaceSystemMessage // a message type that is processed by RaceActor.handleSystemMessage

  //--- RaceActor system messages (processed by core RaceActor traits)
  // each of the system messages has an associated callback function of the same (lowercase)
  // name and arguments, e.g. initializeRaceActor(raceContext,actorConf). If concrete
  // RaceActors override them (and don't want to deliberately override system processing)
  // they have to call the respective super methods (e.g. super.initializeRaceActor)

  /** set RaceContext and do runtime initialization of RaceActors */
  case class InitializeRaceActor (raceContext: RaceContext, actorConfig: Config) extends RaceSystemMessage
  case object RaceActorInitialized extends RaceSystemMessage
  case class RaceActorInitializeFailed (reason: String) extends RaceSystemMessage

  /** inform RaceActor of simulation start */
  case class StartRaceActor (originator: ActorRef) extends RaceSystemMessage
  case object RaceActorStarted extends RaceSystemMessage
  case class RaceActorStartFailed (reason: String) extends RaceSystemMessage

  /** pause/resume of RaceActors */
  case class PauseRaceActor (originator: ActorRef) extends RaceSystemMessage
  case object RaceActorPaused extends RaceSystemMessage
  case class RaceActorPauseFailed (reason: String) extends RaceSystemMessage

  case class ResumeRaceActor (originator: ActorRef) extends RaceSystemMessage
  case object RaceActorResumed extends RaceSystemMessage
  case class RaceActorResumeFailed (reason: String) extends RaceSystemMessage

  /** liveness check */
  case object ProcessRaceActor extends RaceSystemMessage
  case object RaceActorProcessed extends RaceSystemMessage
  case class PingRaceActor (tSentNanos: Long=System.nanoTime(), tReceivedNanos: Long=0) extends RaceSystemMessage

  /** inform RaceActor of termination */
  case class TerminateRaceActor (originator: ActorRef) extends RaceSystemMessage
  case object RaceActorTerminated extends RaceSystemMessage
  case object RaceActorTerminateIgnored extends RaceSystemMessage
  case class RaceActorTerminateFailed (reason: String) extends RaceSystemMessage

  case object RaceTerminateRequest  // ras internal termination request: RaceActor -> Master
  case object RaceAck // generic acknowledgement
  case object RaceCheck // used to trigger actor specific check action

  //--- RaceActorSystem control messages (not processed by handleMessage)
  case object RaceCreate
  case object RaceCreated
  case object RaceInitialize
  case object RaceInitialized
  case object RaceStart
  case object RaceStarted
  case object RaceTerminate
  case object RaceTerminated

  // time keeping between actor systems
  case class StartSimClock (date: DateTime, timeScale: Double)
  case class SyncSimClock (date: DateTime, timeScale: Double)
  case object StopSimClock
  case object ResumeSimClock

  // dynamic subscriptions
  case class Subscribe (channel: String)
  case class Unsubscribe (channel: String)
  case class Publish (channel: String, msg: Any)

  //--- messages to support remote bus subscribers/publishers, processed by BusConnector
  case class RemoteSubscribe (actorRef: ActorRef, channel: Channel) extends RaceSystemMessage // -> master
  case class RemoteUnsubscribe (actorRef: ActorRef, channel: Channel)  extends RaceSystemMessage // -> master
  case class RemotePublish (event: BusEvent) extends RaceSystemMessage

  //--- dynamic channel provider lookup & response
  // note it is still the responsibility of the client to subscribe
  /**
    *  look up channel providers:  c->{p}
    */
  case class ChannelTopicRequest (channelTopic: ChannelTopic, requester: ActorRef)  extends RaceSystemMessage {
    def toAccept = ChannelTopicAccept(channelTopic,requester)
    def toResponse(provider: ActorRef) = ChannelTopicResponse(channelTopic, provider)
  }

  /**
    *  response from potential provider: {p}->c
    */
  case class ChannelTopicResponse (channelTopic: ChannelTopic, provider: ActorRef)  extends RaceSystemMessage {
    def toAccept(client: ActorRef) = ChannelTopicAccept(channelTopic,client)
    def toRelease(client: ActorRef) = ChannelTopicRelease(channelTopic,client)
  }

  /**
    * client accepts (registers with one) provider: c->p
    */
  case class ChannelTopicAccept (channelTopic: ChannelTopic, client: ActorRef)  extends RaceSystemMessage {
    def toRelease = ChannelTopicRelease(channelTopic,client)
  }

  /**
    * client releases registered provider
    */
  case class ChannelTopicRelease (channelTopic: ChannelTopic, client: ActorRef)  extends RaceSystemMessage

  // <2do> we also need a message to indicate that a provider with live subscribers is terminated


  case class SetTimeout (msg: Any, duration: FiniteDuration) extends RaceSystemMessage

  case class ChildNodeRollCall (originator: ActorRef, parent: Option[RollCall] = None) extends RollCall
}
