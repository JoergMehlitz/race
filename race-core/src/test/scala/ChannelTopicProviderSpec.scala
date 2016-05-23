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

import akka.actor.{ActorRef, ActorSystem, Cancellable}
import akka.event.Logging
import com.typesafe.config.Config
import gov.nasa.race.core.Messages._
import org.joda.time.DateTime
import org.scalatest.WordSpecLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object ChannelTopicProviderSpec {

  val N = 3
  val TOPIC = Some("tock")
  case object Tick
  case object Tock

  //------------------------- actors to test the mixed in ChannelTopicXX traits

  class TestServer(val config: Config) extends ChannelTopicProvider {
    val writeTo = config.getString("write-to")
    var n = 0
    var timer: Cancellable = null
    var msg: String = "??"

    override def startRaceActor(originator: ActorRef) = {
      super.startRaceActor(originator)
      println(s"$name scheduling ticks")
      timer = scheduler.schedule(1.second,1.second,self,Tick)
    }

    override def terminateRaceActor(originator: ActorRef) = {
      super.terminateRaceActor(originator)
      timer.cancel()
    }

    override def handleMessage: Receive = {
      case Tick =>
        if (hasClients) {
          n = n + 1
          assert(n <= N)
          println(s"$name publishing message $n '$msg' to channel '$writeTo'")
          publish( writeTo, msg)
        } else {
          println(s"$name ignoring tick (no clients)")
        }
    }

    override def isRequestAccepted (request: ChannelTopicRequest) = {
      request.channelTopic match {
        case ChannelTopic(`writeTo`, TOPIC) =>
          println(s"$name responding to $request")
          true
        case _ => false
      }
    }

    override def gotAccept (accept: ChannelTopicAccept) = {
      println(s"$name got accept $accept")
      msg = accept.channelTopic.topic.get.toString
    }

    override def gotRelease (release: ChannelTopicRelease) = {
      println(s"$name got release $release")
    }
  }

  //------------------------------------------------------------------
  class TestClient(val config: Config) extends ChannelTopicSubscriber {
    var n = 0

    override def startRaceActor(originator: ActorRef) = {
      super.startRaceActor(originator)
      println(s"$name requesting $TOPIC")
      requestTopic(TOPIC)
    }

    override def handleMessage = {
      case BusEvent(channel,msg,_) =>
        assert(hasSubscriptions)
        n = n + 1
        assert(n <= N)
        println(s"$name got message $n: '$msg' from channel '$channel'")
        if (n == N) {
          releaseAll
          println(s"$name releaseAll")
        }
    }

    override def isResponseAccepted(response: ChannelTopicResponse): Boolean = {
      if (response.channelTopic.topic == TOPIC) {
        println(s"$name accepting $response")
        true
      } else {
        println(s"$name rejecting $response")
        false
      }
    }
  }

  //------------------------------------------------------------------
  class TestTranslator (val config: Config) extends TransitiveChannelTopicProvider {
    var n = 0
    val writeTo = config.getString("write-to")

    override def handleMessage: Receive = {
      case BusEvent(channel,msg,_) if !msg.isInstanceOf[RaceSystemMessage] =>
        assert(hasSubscriptions)
        n = n + 1
        assert(n <= N)
        println(s"$name got message $n: '$msg' from channel '$channel'")
        if (hasClients) {
          val obj = Some(msg)
          println(s"$name publishing translated object '$obj' to channel '$writeTo'")
          publish(writeTo, obj)
        }
    }

    override def isRequestAccepted(request: ChannelTopicRequest): Boolean = {
      request.channelTopic match {
        case ChannelTopic(`writeTo`, TOPIC) =>
          println(s"$name responding to $request")
          true
        case _ => false
      }
    }
  }
}
import gov.nasa.race.core.ChannelTopicProviderSpec._

/**
  * tests for ChannelTopicProvider/Subscriber chains
  */
class ChannelTopicProviderSpec extends RaceActorSpec with WordSpecLike {

  "a provider-subscriber chain" must {
    "start and stop publishing messages on demand" in {
      runRaceActorSystem(Logging.WarningLevel) {
        val server = addTestActor(classOf[TestServer], "server", createConfig("write-to = testChannel"))
        val client = addTestActor(classOf[TestClient], "client", createConfig("read-from = testChannel"))

        printTestActors
        initializeTestActors
        printBusSubscriptions
        startTestActors(DateTime.now)
        Thread.sleep(6000)
        terminateTestActors

        assert(actor(server).n == N)
        assert(actor(client).n == N)
      }
    }
  }

  "a provider-translator-subscriber chain" must {
    "start and stop publishing messages transitively on demand" in {
      runRaceActorSystem(Logging.WarningLevel) {
        val server = addTestActor(classOf[TestServer], "server", createConfig("write-to= testString"))
        val translator = addTestActor(classOf[TestTranslator], "translator",
          createConfig("read-from= testString, write-to= testSome"))
        val client = addTestActor(classOf[TestClient], "client", createConfig("read-from= testSome"))

        printTestActors
        initializeTestActors
        printBusSubscriptions
        startTestActors(DateTime.now)
        Thread.sleep(6000)
        terminateTestActors

        assert(actor(server).n == N)
        assert(actor(translator).n == N)
        assert(actor(client).n == N)
      }
    }
  }
}
