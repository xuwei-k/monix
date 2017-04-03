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

package monix.types

import monix.eval.{Coeval, Task}
import monix.execution.misc.HygieneUtilMacros

import scala.reflect.macros.whitebox

/** Macro implementations for integration with Cats of the [[monix.eval]] package.
  */
@macrocompat.bundle
class EvalMacros(override val c: whitebox.Context) extends HygieneUtilMacros {
  import c.universe._

  def catsTaskTypeClassInstances(s: Tree): Tree = {
    val StrategyType = symbolOf[ApplicativeStrategy]
    val StrategyCompanion = symbolOf[ApplicativeStrategy].companion

    q"""
    ($s : $StrategyType) match {
      case $StrategyCompanion.Serial =>
        ${symbolOf[CatsSerialTaskInstances].companion}
      case $StrategyCompanion.Parallel =>
        ${symbolOf[CatsParallelTaskInstances].companion}
    }
    """
  }

  def catsParallelTaskTypeClassInstances: Tree = {
    val ParallelSym = symbolOf[Task.Parallel[_]]
    val CatsTaskInstancesSym = symbolOf[CatsTaskInstances[Task.Parallel]]
    val instances = symbolOf[CatsParallelTaskInstances].companion
    q"""$instances.asInstanceOf[$CatsTaskInstancesSym[$ParallelSym]]"""
  }

  def taskGroupInstance[A : WeakTypeTag]: Tree = {
    val ap = c.inferImplicitValue(weakTypeOf[cats.Applicative[Task]], silent=true)
    if (ap == EmptyTree)
      c.abort(c.macroApplication.pos, "Cannot find implicit for cats.Applicative[Task]")
    else {
      val gr = c.inferImplicitValue(weakTypeOf[cats.Group[A]], silent=true)
      if (gr == EmptyTree)
        c.abort(c.macroApplication.pos, "Cannot find implicit for cats.Group[A]")
      else
        q"""
        new ${symbolOf[CatsTaskGroupInstance[A]]}()($ap, $gr)
        """
    }
  }

  def taskMonoidInstance[A : WeakTypeTag]: Tree = {
    val ap = c.inferImplicitValue(weakTypeOf[cats.Applicative[Task]], silent=true)
    if (ap == EmptyTree)
      c.abort(c.macroApplication.pos, "Cannot find implicit for cats.Applicative[Task]")
    else {
      val gr = c.inferImplicitValue(weakTypeOf[cats.Monoid[A]], silent=true)
      if (gr == EmptyTree)
        c.abort(c.macroApplication.pos, "Cannot find implicit for cats.Monoid[A]")
      else
        q"""
        new ${symbolOf[CatsTaskMonoidInstance[A]]}()($ap, $gr)
        """
    }
  }

  def taskSemigroupInstance[A : WeakTypeTag]: Tree = {
    val ap = c.inferImplicitValue(weakTypeOf[cats.Applicative[Task]], silent=true)
    if (ap == EmptyTree)
      c.abort(c.macroApplication.pos, "Cannot find implicit for cats.Applicative[Task]")
    else {
      val gr = c.inferImplicitValue(weakTypeOf[cats.Semigroup[A]], silent=true)
      if (gr == EmptyTree)
        c.abort(c.macroApplication.pos, "Cannot find implicit for cats.Semigroup[A]")
      else
        q"""
        new ${symbolOf[CatsTaskSemigroupInstance[A]]}()($ap, $gr)
        """
    }
  }

  def catsCoevalTypeClassInstances: Tree =
    q"""${symbolOf[CatsCoevalDefaultInstances].companion}"""

  def coevalGroupInstance[A : WeakTypeTag]: Tree = {
    val ap = c.inferImplicitValue(weakTypeOf[cats.Applicative[Coeval]], silent=true)
    if (ap == EmptyTree)
      c.abort(c.macroApplication.pos, "Cannot find implicit for cats.Applicative[Coeval]")
    else {
      val gr = c.inferImplicitValue(weakTypeOf[cats.Group[A]], silent=true)
      if (gr == EmptyTree)
        c.abort(c.macroApplication.pos, "Cannot find implicit for cats.Group[A]")
      else
        q"""
        new ${symbolOf[CatsCoevalGroupInstance[A]]}()($ap, $gr)
        """
    }
  }

  def coevalMonoidInstance[A : WeakTypeTag]: Tree = {
    val ap = c.inferImplicitValue(weakTypeOf[cats.Applicative[Coeval]], silent=true)
    if (ap == EmptyTree)
      c.abort(c.macroApplication.pos, "Cannot find implicit for cats.Applicative[Coeval]")
    else {
      val gr = c.inferImplicitValue(weakTypeOf[cats.Monoid[A]], silent=true)
      if (gr == EmptyTree)
        c.abort(c.macroApplication.pos, "Cannot find implicit for cats.Monoid[A]")
      else
        q"""
        new ${symbolOf[CatsCoevalMonoidInstance[A]]}()($ap, $gr)
        """
    }
  }

  def coevalSemigroupInstance[A : WeakTypeTag]: Tree = {
    val ap = c.inferImplicitValue(weakTypeOf[cats.Applicative[Coeval]], silent=true)
    if (ap == EmptyTree)
      c.abort(c.macroApplication.pos, "Cannot find implicit for cats.Applicative[Coeval]")
    else {
      val gr = c.inferImplicitValue(weakTypeOf[cats.Semigroup[A]], silent=true)
      if (gr == EmptyTree)
        c.abort(c.macroApplication.pos, "Cannot find implicit for cats.Semigroup[A]")
      else
        q"""
        new ${symbolOf[CatsCoevalSemigroupInstance[A]]}()($ap, $gr)
        """
    }
  }
}
