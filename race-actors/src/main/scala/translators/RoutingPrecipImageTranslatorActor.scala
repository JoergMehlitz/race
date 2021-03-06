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

package gov.nasa.race.actors.translators

import com.typesafe.config.Config
import gov.nasa.race.core._
import gov.nasa.race.core.BusEvent
import gov.nasa.race.data.PrecipImage
import gov.nasa.race.data.translators.ITWSprecip2PrecipImage

/**
  * a specialized ITWS precip to PrecipImage translator that publishes
  * to precipitation product subchannels of the specified output channel
  *
  * There are currently three product types:
  *   - 9849: long range
  *   - 9850: TRACON
  *   - 9905: 5nm
  *
  * This is just a convenience actor that saves some configuration for
  * filter and content based routing
  */
class RoutingPrecipImageTranslatorActor (config: Config) extends TranslatorActor(config, new ITWSprecip2PrecipImage) {

  val writeToChannels = Map (9849 -> (writeTo + "9849"), 9850 -> (writeTo + "9850"), 9905 -> (writeTo + "9905"))

  override def handleMessage = {
    case BusEvent(_, xml: String, _) if xml.nonEmpty =>
      translator.translate(xml) match {
        case Some(precipImage:PrecipImage) =>
          writeToChannels.get(precipImage.product) match {
            case Some(writeTo) => publish(writeTo, precipImage)
            case None => info(s"$name not routing precip type ${precipImage.product}")
          }
        case None => warning(s"precip image translation failed")
        case other => warning(s"unsupported translation type: ${other.getClass}")
      }
  }
}
