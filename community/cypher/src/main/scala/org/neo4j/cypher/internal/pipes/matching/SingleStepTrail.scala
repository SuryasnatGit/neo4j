/**
 * Copyright (c) 2002-2013 "Neo Technology,"
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
package org.neo4j.cypher.internal.pipes.matching

import org.neo4j.graphdb.{PropertyContainer, Direction}
import org.neo4j.cypher.internal.commands.{True, Pattern, Predicate}
import org.neo4j.graphdb.DynamicRelationshipType._
import org.neo4j.cypher.internal.symbols.{RelationshipType, NodeType, SymbolTable}

final case class SingleStepTrail(next: Trail,
                                 dir: Direction,
                                 relName: String,
                                 typ: Seq[String],
                                 start: String,
                                 relPred: Predicate,
                                 nodePred: Predicate,
                                 pattern: Pattern,
                                 originalPredicates: Seq[Predicate]) extends Trail {
  def end = next.end

  def pathDescription = next.pathDescription ++ Seq(relName, end)

  def toSteps(id: Int) = {
    val steps = next.toSteps(id + 1)

    Some(SingleStep(id, typ, dir, steps, relPred, nodePred))
  }

  def size = next.size + 1

  protected[matching] def decompose(p: Seq[PropertyContainer], m: Map[String, Any]): Iterator[(Seq[PropertyContainer], Map[String, Any])] =
    if (p.size < 2) {
      Iterator()
    } else {
      val thisRel = p.tail.head
      val thisNode = p.head

      val a = m.get(relName)
      val b = m.get(start)

      if ((a.nonEmpty && a.get != thisRel)||(b.nonEmpty && b.get != thisNode)) {
        Iterator()
      } else {
        val newMap = m + (relName -> thisRel) + (start -> thisNode)
        next.decompose(p.tail.tail, newMap)
      }
    }

  def symbols(table: SymbolTable): SymbolTable =
    next.symbols(table).add(start, NodeType()).add(relName, RelationshipType())

  def contains(target: String): Boolean = next.contains(target) || target == end

  def predicates = originalPredicates ++ next.predicates

  def patterns = next.patterns :+ pattern

  override def toString = {
    val left = if (Direction.INCOMING == dir) "<" else ""
    val right = if (Direction.OUTGOING == dir) ">" else ""
    val t = typ match {
      case List() => ""
      case x      => typ.mkString(":", "|", "")
    }

    "(%s)%s-[%s%s]-%s%s".format(start, left, relName, t, right, next.toString)
  }


  def nodeNames = Seq(start) ++ next.nodeNames

  def add(f: (String) => Trail) = copy(next = next.add(f))

  def filter(f: (Trail) => Boolean):Iterable[Trail] = Some(this).filter(f) ++ next.filter(f)
}