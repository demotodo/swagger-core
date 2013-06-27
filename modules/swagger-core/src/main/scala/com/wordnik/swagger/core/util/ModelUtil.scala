/**
 *  Copyright 2013 Wordnik, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.wordnik.swagger.core.util

import com.wordnik.swagger.model._
import com.wordnik.swagger.converter.ModelConverters
import com.wordnik.swagger.core.{ SwaggerContext, SwaggerSpec }

import org.slf4j.LoggerFactory

import scala.collection.mutable.{ ListBuffer, HashMap, HashSet }

object ModelUtil {
  private val LOGGER = LoggerFactory.getLogger(ModelUtil.getClass)
  val ComplexTypeMatcher = "([a-zA-Z]*)\\[([a-zA-Z\\.\\-]*)\\].*".r

  def stripPackages(apis: List[ApiDescription]): List[ApiDescription] = {
    (for(api <- apis) yield {
      val operations = (for(op <- api.operations) yield {
        val parameters = (for(param <- op.parameters) yield {
          param.copy(dataType = cleanDataType(param.dataType))
        }).toList
        val messages = (for(message <- op.responseMessages) yield {
          if(message.responseModel != None) {
            message.copy(responseModel = Some(cleanDataType(message.responseModel.get)))
          }
          else message
        }).toList
        op.copy(
          responseClass = cleanDataType(op.responseClass),
          parameters = parameters,
          responseMessages = messages)
      }).toList
      api.copy(operations = operations)
    }).toList
  }

  def cleanDataType(dataType: String) = {
    val out = if(dataType.startsWith("java.lang")) {
      val trimmed = dataType.substring("java.lang".length + 1)
      if(SwaggerSpec.baseTypes.contains(trimmed.toLowerCase))
        trimmed.toLowerCase
      else
        trimmed
    }
    else {
      modelFromString(dataType) match {
        case Some(e) => e._1
        case None => dataType
      }
    }
    // put back in container
    if(out != dataType) {
      dataType match {
        case e: String if(e.toLowerCase.startsWith("list")) => "List[%s]".format(out)
        case e: String if(e.toLowerCase.startsWith("set")) => "Set[%s]".format(out)
        case e: String if(e.toLowerCase.startsWith("array")) => "Array[%s]".format(out)
        case e: String if(e.toLowerCase.startsWith("map")) => "Map[string,%s]".format(out)
        case _ => out
      }
    }
    else out
  }

  def modelsFromApis(apis: List[ApiDescription]): Option[Map[String, Model]] = {
    val modelnames = new HashSet[String]()
    for(api <- apis; op <- api.operations) {
      modelnames ++= op.responseMessages.map{_.responseModel}.flatten.toSet
      modelnames += op.responseClass
      op.parameters.foreach(param => modelnames += param.dataType)
    }
    val models = (for(name <- modelnames) yield modelAndDependencies(name)).flatten.toMap
    if(models.size > 0) Some(models)
    else None
  }

  def modelAndDependencies(name: String): Map[String, Model] = {
    val typeRef = name match {
      case ComplexTypeMatcher(containerType, basePart) => {
        if(basePart.indexOf(",") > 0) // handle maps, i.e. List[String,String]
          basePart.split("\\,").last.trim
        else basePart
      }
      case _ => name
    }
    if(shoudIncludeModel(typeRef)) {
      try{
        val cls = SwaggerContext.loadClass(typeRef)
        (for(model <- ModelConverters.readAll(cls)) yield (model.name, model)).toMap
      }
      catch {
        case e: ClassNotFoundException => Map()
      }
    }
    else Map()
  }

  def modelFromString(name: String): Option[Tuple2[String, Model]] = {
    val typeRef = name match {
      case ComplexTypeMatcher(containerType, basePart) => {
        if(basePart.indexOf(",") > 0) // handle maps, i.e. List[String,String]
          basePart.split("\\,").last.trim
        else basePart
      }
      case _ => name
    }
    if(shoudIncludeModel(typeRef)) {
      try{
        val cls = SwaggerContext.loadClass(typeRef)
        ModelConverters.read(cls) match {
          case Some(model) => Some((toName(cls), model))
          case None => None
        }
      }
      catch {
        case e: ClassNotFoundException => None
      }
    }
    else None
  }

  def toName(cls: Class[_]): String = {
    import javax.xml.bind.annotation._

    val xmlRootElement = cls.getAnnotation(classOf[XmlRootElement])
    val xmlEnum = cls.getAnnotation(classOf[XmlEnum])

    if (xmlEnum != null && xmlEnum.value != null)
      toName(xmlEnum.value())
    else if (xmlRootElement != null) {
      if ("##default".equals(xmlRootElement.name())) {
        cls.getSimpleName 
      } else {
        xmlRootElement.name() 
      }
    } else if (cls.getName.startsWith("java.lang.")) {
      val name = cls.getName.substring("java.lang.".length)
      val lc = name.toLowerCase
      if(SwaggerSpec.baseTypes.contains(lc)) lc
      else name
    }
    else if (cls.getName.indexOf(".") < 0) cls.getName
    else cls.getSimpleName 
  }

  def shoudIncludeModel(modelname: String) = {
    if(SwaggerSpec.baseTypes.contains(modelname.toLowerCase))
      false
    else if(modelname.startsWith("java.lang"))
      false
    else true
  }
}