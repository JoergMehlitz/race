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
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.common.{ConfigurableTranslator, _}
import gov.nasa.race.core.{BusEvent, PublishingRaceActor, SubscribingRaceActor, _}

/**
 * a generic actor that translates text messages into objects by means of a
 * configured Translator instance
 */
class TranslatorActor (val config: Config, var translator: ConfigurableTranslator) extends SubscribingRaceActor with PublishingRaceActor {
  def this(config: Config) = this(config, null)

  val writeTo = config.getString("write-to")

  translator = if (translator == null) { // we need one or init fails
    createTranslator(config.getConfig("translator"))
  } else { // can be optionally overridden from config
    config.getOptionalConfig("translator") match {
      case Some(tConf) => createTranslator(tConf)
      case None => translator
    }
  }

  override def handleMessage = {
    case BusEvent(_, xml: String, _) if xml.nonEmpty =>
      ifSome(translator.translate(xml)) {
        obj => publish(writeTo, obj)
      }
  }

  def createTranslator (config: Config): ConfigurableTranslator = {
    val translator = newInstance[ConfigurableTranslator](config.getString("class"), Array(classOf[Config]), Array(config)).get
    info(s"$name instantiated translator ${translator.name}")
    translator
  }
}
