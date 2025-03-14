package com.johnsnowlabs.nlp.annotators

import com.johnsnowlabs.nlp.{Annotation, AnnotatorApproach, DocumentAssembler}
import com.johnsnowlabs.nlp.AnnotatorType.TOKEN
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.util.{DefaultParamsReadable, Identifiable}
import org.apache.spark.sql.Dataset
import com.johnsnowlabs.nlp.AnnotatorType._
import com.johnsnowlabs.nlp.annotators.param.ExternalResourceParam
import com.johnsnowlabs.nlp.util.io.{ExternalResource, ReadAs, ResourceHelper}
import org.apache.spark.ml.param.BooleanParam

class TextMatcher(override val uid: String) extends AnnotatorApproach[TextMatcherModel] {

  def this() = this(Identifiable.randomUID("ENTITY_EXTRACTOR"))

  override val inputAnnotatorTypes = Array(DOCUMENT, TOKEN)

  override val outputAnnotatorType: AnnotatorType = CHUNK

  override val description: String = "Extracts entities from target dataset given in a text file"

  val entities = new ExternalResourceParam(this, "entities", "entities external resource.")
  val caseSensitive = new BooleanParam(this, "caseSensitive", "whether to match regardless of case. Defaults true")

  setDefault(inputCols,Array(TOKEN))
  setDefault(caseSensitive, true)

  def setEntities(value: ExternalResource): this.type =
    set(entities, value)

  def setEntities(path: String, readAs: ReadAs.Format, options: Map[String, String] = Map("format" -> "text")): this.type =
    set(entities, ExternalResource(path, readAs, options))

  def setCaseSensitive(v: Boolean): this.type =
    set(caseSensitive, v)

  def getCaseSensitive: Boolean =
    $(caseSensitive)

  /**
    * Loads entities from a provided source.
    */
  private def loadEntities(dataset: Dataset[_]): Array[Array[String]] = {
    val phrases: Array[String] = ResourceHelper.parseLines($(entities))
    val tokenizer = new Tokenizer().fit(dataset)
    val parsedEntities: Array[Array[String]] = phrases.map {
      line =>
        val annotation = Seq(Annotation(line))
        val tokens = tokenizer.annotate(annotation)
        tokens.map(_.result).toArray
    }
    parsedEntities
  }

  private def loadEntities(pipelineModel: PipelineModel): Array[Array[String]] = {
    val phrases: Seq[String] = ResourceHelper.parseLines($(entities))
    import ResourceHelper.spark.implicits._
    val textColumn = pipelineModel.stages.find {
      case _: DocumentAssembler => true
      case _ => false
    }.map(_.asInstanceOf[DocumentAssembler].getInputCol)
      .getOrElse(throw new Exception("Could not retrieve DocumentAssembler from RecursivePipeline"))
    val data = phrases.toDS.withColumnRenamed("value", textColumn)
    pipelineModel.transform(data).select(getInputCols.head).as[Array[Annotation]].map(_.map(_.result)).collect
  }

  override def train(dataset: Dataset[_], recursivePipeline: Option[PipelineModel]): TextMatcherModel = {
    if (recursivePipeline.isDefined) {
      new TextMatcherModel()
        .setEntities(loadEntities(recursivePipeline.get))
    } else {
      new TextMatcherModel()
        .setEntities(loadEntities(dataset))
        .setCaseSensitive($(caseSensitive))
    }
  }

}

object TextMatcher extends DefaultParamsReadable[TextMatcher]