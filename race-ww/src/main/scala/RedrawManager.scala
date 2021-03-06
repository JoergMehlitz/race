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

package gov.nasa.race.ww

import gov.nasa.race.swing.Redrawable
import gov.nasa.worldwind.WorldWindow
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * the factory singleton for redraw policies
 */
object RedrawManager {
  def apply (wwd: Redrawable) = new SlidingTimeFramePolicy(wwd)
}

/**
 * abstract class to handle redraw requests of WorldWind layers, since WW only
 * provides a undiscriminating redraw() that can cause frequent repaint operations
 * for backlogged layer updates.
 *
 * [R-100.4.1] concrete implementation has to prevent starvation of non-rendering threads
 * [R-100.4.2] concrete implementation has to guarantee feedback within given time
 *
 * NOTE - we use the abstract `Redrawable` instead of `WorldWindow` so that we
 * can easily implement unit tests without having to create a real WorldWind instance
 * (all we need here are `redraw()` and `redrawNow()` methods)
 *
 * NOTE - while this could use a structural type, those incur additional runtime
 * costs (reflection call) which we want to avoid here
 */
abstract class RedrawManager (val wwd: Redrawable) {
  protected var pending: Option[Future[Any]] = None
  @volatile protected var lastTime: Long = 0 // time of most recent request

  def redraw()

  // only the generic version - override if it has to sync with redraw()
  def redrawNow() = wwd.redrawNow()
}

/**
 * simple example policy for redraw management:
 *
 * update `minDelay` msecs after an initiating request, unless there was another
 * request within that period, in which case we wait for another `minDelay` period,
 * but ONLY if we don't exceed a `maxDelay` limit
 *
 * <2do> we might want to turn this into a permanent (non-pooled) thread to avoid
 * the pooling overhead. However, we do this for controlled delay and the pool might
 * be re-used for other purposes too (timers etc.)
 *
 * <2do> sync redrawNow() and redraw() - a redrawNow() should count as a maxDelay redraw
 */
class SlidingTimeFramePolicy (wwd: Redrawable, val minDelay: Long=300, val maxDelay: Long=600)
                                                                         extends RedrawManager(wwd) {
  def redraw() = {
    lastTime = System.currentTimeMillis
    synchronized {
      if (pending == None) {
        pending = Some(Future {
          var startTime = lastTime
          var curTime: Long = 0
          do {
            Thread.sleep(minDelay)
            curTime = System.currentTimeMillis
            if (curTime - startTime > maxDelay) {
              wwd.redraw()
              startTime = curTime
            }
          } while ((curTime - lastTime) < minDelay)
          if (startTime != curTime){ // means we had redraw requests since the last MAX
            wwd.redraw()
          }
          synchronized {pending = None}
        })
      }
    }
  }
}


