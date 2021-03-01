/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.engine.spark.operation

import java.util.regex.Pattern

import org.apache.commons.lang3.StringUtils
import org.apache.hive.service.rpc.thrift.{TRowSet, TTableSchema}
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.types.StructType

import org.apache.kyuubi.KyuubiSQLException
import org.apache.kyuubi.engine.spark.FetchIterator
import org.apache.kyuubi.operation.{AbstractOperation, OperationState}
import org.apache.kyuubi.operation.FetchOrientation._
import org.apache.kyuubi.operation.OperationState.OperationState
import org.apache.kyuubi.operation.OperationType.OperationType
import org.apache.kyuubi.operation.log.OperationLog
import org.apache.kyuubi.schema.{RowSet, SchemaHelper}
import org.apache.kyuubi.session.Session

abstract class SparkOperation(spark: SparkSession, opType: OperationType, session: Session)
  extends AbstractOperation(opType, session) {

  protected var iter: FetchIterator[Row] = _

  protected def resultSchema: StructType

  protected def cleanup(targetState: OperationState): Unit = synchronized {
    if (!isTerminalState(state)) {
      setState(targetState)
      spark.sparkContext.cancelJobGroup(statementId)
    }
  }

  /**
   * convert SQL 'like' pattern to a Java regular expression.
   *
   * Underscores (_) are converted to '.' and percent signs (%) are converted to '.*'.
   *
   * @param input the SQL pattern to convert
   * @return the equivalent Java regular expression of the pattern
   */
  def toJavaRegex(input: String): String = {
    val res = if (StringUtils.isEmpty(input) || input == "*") {
      "%"
    } else {
      input
    }
    val in = res.toIterator
    val out = new StringBuilder()

    while (in.hasNext) {
      in.next match {
        case c if c == '\\' && in.hasNext => Pattern.quote(Character.toString(in.next()))
        case c if c == '\\' && !in.hasNext => Pattern.quote(Character.toString(c))
        case '_' => out ++= "."
        case '%' => out ++= ".*"
        case c => out ++= Character.toString(c)
      }
    }
    out.result()
  }

  protected def onError(cancel: Boolean = false): PartialFunction[Throwable, Unit] = {
    // We should use Throwable instead of Exception since `java.lang.NoClassDefFoundError`
    // could be thrown.
    case e: Throwable =>
      if (cancel) spark.sparkContext.cancelJobGroup(statementId)
      state.synchronized {
        val errMsg = KyuubiSQLException.stringifyException(e)
        if (isTerminalState(state)) {
          warn(s"Ignore exception in terminal state with $statementId: $errMsg")
        } else {
          setState(OperationState.ERROR)
          error(s"Error operating $opType: $errMsg", e)
          val ke = KyuubiSQLException(s"Error operating $opType: $errMsg", e)
          setOperationException(ke)
          throw ke
        }
      }
  }

  override protected def beforeRun(): Unit = {
    Thread.currentThread().setContextClassLoader(spark.sharedState.jarClassLoader)
    setHasResultSet(true)
    setState(OperationState.RUNNING)
  }

  override protected def afterRun(): Unit = {
    state.synchronized {
      if (!isTerminalState(state)) {
        setState(OperationState.FINISHED)
      }
    }
    OperationLog.removeCurrentOperationLog()
  }

  override def cancel(): Unit = {
    cleanup(OperationState.CANCELED)
  }

  override def close(): Unit = {
    cleanup(OperationState.CLOSED)
    getOperationLog.foreach(_.close())
  }

  override def getResultSetSchema: TTableSchema = SchemaHelper.toTTableSchema(resultSchema)

  override def getNextRowSet(order: FetchOrientation, rowSetSize: Int): TRowSet = {
    validateDefaultFetchOrientation(order)
    assertState(OperationState.FINISHED)
    setHasResultSet(true)
    order match {
      case FETCH_NEXT => iter.fetchNext()
      case FETCH_PRIOR => iter.fetchPrior(rowSetSize);
      case FETCH_FIRST => iter.fetchAbsolute(0);
    }
    val taken = iter.take(rowSetSize)
    val resultRowSet = RowSet.toTRowSet(taken.toList, resultSchema, getProtocolVersion)
    resultRowSet.setStartRowOffset(iter.getPosition)
    resultRowSet
  }

  override def shouldRunAsync: Boolean = false
}
