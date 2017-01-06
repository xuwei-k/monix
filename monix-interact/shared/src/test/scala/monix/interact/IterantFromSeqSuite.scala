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

package monix.interact

import monix.eval.Task
import scala.collection.mutable.ListBuffer

object IterantFromSeqSuite extends BaseTestSuite {
  test("TaskStream.fromSeq(vector)") { implicit s =>
    check1 { (list: List[Int]) =>
      val result = TaskStream.fromSeq(list.toVector).toListL
      result === Task.now(list)
    }
  }

  test("TaskStream.fromSeq(list)") { implicit s =>
    check1 { (list: List[Int]) =>
      val result = TaskStream.fromSeq(list).toListL
      result === Task.now(list)
    }
  }

  test("TaskStream.fromSeq(iterable)") { implicit s =>
    check1 { (list: List[Int]) =>
      val result = TaskStream.fromSeq(list.to[ListBuffer]).toListL
      result === Task.now(list)
    }
  }
}
