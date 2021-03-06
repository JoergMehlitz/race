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

package gov.nasa.race.data

import gov.nasa.race.common._
import squants.space.{Angle, Radians, Meters, Length}


/**
 * functions to convert cartesian ITWS grids into lat/lon
 * Based on Lincoln Labs Car92Projection
 *
 * <2do> verify signums (offsets and rotation)
 */
class ITWSGridProjection (val trpPos: LatLonPos,                  // tracon reference point in (φ,λ)
                          val xoffset: Length, yoffset: Length,   // trp -> grid origin (SW)
                          val rotation: Angle) {                  // trueN -> magN at trp

  // rotation constants
  final val SinΘ = sin(rotation)
  final val CosΘ = cos(rotation)

  /**
   * transform cartesian grid coordinates into LatLonPos(φ,λ)
   *
   * @param xGrid  horizontal distance relative to grid origin (SW corner)
   * @param yGrid  vertical distance relative to grid origin (SW corner)
   * @return LatLonPos with latitude and longitude angles
   */
  def toLatLonPos (xGrid: Length, yGrid: Length): LatLonPos = {
    val x = xGrid + xoffset
    val y = yGrid + yoffset
    val xʹ = x * CosΘ - y * SinΘ
    val yʹ = x * SinΘ + y * CosΘ

    var rTrans = Length0
    var rMerid = Length0
    var φ = Angle0
    var φʹ = Angle0

    repeat(4) {
      φʹ = (trpPos.φ + φ) / 2
      val tmpECC = 1 - E_ECC * sin2(φʹ)
      val sqrtECC = √(tmpECC)
      rTrans = Meters(RE_E / sqrtECC)
      rMerid = Meters(RE_E * (1.0 - E_ECC) / (tmpECC * sqrtECC))
      φ = trpPos.φ + Radians( yʹ / rMerid)
    }

    val λ = trpPos.λ + Radians( xʹ / (rTrans * cos((trpPos.φ + φ)/2)))
    LatLonPos(φ,λ)
  }

}
