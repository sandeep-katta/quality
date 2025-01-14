package com.sparkutils.qualityTests
import com.sparkutils.quality.utils.{Docs, DocsParser}
import org.junit.Test

class DocsParserTest {

  @Test
  def simpleParsingTest: Unit = {
    val test = "/** My Description @param name name desc @param othername othername desc @return return val*/ "

    val res = DocsParser.parse(test)

    assert(res.contains(
      Docs("My Description", Map("name" -> "name desc", "othername" -> "othername desc"), "return val")
    ))
  }

  @Test
  def simpleNoParamsTest: Unit = {
    val test = "/** My Description @return return val*/ "

    val res = DocsParser.parse(test)

    assert(res.contains(
      Docs("My Description", Map.empty, "return val")
    ))
  }

  @Test
  def simpleNoReturnTest: Unit = {
    val test = "/** My Description @param name name desc @param othername othername desc */ "

    val res = DocsParser.parse(test)

    assert(res.contains(
      Docs("My Description", Map("name" -> "name desc", "othername" -> "othername desc"))
    ))
  }

  @Test
  def simpleDescOnlyTest: Unit = {
    val test = "/** My Description */ "

    val res = DocsParser.parse(test)

    assert(res.contains(
      Docs("My Description")
    ))
  }

  @Test
  def descOnlyButWithExprTest: Unit = {
    val test = "/** My Description */ var -> var + 1"

    val res = DocsParser.parse(test)

    assert(res.contains(
      Docs("My Description")
    ))
  }

  @Test
  def noDocsTest: Unit = {
    val test = "var -> var + 1"

    val res = DocsParser.parse(test)

    assert(res.isEmpty)
  }

  @Test
  def simpleParamsOnlyTest: Unit = {
    val test = "/** @param name name desc @param othername othername desc */ "

    val res = DocsParser.parse(test)

    // empty desc
    assert(res.filter(_.description.isEmpty).isDefined)
  }

  @Test
  def markdownParsingTest: Unit = {
    val desc = """My Description:

* Bullet Point
* Another

"""
    val param = """
othername desc that

has

paragraphs
"""

    val test =
      s"""
/** $desc
@param name name desc
@param othername $param
@return return val
*/ """.stripMargin

    val res = DocsParser.parse(test)

    assert(res.contains(
      Docs(desc.trim(), Map("name" -> "name desc", "othername" -> param.trim()), "return val")
    ))
  }

}
