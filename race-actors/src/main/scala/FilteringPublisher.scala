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

package gov.nasa.race.actors

import com.typesafe.config.Config
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.common.ConfigurableFilter
import gov.nasa.race.core._

/**
  * trait for PublishingRaceActors that support filters
  */
trait FilteringPublisher extends PublishingRaceActor {

  val config: Config // actor config to be provided by concrete actor class
  val writeTo: String  // default publishing channel, provided by concrete actor class

  val passUnfiltered = letPassUnfiltered(config) // do we let pass if there is no filter set?
  val filters = createFilters(config) // optional
  val firstMatchOnly = config.getBooleanOrElse("first-match", false)

  // override this if we have specific filters
  def createFilters (config: Config) = config.getOptionalConfigList("filters").map(createFilter)

  // override this if we only want to let messages pass if we have filters set
  def letPassUnfiltered (config: Config) = config.getBooleanOrElse("pass-unfiltered", true)


  def createFilter (config: Config): ConfigurableFilter = {
    val filter = newInstance[ConfigurableFilter](config.getString("class"), Array(classOf[Config]), Array(config)).get
    info(s"$name instantiated filter ${filter.name}")
    filter
  }

  def publishFiltered (msg: Any): Unit= {
    @inline def _publish(channel: String, msg: Any) = {
      info(f"$name publishing to: $channel message: $msg%30.30s..")
      publish(channel,msg)
    }

    if (filters.isEmpty) { // no filter -> publish to default channel
      if (passUnfiltered) _publish(writeTo, msg)
    } else {
      for (filter <- filters) {
        if (filter.pass(msg)) {
          _publish( filter.config.getStringOrElse("write-to", writeTo), msg)
          if (firstMatchOnly) return
        }
      }
    }
  }
}
