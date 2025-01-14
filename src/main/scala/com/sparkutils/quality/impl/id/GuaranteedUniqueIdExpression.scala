package com.sparkutils.quality.impl.id

import org.apache.spark.sql.Column
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenFallback
import org.apache.spark.sql.catalyst.expressions.{LeafExpression, Nondeterministic}
import org.apache.spark.sql.types.DataType

trait GuaranteedUniqueIDImports {
  /**
   * Creates a uniqueID backed by the GuaranteedUniqueID Spark Snowflake ID approach
   */
  def uniqueID(prefix: String): Column =
    new Column(GuaranteedUniqueIdIDExpression(
      GuaranteedUniqueID() // defaults are all fine, ms just relates to definition instead of action
      , prefix
    ))
}

/**
 * Delegates ID creation to some other expression which must provide an array of longs result.
 *
 * @param idBase provides the entrance MAC, header, partition and row information
 * @param prefix how to name the fields
 */
case class GuaranteedUniqueIdIDExpression(idBase: GuaranteedUniqueID, prefix: String) extends LeafExpression with CodegenFallback
  with Nondeterministic {

  @transient var idPartition: GuaranteedUniqueID = _
  @transient var idOps: GuaranteedUniqueIDOps = _

  override def dataType: DataType = idBase.dataType(prefix)

  /**
   * Reset the partition and therefore the ops, as the expression can be defined up front repeated actions can
   * re-use the same idBase, as such we reset the ms as well
   * @param partitionIndex
   */
  override protected def initializeInternal(partitionIndex: Int): Unit = {
    idPartition = idBase.copy(partition = partitionIndex, ms = System.currentTimeMillis - model.guaranteedUniqueEpoch)
    idOps = idPartition.uniqueOps
  }

  override protected def evalInternal(input: InternalRow): Any = {
    idOps.incRow
    InternalRow(idOps.base, idOps.array(0), idOps.array(1))
  }

  override def nullable: Boolean = false
}
