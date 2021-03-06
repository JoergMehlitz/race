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

package gov.nasa.race.actors.routers

import gov.nasa.race.common.ConfigValueMapper
import gov.nasa.race.core.{PublishingRaceActor, BusEvent, SubscribingRaceActor}
import gov.nasa.race.common._
import gov.nasa.race.core._
import gov.nasa.race.common.ConfigUtils._
import com.typesafe.config.{ConfigValue, Config}
import scala.collection.JavaConversions._

/**
  * a generic, configurable content based router
  */
class ContentRouter (val config: Config) extends SubscribingRaceActor with PublishingRaceActor {

  val routes: Map[ConfigValue,String] = createRouteMap(config.getConfigList("routes"))
  val defaultRoute = config.getOptionalString("default-route")
  val mapper: ConfigValueMapper = createMapper(config.getConfig("mapper"))

  override def handleMessage = {
    case BusEvent(readFrom,msg,originator) =>
      mapper.translate(msg) match {
        case Some(value: ConfigValue) =>
          routes.get(value) match {
            case Some (writeTo: String) =>
              debug(f"$name routing to $writeTo : ${msg.toString}%20.20s..")
              publish (writeTo, msg) // <2do> shall we preserve the originator?
            case None => ifSome (defaultRoute) {publish (_, msg)}
          }
        case None => ifSome (defaultRoute) {publish (_, msg)}
      }
  }

  def createMapper (mapperConf: Config): ConfigValueMapper = {
    val clsName = mapperConf.getString("class")
    newInstance[ConfigValueMapper](clsName,Array(classOf[Config]),Array(mapperConf)).get
  }

  def createRouteMap (mappingList: Seq[Config]) = {
    mappingList.foldLeft(Map[ConfigValue,String]())( (map,route) => map + (route.getValue("value") -> route.getString("write-to")))
  }
}
