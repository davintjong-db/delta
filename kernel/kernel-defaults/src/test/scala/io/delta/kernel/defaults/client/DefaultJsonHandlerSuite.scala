/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.delta.kernel.defaults.client

import java.math.{BigDecimal => JBigDecimal}
import java.util.Optional

import scala.collection.JavaConverters._

import org.apache.hadoop.conf.Configuration
import org.scalatest.funsuite.AnyFunSuite

import io.delta.kernel.types._
import io.delta.kernel.internal.util.InternalUtils.singletonStringColumnVector

import io.delta.kernel.defaults.utils.{TestRow, TestUtils, VectorTestUtils}

// NOTE: currently tests are split across scala and java; additional tests are in
// TestDefaultJsonHandler.java
class DefaultJsonHandlerSuite extends AnyFunSuite with TestUtils with VectorTestUtils {

  val jsonHandler = new DefaultJsonHandler(new Configuration());

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // Tests for parseJson for statistics eligible types (additional in TestDefaultJsonHandler.java)
  /////////////////////////////////////////////////////////////////////////////////////////////////

  def testJsonParserWithSchema(
    jsonString: String,
    schema: StructType,
    expectedRow: TestRow): Unit = {
    val batchRows = jsonHandler.parseJson(
      singletonStringColumnVector(jsonString),
      schema,
      Optional.empty()
    ).getRows.toSeq
    checkAnswer(batchRows, Seq(expectedRow))
  }

  def testJsonParserForSingleType(
    jsonString: String,
    dataType: DataType,
    numColumns: Int,
    expectedRow: TestRow): Unit = {
    val schema = new StructType(
      (1 to numColumns).map(i => new StructField(s"col$i", dataType, true)).asJava)
    testJsonParserWithSchema(jsonString, schema, expectedRow)
  }

  def testOutOfRangeValue(stringValue: String, dataType: DataType): Unit = {
    val e = intercept[RuntimeException]{
      testJsonParserForSingleType(
        jsonString = s"""{"col1":$stringValue}""",
        dataType = dataType,
        numColumns = 1,
        expectedRow = TestRow()
      )
    }
    assert(e.getMessage.contains(s"Couldn't decode $stringValue"))
  }

  test("parse byte type") {
    testJsonParserForSingleType(
      jsonString = """{"col1":0,"col2":-127,"col3":127, "col4":null}""",
      dataType = ByteType.BYTE,
      4,
      TestRow(0.toByte, -127.toByte, 127.toByte, null)
    )
    testOutOfRangeValue("128", ByteType.BYTE)
    testOutOfRangeValue("-129", ByteType.BYTE)
    testOutOfRangeValue("2147483648", ByteType.BYTE)
  }

  test("parse short type") {
    testJsonParserForSingleType(
      jsonString = """{"col1":-32767,"col2":8,"col3":32767, "col4":null}""",
      dataType = ShortType.SHORT,
      4,
      TestRow(-32767.toShort, 8.toShort, 32767.toShort, null)
    )
    testOutOfRangeValue("32768", ShortType.SHORT)
    testOutOfRangeValue("-32769", ShortType.SHORT)
    testOutOfRangeValue("2147483648", ShortType.SHORT)
  }

  test("parse integer type") {
    testJsonParserForSingleType(
      jsonString = """{"col1":-2147483648,"col2":8,"col3":2147483647, "col4":null}""",
      dataType = IntegerType.INTEGER,
      4,
      TestRow(-2147483648, 8, 2147483647, null)
    )
    testOutOfRangeValue("2147483648", IntegerType.INTEGER)
    testOutOfRangeValue("-2147483649", IntegerType.INTEGER)
  }

  test("parse long type") {
    testJsonParserForSingleType(
      jsonString =
      """{"col1":-9223372036854775808,"col2":8,"col3":9223372036854775807, "col4":null}""",
      dataType = LongType.LONG,
      4,
      TestRow(-9223372036854775808L, 8L, 9223372036854775807L, null)
    )
    testOutOfRangeValue("9223372036854775808", LongType.LONG)
    testOutOfRangeValue("-9223372036854775809", LongType.LONG)
  }

  test("parse float type") {
    testJsonParserForSingleType(
      jsonString =
        """
          |{"col1":-9223.33,"col2":0.4,"col3":1.2E8,
          |"col4":1.23E-7,"col5":0.004444444, "col6":null}""".stripMargin,
      dataType = FloatType.FLOAT,
      6,
      TestRow(-9223.33F, 0.4F, 120000000.0F, 0.000000123F, 0.004444444F, null)
    )
    testOutOfRangeValue("3.4028235E+39", FloatType.FLOAT)
  }

  test("parse double type") {
    testJsonParserForSingleType(
      jsonString =
        """
          |{"col1":-9.2233333333E8,"col2":0.4,"col3":1.2E8,
          |"col4":1.234444444E-7,"col5":0.0444444444, "col6":null}""".stripMargin,
      dataType = DoubleType.DOUBLE,
      6,
      TestRow(-922333333.33D, 0.4D, 120000000.0D, 0.0000001234444444D, 0.0444444444D, null)
    )
    // For some reason out-of-range doubles are parsed initially as Double.INFINITY instead of
    // a BigDecimal
    val e = intercept[RuntimeException]{
      testJsonParserForSingleType(
        jsonString = s"""{"col1":1.7976931348623157E+309}""",
        dataType = DoubleType.DOUBLE,
        numColumns = 1,
        expectedRow = TestRow()
      )
    }
    assert(e.getMessage.contains(s"Couldn't decode"))
  }

  test("parse string type") {
    testJsonParserForSingleType(
      jsonString = """{"col1": "foo", "col2": "", "col3": null}""",
      dataType = StringType.STRING,
      3,
      TestRow("foo", "", null)
    )
  }

  test("parse decimal type") {
    testJsonParserWithSchema(
      jsonString = """
      |{
      |  "col1":0,
      |  "col2":0.01234567891234567891234567891234567890,
      |  "col3":123456789123456789123456789123456789,
      |  "col4":1234567891234567891234567891.2345678900,
      |  "col5":1.23,
      |  "col6":null
      |}
      |""".stripMargin,
      schema = new StructType()
        .add("col1", DecimalType.USER_DEFAULT)
        .add("col2", new DecimalType(38, 38))
        .add("col3", new DecimalType(38, 0))
        .add("col4", new DecimalType(38, 10))
        .add("col5", new DecimalType(5, 2))
        .add("col6", new DecimalType(5, 2)),
      TestRow(
        new JBigDecimal(0),
        new JBigDecimal("0.01234567891234567891234567891234567890"),
        new JBigDecimal("123456789123456789123456789123456789"),
        new JBigDecimal("1234567891234567891234567891.2345678900"),
        new JBigDecimal("1.23"),
        null
      )
    )
  }

  test("parse date type") {
    testJsonParserForSingleType(
      jsonString = """{"col1":"2020-12-31", "col2":"1965-01-31", "col3": null}""",
      dataType = DateType.DATE,
      3,
      TestRow(18627, -1796, null)
    )
  }

  test("parse timestamp type") {
    testJsonParserForSingleType(
      jsonString =
        """
          |{
          | "col1":"2050-01-01T00:00:00.000-08:00",
          | "col2":"1970-01-01T06:30:23.523Z",
          | "col3":"1960-01-01T10:00:00.000Z",
          | "col4":null
          | }
          | """.stripMargin,
      dataType = TimestampType.TIMESTAMP,
      numColumns = 4,
      TestRow(2524636800000000L, 23423523000L, -315583200000000L, null)
    )
  }

  test("parse null input") {
    val schema = new StructType()
      .add("nested_struct", new StructType().add("foo", IntegerType.INTEGER))

    val batch = jsonHandler.parseJson(
      singletonStringColumnVector(null),
      schema,
      Optional.empty()
    )
    assert(batch.getColumnVector(0).getChild(0).isNullAt(0))
  }

  test("parse NaN and INF for float and double") {
    def testSpecifiedString(json: String, output: TestRow): Unit = {
      testJsonParserWithSchema(
        jsonString = json,
        schema = new StructType()
          .add("col1", FloatType.FLOAT)
          .add("col2", DoubleType.DOUBLE),
        output
      )
    }
    testSpecifiedString("""{"col1":"NaN","col2":"NaN"}""", TestRow(Float.NaN, Double.NaN))
    testSpecifiedString("""{"col1":"+INF","col2":"+INF"}""",
      TestRow(Float.PositiveInfinity, Double.PositiveInfinity))
    testSpecifiedString("""{"col1":"+Infinity","col2":"+Infinity"}""",
      TestRow(Float.PositiveInfinity, Double.PositiveInfinity))
    testSpecifiedString("""{"col1":"Infinity","col2":"Infinity"}""",
      TestRow(Float.PositiveInfinity, Double.PositiveInfinity))
    testSpecifiedString("""{"col1":"-INF","col2":"-INF"}""",
      TestRow(Float.NegativeInfinity, Double.NegativeInfinity))
    testSpecifiedString("""{"col1":"-Infinity","col2":"-Infinity"}""",
      TestRow(Float.NegativeInfinity, Double.NegativeInfinity))
  }

  test("don't parse unselected rows") {
    val selectionVector = booleanVector(Seq(true, false, false))
    val jsonVector = stringVector(
      Seq("""{"col1":1}""", """{"col1":"foo"}""", """{"col1":"foo"}"""))
    val batchRows = jsonHandler.parseJson(
      jsonVector,
      new StructType()
        .add("col1", IntegerType.INTEGER),
      Optional.of(selectionVector)
    ).getRows.toSeq
    assert(!batchRows(0).isNullAt(0) && batchRows(0).getInt(0) == 1)
    assert(batchRows(1).isNullAt(0) && batchRows(2).isNullAt(0))
  }

  //////////////////////////////////////////////////////////////////////////////////
  // END-TO-END TESTS FOR deserializeStructType (more tests in DataTypeParserSuite)
  //////////////////////////////////////////////////////////////////////////////////

  private def sampleMetadata: FieldMetadata = FieldMetadata.builder()
    .putNull("null")
    .putLong("long", 1000L)
    .putDouble("double", 2.222)
    .putBoolean("boolean", true)
    .putString("string", "value")
    .build()

  test("deserializeStructType: primitive type round trip") {
    val fields = BasePrimitiveType.getAllPrimitiveTypes().asScala.flatMap { dataType =>
      Seq(
        new StructField("col1" + dataType, dataType, true),
        new StructField("col1" + dataType, dataType, false),
        new StructField("col1" + dataType, dataType, false, sampleMetadata)
      )
    } ++ Seq(
      new StructField("col1decimal", new DecimalType(30, 10), true),
      new StructField("col2decimal", new DecimalType(38, 22), true),
      new StructField("col3decimal", new DecimalType(5, 2), true)
    )

    val expSchema = new StructType(fields.asJava);
    val serializedSchema = expSchema.toJson
    val actSchema = jsonHandler.deserializeStructType(serializedSchema)
    assert(expSchema == actSchema)
  }

  test("deserializeStructType: complex type round trip") {
    val arrayType = new ArrayType(IntegerType.INTEGER, true)
    val arrayArrayType = new ArrayType(arrayType, false)
    val mapType = new MapType(FloatType.FLOAT, BinaryType.BINARY, false)
    val mapMapType = new MapType(mapType, BinaryType.BINARY, true)
    val structType = new StructType().add("simple", DateType.DATE)
    val structAllType = new StructType()
      .add("prim", BooleanType.BOOLEAN)
      .add("arr", arrayType)
      .add("map", mapType)
      .add("struct", structType)

    val expSchema = new StructType()
      .add("col1", arrayType, true)
      .add("col2", arrayArrayType, false)
      .add("col3", mapType, false)
      .add("col4", mapMapType, false)
      .add("col5", structType, false)
      .add("col6", structAllType, false)

    val serializedSchema = expSchema.toJson
    val actSchema = jsonHandler.deserializeStructType(serializedSchema)
    assert(expSchema == actSchema)
  }

  test("deserializeStructType: not a StructType") {
    val e = intercept[IllegalArgumentException] {
      jsonHandler.deserializeStructType(new ArrayType(StringType.STRING, true).toJson())
    }
    assert(e.getMessage.contains("Could not parse the following JSON as a valid StructType"))
  }

  test("deserializeStructType: invalid JSON") {
    val e = intercept[RuntimeException] {
      jsonHandler.deserializeStructType(
        """
          |{
          |  "type" : "struct,
          |  "fields" : []
          |}
          |""".stripMargin
      )
    }
    assert(e.getMessage.contains("Could not parse JSON"))
  }

  // TODO we use toJson to serialize our physical and logical schemas in ScanStateRow, we should
  //  test DataType.toJson
}
