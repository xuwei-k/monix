/*
 * Copyright (c) 2014-2016 by its authors. Some rights reserved.
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

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success}

object TaskStreamSuite extends BaseTestSuite {
  test("TaskStream.filter") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Task.now(numbers.filter(_ % 2 == 0))
      val stream = TaskStream.fromList(numbers).filter(_ % 2 == 0).toListL
      expect === stream
    }
  }

  test("TaskStream.filter(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Task.now(numbers.filter(_ % 2 == 0))
      val stream = TaskStream.fromList(numbers, 4).filter(_ % 2 == 0).toListL
      expect === stream
    }
  }

  test("TaskStream.filter should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream.now(1).filter(_ => throw ex).firstL.runAsync
    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.filter(batched) should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream.consSeq(List(1), Task.now(TaskStream.empty))
      .filter(_ => throw ex).firstL.runAsync

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.map") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Task.now(numbers.map(_ + 1))
      val stream = TaskStream.fromList(numbers).map(_ + 1).toListL
      expect === stream
    }
  }

  test("TaskStream.map(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Task.now(numbers.map(_ + 1))
      val stream = TaskStream.fromList(numbers, 4).map(_ + 1).toListL
      expect === stream
    }
  }

  test("TaskStream.map should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream.now(1).map(_ => throw ex).firstL.runAsync
    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.map(batched) should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream.consSeq(List(1), Task.now(TaskStream.empty))
      .map(_ => throw ex).firstL.runAsync

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.flatMap") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Task.now(numbers.flatMap(x => List(x,x,x)))
      val stream = TaskStream.fromList(numbers).flatMap(x => TaskStream.fromList(List(x,x,x))).toListL
      expect === stream
    }
  }

  test("TaskStream.flatMap(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Task.now(numbers.flatMap(x => List(x,x,x)))
      val stream = TaskStream.fromList(numbers, 4).flatMap(x => TaskStream.fromList(List(x,x,x), 2)).toListL
      expect === stream
    }
  }

  test("TaskStream.flatMap should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream.now(1).flatMap(_ => throw ex).firstL.runAsync
    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.flatMap(batched) should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream.consSeq(List(1), Task.now(TaskStream.empty))
      .flatMap(_ => throw ex).firstL.runAsync

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.flatten == flatMap(x => x)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers).map(x => TaskStream.fromList(List(x,x))).flatten.toListL
      val expect = TaskStream.fromList(numbers).flatMap(x => TaskStream.fromList(List(x,x))).toListL
      expect === stream
    }
  }

  test("TaskStream.concat == flatMap(x => x)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers).map(x => TaskStream.fromList(List(x,x))).concat.toListL
      val expect = TaskStream.fromList(numbers).flatMap(x => TaskStream.fromList(List(x,x))).toListL
      expect === stream
    }
  }

  test("TaskStream.concatMap == flatMap") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers).concatMap(x => TaskStream.fromList(List(x,x))).toListL
      val expect = TaskStream.fromList(numbers).flatMap(x => TaskStream.fromList(List(x,x))).toListL
      expect === stream
    }
  }

  test("TaskStream #:: elem") { implicit s =>
    check2 { (numbers: List[Int], head: Int) =>
      val expect = Task.now(head :: numbers)
      val stream = (head #:: TaskStream.fromList(numbers)).toListL
      expect === stream
    }
  }

  test("TaskStream.foldLeftL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Task.now(numbers.sum)
      val stream = TaskStream.fromList(numbers).foldLeftL(0)(_+_)
      expect === stream
    }
  }

  test("TaskStream.foldLeftL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Task.now(numbers.sum)
      val stream = TaskStream.fromList(numbers, 4).foldLeftL(0)(_+_)
      expect === stream
    }
  }

  test("TaskStream.foldLeftL should protect against user code, test 1") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream.now(1).foldLeftL(0)((a,e) => throw ex).runAsync
    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.foldLeftL should protect against user code, test 2") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream.now(1).foldLeftL((throw ex) : Int)(_ + _).runAsync
    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.foldLeftL(batched) should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream.consSeq(List(1), Task.now(TaskStream.empty))
      .foldLeftL(0)((a,e) => throw ex).runAsync

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.foldWhileL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Task.now(numbers.sum)
      val stream = TaskStream.fromList(numbers).foldWhileL(0)((acc,e) => (true, acc+e))
      expect === stream
    }
  }

  test("TaskStream.foldWhileL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Task.now(numbers.sum)
      val stream = TaskStream.fromList(numbers, 4).foldWhileL(0)((acc,e) => (true, acc+e))
      expect === stream
    }
  }

  test("TaskStream.foldWhileL should protect against user code, test 1") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream.now(1).foldWhileL(0)((a,e) => throw ex).runAsync
    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.foldWhileL should protect against user code, test 2") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream.now(1).foldWhileL((throw ex) : Int)((_,_) => (true,0)).runAsync
    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.foldWhileL(batched) should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream
      .consSeq(List(1), Task.now(TaskStream.empty))
      .foldWhileL(0)((a,e) => throw ex).runAsync

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.foldRightL") { implicit s =>
    check1 { (numbers: List[Boolean]) =>
      val expect = Task.now(numbers.forall(x => x))
      val stream = TaskStream.fromList(numbers).foldRightL(Task.now(true)) {
        (elem, acc) => if (elem) acc else Task.now(elem)
      }

      expect === stream
    }
  }

  test("TaskStream.foldRightL(batched)") { implicit s =>
    check1 { (numbers: List[Boolean]) =>
      val expect = Task.now(numbers.forall(x => x))
      val stream = TaskStream.fromList(numbers,4).foldRightL(Task.now(true)) {
        (elem, acc) => if (elem) acc else Task.now(elem)
      }

      expect === stream
    }
  }

  test("TaskStream.foldRightL shoudl protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream.now(1).foldRightL(Task.now(true))((_,_) => throw ex).runAsync
    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.foldRightL(batched) shoudl protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream
      .consSeq(List(1), Task.now(TaskStream.empty))
      .foldRightL(Task.now(true))((_,_) => throw ex).runAsync

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.fromList ++ TaskStream.fromList") { implicit s =>
    check2 { (seq1: List[Int], seq2: List[Int]) =>
      val expect = Task.now(seq1 ++ seq2)
      val stream = (TaskStream.fromList(seq1) ++ TaskStream.fromList(seq2)).toListL
      expect === stream
    }
  }

  test("TaskStream.defer(TaskStream.fromList) ++ TaskStream.fromList") { implicit s =>
    check2 { (seq1: List[Int], seq2: List[Int]) =>
      val expect = Task.now(seq1 ++ seq2)
      val stream = (TaskStream.defer(TaskStream.fromList(seq1)) ++ TaskStream.fromList(seq2)).toListL
      expect === stream
    }
  }

  test("TaskStream.fromList ++ TaskStream.defer(TaskStream.fromList)") { implicit s =>
    check2 { (seq1: List[Int], seq2: List[Int]) =>
      val expect = Task.now(seq1 ++ seq2)
      val stream = (TaskStream.defer(TaskStream.fromList(seq1)) ++ TaskStream.fromList(seq2)).toListL
      expect === stream
    }
  }

  test("TaskStream.fromList(batched) ++ TaskStream.fromList(batched)") { implicit s =>
    check2 { (seq1: List[Int], seq2: List[Int]) =>
      val expect = Task.now(seq1 ++ seq2)
      val stream = (TaskStream.fromList(seq1,4) ++ TaskStream.fromList(seq2,4)).toListL
      expect === stream
    }
  }

  test("TaskStream.findL") { implicit s =>
    check2 { (numbers: List[Int], n: Int) =>
      val stream = TaskStream.fromList(numbers)
      stream.findL(_ == n) === Task.now(numbers.find(_ == n))
    }
  }

  test("TaskStream.findL(batched)") { implicit s =>
    check2 { (numbers: List[Int], n: Int) =>
      val stream = TaskStream.fromList(numbers,4)
      stream.findL(_ == n) === Task.now(numbers.find(_ == n))
    }
  }

  test("TaskStream.findL is true") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers)

      numbers.lastOption match {
        case Some(toFind) =>
          stream.findL(_ == toFind) === Task.now(numbers.find(_ == toFind))
        case None =>
          stream.findL(_ == 0) === Task.now(None)
      }
    }
  }

  test("TaskStream.existsL") { implicit s =>
    check2 { (numbers: List[Int], n: Int) =>
      val stream = TaskStream.fromList(numbers)
      stream.existsL(_ == n) === Task.now(numbers.contains(n))
    }
  }

  test("TaskStream.existsL(batched)") { implicit s =>
    check2 { (numbers: List[Int], n: Int) =>
      val stream = TaskStream.fromList(numbers,4)
      stream.existsL(_ == n) === Task.now(numbers.contains(n))
    }
  }

  test("TaskStream.forallL") { implicit s =>
    check2 { (numbers: List[Int], n: Int) =>
      val stream = TaskStream.fromList(numbers)
      stream.forallL(_ == n) === Task.now(numbers.forall(_ == n))
    }
  }

  test("TaskStream.forallL(batched)") { implicit s =>
    check2 { (numbers: List[Int], n: Int) =>
      val stream = TaskStream.fromList(numbers,4)
      stream.forallL(_ == n) === Task.now(numbers.forall(_ == n))
    }
  }

  test("TaskStream.countL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers)
      stream.countL === Task.now(numbers.length)
    }
  }

  test("TaskStream.countL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers,4)
      stream.countL === Task.now(numbers.length)
    }
  }

  test("TaskStream.sumL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers)
      stream.sumL === Task.now(numbers.sum)
    }
  }

  test("TaskStream.sumL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers,4)
      stream.sumL === Task.now(numbers.sum)
    }
  }

  test("TaskStream.isEmptyL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers)
      stream.isEmptyL === Task.now(numbers.isEmpty)
    }
  }

  test("TaskStream.isEmptyL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers,4)
      stream.isEmptyL === Task.now(numbers.isEmpty)
    }
  }

  test("TaskStream.nonEmptyL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers)
      stream.nonEmptyL === Task.now(numbers.nonEmpty)
    }
  }

  test("TaskStream.nonEmptyL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers,4)
      stream.nonEmptyL === Task.now(numbers.nonEmpty)
    }
  }

  test("TaskStream.firstL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers)
      stream.firstL === Task.now(numbers.headOption)
    }
  }

  test("TaskStream.firstL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers,4)
      stream.firstL === Task.now(numbers.headOption)
    }
  }

  test("TaskStream.headOptionL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers)
      stream.headOptionL === Task.now(numbers.headOption)
    }
  }

  test("TaskStream.headOptionL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers,4)
      stream.headOptionL === Task.now(numbers.headOption)
    }
  }

  test("TaskStream.headL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers)
      stream.headL === Task.evalAlways(numbers.head)
    }
  }

  test("TaskStream.headL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers,4)
      stream.headL === Task.evalAlways(numbers.head)
    }
  }

  test("TaskStream.take") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers)
      stream.take(5).toListL === Task.now(numbers.take(5))
    }
  }

  test("TaskStream.take(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers,4)
      stream.take(5).toListL === Task.now(numbers.take(5))
    }
  }

  test("TaskStream.takeWhile") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers)
      stream.takeWhile(_ >= 0).toListL === Task.now(numbers.takeWhile(_ >= 0))
    }
  }

  test("TaskStream.takeWhile(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers,4)
      stream.takeWhile(_ >= 0).toListL === Task.now(numbers.takeWhile(_ >= 0))
    }
  }

  test("TaskStream.memoize") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers)
      stream.memoize.toListL === Task.now(numbers)
    }
  }

  test("TaskStream.memoize(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers,4)
      stream.memoize.toListL === Task.now(numbers)
    }
  }

  test("TaskStream.onErrorHandleWith equivalence") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers).onErrorHandleWith(_ => TaskStream.empty)
      stream.memoize.toListL === Task.now(numbers)
    }
  }

  test("TaskStream.onErrorHandleWith(batched) equivalence") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers,4).onErrorHandleWith(_ => TaskStream.empty)
      stream.memoize.toListL === Task.now(numbers)
    }
  }

  test("TaskStream.onErrorHandleWith recovers") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val ex = DummyException("dummy")
      val recovery = List(1,2,3)
      val stream = (TaskStream.fromList(numbers) ++ TaskStream.raiseError[Int](ex))
        .onErrorHandleWith { case `ex` => TaskStream.fromList(recovery) }
      stream.memoize.toListL === Task.now(numbers ++ recovery)
    }
  }

  test("TaskStream.onErrorHandle equivalence") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers).onErrorHandle(_ => 0)
      stream.memoize.toListL === Task.now(numbers)
    }
  }

  test("TaskStream.onErrorHandle(batched) equivalence") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers,4).onErrorHandle(_ => 0)
      stream.memoize.toListL === Task.now(numbers)
    }
  }

  test("TaskStream.onErrorHandle recovers") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val ex = DummyException("dummy")
      val stream = (TaskStream.fromList(numbers) ++ TaskStream.raiseError[Int](ex))
        .onErrorHandle { case `ex` => 1 }
      stream.memoize.toListL === Task.now(numbers :+ 1)
    }
  }

  test("TaskStream.onErrorRecoverWith equivalence") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers).onErrorRecoverWith { case _ => TaskStream.empty }
      stream.memoize.toListL === Task.now(numbers)
    }
  }

  test("TaskStream.onErrorRecoverWith(batched) equivalence") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers,4).onErrorRecoverWith { case _ => TaskStream.empty }
      stream.memoize.toListL === Task.now(numbers)
    }
  }

  test("TaskStream.onErrorRecoverWith recovers") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val ex = DummyException("dummy")
      val recovery = List(1,2,3)
      val stream = (TaskStream.fromList(numbers) ++ TaskStream.raiseError[Int](ex))
        .onErrorRecoverWith { case `ex` => TaskStream.fromList(recovery) }
      stream.memoize.toListL === Task.now(numbers ++ recovery)
    }
  }

  test("TaskStream.onErrorRecover equivalence") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers).onErrorRecover { case _ => 0 }
      stream.memoize.toListL === Task.now(numbers)
    }
  }

  test("TaskStream.onErrorRecover(batched) equivalence") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers,4).onErrorRecover { case _ => 0 }
      stream.memoize.toListL === Task.now(numbers)
    }
  }

  test("TaskStream.onErrorRecover recovers") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val ex = DummyException("dummy")
      val stream = (TaskStream.fromList(numbers) ++ TaskStream.raiseError[Int](ex))
        .onErrorRecover { case `ex` => 1 }
      stream.memoize.toListL === Task.now(numbers :+ 1)
    }
  }

  test("TaskStream.drop") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers)
      stream.drop(5).toListL === Task.now(numbers.drop(5))
    }
  }

  test("TaskStream.drop(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers,4)
      stream.drop(5).toListL === Task.now(numbers.drop(5))
    }
  }

  test("TaskStream.completedL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers)
      stream.completedL === Task.now(())
    }
  }

  test("TaskStream.completedL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = TaskStream.fromList(numbers)
      stream.completedL === Task.now(())
    }
  }

  test("TaskStream.foreachL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val task = Task.evalAlways(ListBuffer.empty[Int]).flatMap { buffer =>
        val f = TaskStream.fromList(numbers).foreachL(n => buffer.append(n))
        f.map(_ => buffer.toList)
      }

      task === Task.now(numbers)
    }
  }

  test("TaskStream.foreachL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val task = Task.evalAlways(ListBuffer.empty[Int]).flatMap { buffer =>
        val f = TaskStream.fromList(numbers,4).foreachL(n => buffer.append(n))
        f.map(_ => buffer.toList)
      }

      task === Task.now(numbers)
    }
  }

  test("TaskStream.foreachL should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream.now(1).foreach(_ => throw ex)
    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.foreach") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val task = Task.evalAlways(ListBuffer.empty[Int]).flatMap { buffer =>
        val f = TaskStream.fromList(numbers).foreach(n => buffer.append(n))
        Task.fromFuture(f).map(_ => buffer.toList)
      }

      task === Task.now(numbers)
    }
  }

  test("TaskStream.foreach(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val task = Task.evalAlways(ListBuffer.empty[Int]).flatMap { buffer =>
        val f = TaskStream.fromList(numbers,4).foreach(n => buffer.append(n))
        Task.fromFuture(f).map(_ => buffer.toList)
      }

      task === Task.now(numbers)
    }
  }

  test("TaskStream.evalAlways") { implicit s =>
    val f = TaskStream.evalAlways(10).toListL.runAsync
    assertEquals(f.value, Some(Success(List(10))))
  }

  test("TaskStream.evalAlways should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream.evalAlways[Int](throw ex).toListL.runAsync
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.evalOnce") { implicit s =>
    val f = TaskStream.evalOnce(10).toListL.runAsync
    assertEquals(f.value, Some(Success(List(10))))
  }

  test("TaskStream.evalOnce should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val f = TaskStream.evalOnce[Int](throw ex).toListL.runAsync
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("TaskStream.fromIterable(batch=1)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val task = TaskStream.fromIterable(numbers, 1).toListL
      val expect = TaskStream.fromList(numbers, 100).toListL
      task === expect
    }
  }

  test("TaskStream.fromIterable(batch=4)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val task = TaskStream.fromIterable(numbers, 4).toListL
      val expect = TaskStream.fromList(numbers, 100).toListL
      task === expect
    }
  }

  test("TaskStream.fromIterable(batch=1) (Java)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      import collection.JavaConverters._
      val task = TaskStream.fromIterable(numbers.toIterable.asJava, 1).toListL
      val expect = TaskStream.fromList(numbers, 100).toListL
      task === expect
    }
  }

  test("TaskStream.fromIterable(batch=4) (Java)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      import collection.JavaConverters._
      val task = TaskStream.fromIterable(numbers.toIterable.asJava, 4).toListL
      val expect = TaskStream.fromList(numbers, 100).toListL
      task === expect
    }
  }
}
