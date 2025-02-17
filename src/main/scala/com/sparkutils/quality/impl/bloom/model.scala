package com.sparkutils.quality.impl.bloom

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import com.sparkutils.quality.impl.rng.RandomLongs
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.{CatalystTypeConverters, InternalRow}
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.util.sketch.BloomFilter

import scala.collection.JavaConverters._

trait BloomFilterTypes {
  /**
    * Used as a param to load the bloomfilter expr
   */
  type BloomFilterMap = Map[ String, (BloomLookup, Double) ]

  /**
   * Simple does it contain function to test a bloom
   */
  trait BloomLookup {
    def apply(any: Any): Boolean = mightContain(any)

    def mightContain(any: Any): Boolean
  }

  case class SparkBloomFilter( bloom: BloomFilter ) extends BloomLookup {
    override def mightContain(any: Any): Boolean =
      any match {
        case s: String => bloom.mightContainString(s)
        case b: Array[Byte] => bloom.mightContainBinary(b)
        case l: Long => bloom.mightContainLong(l)
        case _ => bloom.mightContain(any)
      }
  }

}

object BloomExpressionLookup {

  /**
   * Identifies a converter for a given expression
   * @param expr
   * @return
   */
  def bloomLookupValueConverter(expr: Expression) =
    if (expr.dataType == RandomLongs.structType)
      (input: Any) => {
        val row = input.asInstanceOf[InternalRow]
        Array(row.getLong(0), row.getLong(1))
      }
    else
      CatalystTypeConverters.createToScalaConverter(expr.dataType)

}

case class BloomRaw(bloom_id: String, bloom: Array[Byte])

case class BloomStruct(fpp: Double, blooms: Array[Array[Byte]])
case class BloomArrayRaw(bloom_id: String, bloom: BloomStruct)

object BloomStruct {
  import frameless._

  implicit val typedEnc = TypedEncoder[BloomStruct]

  implicit val enc = TypedExpressionEncoder[BloomStruct]
}

object BloomArrayRaw {
  import frameless._

  implicit val typedEnc = TypedEncoder[BloomArrayRaw]

  implicit val enc = TypedExpressionEncoder[BloomArrayRaw]
}

trait BloomSerializer[Storage, T] {
  type Serialized = (String, (com.sparkutils.quality.BloomLookup, Double))
  type SerType

  import scala.language.implicitConversions

  implicit def enc(sparkSession: SparkSession): Encoder[SerType]

  def fromType(ser: SerType): Serialized
  def toType(bloomFilter: Serialized): SerType
}

case object SparkBloomFilterSerializer extends BloomSerializer[Array[Byte], com.sparkutils.quality.SparkBloomFilter] {
  type SerType = BloomRaw

  import scala.language.implicitConversions

  implicit def enc(sparkSession: SparkSession): Encoder[SerType] = {
    import sparkSession.implicits._
    implicitly[Encoder[BloomRaw]]
  }

  override def fromType(bloomRaw: BloomRaw): Serialized = {
    val bis = new ByteArrayInputStream(bloomRaw.bloom)

    val bloom = BloomFilter.readFrom(bis)
    bis.close()
    bloomRaw.bloom_id -> (com.sparkutils.quality.SparkBloomFilter(bloom), 1.0 - bloom.expectedFpp())
  }

  override def toType(bloomFilter: Serialized): SerType = {
    val (id, (com.sparkutils.quality.SparkBloomFilter(bloom), fpp)) = bloomFilter

    val bos = new ByteArrayOutputStream()
    bloom.writeTo(bos)
    val bits = bos.toByteArray
    bos.close()
    BloomRaw(id, bits)
  }
}

object Serializing {
  import com.sparkutils.quality.BloomFilterMap

  implicit val sparkBloomFilterSerializer = SparkBloomFilterSerializer

  /**
    * Loads bloomfilters from a dataframe with string id and binary bloomfilter bloom
    * @param id the column representing the bloom filter id, must be string
    * @param bloom the column representing the bloomfilter itself, must be binary
    * @return a map of bloomfilter id to bloomfilter and expected fpp
    */
  def fromDF[SerializedType, T: ({ type B[A] = BloomSerializer[SerializedType, A] })#B](df: DataFrame, id: Column, bloom: Column): BloomFilterMap = {
    val ser = implicitly[BloomSerializer[SerializedType, T]]

    implicit val enc = ser.enc(df.sparkSession)

    df.select(id.as("BLOOM_ID"), bloom.as("BLOOM")).as[ser.SerType].toLocalIterator().asScala.map{
      bloomRaw => ser.fromType(bloomRaw)
    }.toMap
  }

  /**
    * Must have an active sparksession before calling
    * @param bloomFilterMap
    * @return a DataFrame representing storage of ID -> some bloom type
    */
  def toDF[SerializedType, T: ({ type B[A] = BloomSerializer[SerializedType, A] })#B](bloomFilterMap: BloomFilterMap):
      Dataset[BloomSerializer[SerializedType, T]#SerType] = {
    val ser = implicitly[BloomSerializer[SerializedType, T]]

    val blooms = bloomFilterMap.map { pair =>
      ser.toType(pair)
    }.toSeq

    val sess = SparkSession.getDefaultSession.get
    implicit val enc = ser.enc(sess)

    sess.createDataset(blooms).asInstanceOf[Dataset[BloomSerializer[SerializedType, T]#SerType]]
  }
}
