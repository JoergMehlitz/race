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

package gov.nasa.race.actors.metrics.swimstats

import com.typesafe.config.Config
import gov.nasa.race.core.BusEvent

import scala.xml.{Elem, Text, XML}

class SiteMonitor (val config: Config) extends MonitorActor {

  override def handleMessage = {
    case BusEvent(_,xml:String,_) if xml.nonEmpty => {
      val facility = getFacility(xml)
      monitorMsg(xml,facility)
    }
  }

  override def getFacility (o: Any) : String = {
    o match {
      case txt: String => {
        val doc = XML.loadString(txt)

        doc match {
          case item @ Elem( _, "itws_msg", _, _, _, content @ _ *) => {
            for(e1 <- doc \ "_") {
              e1 match {
                case item @ Elem( _, "product_header", _, _, _, content @ _ *) => {
                  for(e2 <- e1 \ "_") {
                    e2 match {
                      case <product_header_itws_sites>{Text(facility)}</product_header_itws_sites> =>
                        return facility
                      case _ => // don't care
                    }
                  }
                }
                case _ => // don't care
              }
            }
          }
          case _ => {
            println(s"different message type --> ${doc.label}")
          }
        }
      }
      case _ =>
    }
    return ""
  }
}
