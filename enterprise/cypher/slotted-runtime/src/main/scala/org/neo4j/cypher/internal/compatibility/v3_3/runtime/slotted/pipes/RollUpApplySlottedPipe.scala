/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compatibility.v3_3.runtime.slotted.pipes

import org.neo4j.cypher.internal.compatibility.v3_3.runtime._
import org.neo4j.cypher.internal.compatibility.v3_3.runtime.pipes.{Pipe, PipeWithSource, QueryState}
import org.neo4j.cypher.internal.compatibility.v3_3.runtime.slotted.PrimitiveExecutionContext
import org.neo4j.cypher.internal.v3_3.logical.plans.LogicalPlanId
import org.neo4j.values.AnyValue
import org.neo4j.values.storable.Values
import org.neo4j.values.storable.Values.NO_VALUE
import org.neo4j.values.virtual.VirtualValues

case class RollUpApplySlottedPipe(lhs: Pipe, rhs: Pipe, collectionName: String, identifierToCollect: String, nullableIdentifiers: Set[String], pipelineInformation: PipelineInformation)
                                 (val id: LogicalPlanId = LogicalPlanId.DEFAULT)
  extends PipeWithSource(lhs) {

  private val collectionSlot = pipelineInformation.get(collectionName).get
  private val identifierToCollectSlot = pipelineInformation.get(identifierToCollect).get

  private val getValueToCollectFunction =
    identifierToCollectSlot match {
      case LongSlot(offset, _, _, _) => (ctx: ExecutionContext) => Values.longValue(ctx.getLongAt(offset))
      case RefSlot(offset, _, _, _) => (ctx: ExecutionContext) => ctx.getRefAt(offset)
    }

  private val hasNullValuePredicates: Seq[(ExecutionContext) => Boolean] =
    nullableIdentifiers.toSeq.map { elem =>
      val elemSlot = pipelineInformation.get(elem)
      elemSlot match {
        case Some(LongSlot(offset, true, _, _)) => { (ctx: ExecutionContext) => ctx.getLongAt(offset) == -1 }
        case Some(RefSlot(offset, true, _, _)) => { (ctx: ExecutionContext) => ctx.getRefAt(offset) == NO_VALUE }
        case _ => { (ctx: ExecutionContext) => false }
      }
    }

  private def hasNullValue(ctx: ExecutionContext): Boolean =
    hasNullValuePredicates.exists(p => p(ctx))

  private val setCollectionInRow: (ExecutionContext, AnyValue) => Unit = {
    collectionSlot match {
      case RefSlot(offset, _, _, _) =>
        (ctx: ExecutionContext, value: AnyValue) => ctx.setRefAt(offset, value)
      case _ => throw new InternalError("Expected collection to be allocated to a ref slot")
    }
  }

  override protected def internalCreateResults(input: Iterator[ExecutionContext], state: QueryState) = {
    input.map {
      ctx =>
        val outputRow = PrimitiveExecutionContext(pipelineInformation)
        ctx.copyTo(outputRow)

        if (hasNullValue(ctx)) {
          setCollectionInRow(outputRow, NO_VALUE)
        }
        else {
          val innerState = state.withInitialContext(outputRow)
          val innerResults: Iterator[ExecutionContext] = rhs.createResults(innerState)
          val collection = VirtualValues.list(innerResults.map(getValueToCollectFunction).toArray: _*)
          setCollectionInRow(outputRow, collection)
        }
        outputRow
    }
  }
}
