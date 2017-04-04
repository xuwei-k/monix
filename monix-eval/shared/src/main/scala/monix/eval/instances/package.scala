/*
 * Copyright (c) 2014-2017 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.eval

package object instances {
  /** Newtype implementation for Scala */
  type Tagged[U] = { type Tag = U }

  /** Tag a type `T` with a tag.
    *
    * The resulting type is used to discriminate between type-class
    * instances. Similar in usage to `newtype` from Haskell.
    *
    * Credit: Miles Sabin and Scalaz
    */
  type @@[+T, Tag] = T with Tagged[Tag]
}
