/*
 * Tencent is pleased to support the open source community by making Angel available.
 *
 * Copyright (C) 2017-2018 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in 
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/Apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */


package com.tencent.angel.ml.core.graphsubmit

import com.tencent.angel.client.AngelClient
import com.tencent.angel.ml.core.conf.SharedConf
import com.tencent.angel.ml.core.network.layers.edge.inputlayer.{Embedding, SparseInputLayer}
import com.tencent.angel.ml.feature.LabeledData
import com.tencent.angel.ml.math2.matrix.{BlasDoubleMatrix, BlasFloatMatrix}
import com.tencent.angel.ml.math2.vector.Vector
import com.tencent.angel.ml.model.MLModel
import com.tencent.angel.ml.core.network.layers.{AngelGraph, PlaceHolder}
import com.tencent.angel.ml.core.optimizer.loss._
import com.tencent.angel.ml.core.utils.paramsutils.JsonUtils
import com.tencent.angel.ml.predict.PredictResult
import com.tencent.angel.worker.storage.{DataBlock, MemoryDataBlock}
import com.tencent.angel.worker.task.TaskContext
import org.apache.hadoop.conf.Configuration
import org.json4s.JValue


class GraphModel(conf: Configuration, _ctx: TaskContext = null)
  extends MLModel(conf, _ctx) {
  val sharedConf: SharedConf = SharedConf.get()
  implicit lazy val graph: AngelGraph = new AngelGraph(new PlaceHolder(sharedConf), sharedConf)

  val batchSize: Int = SharedConf.batchSize
  val blockSize: Int = SharedConf.blockSize
  val dataFormat: String = SharedConf.inputDataFormat
  var jsonAst: JValue = _

  def ensureJsonAst(): Unit = {
    if (sharedConf.getJson == null) {
      JsonUtils.init()
    }
    jsonAst = sharedConf.getJson
  }

  def lossFunc: LossFunc = {
    ensureJsonAst()
    JsonUtils.getLossFunc(jsonAst).build()
  }

  def buildNetwork(): Unit = {
    ensureJsonAst()
    JsonUtils.fillGraph(jsonAst)
  }

  /**
    * Predict use the PSModels and predict data
    *
    * @param storage predict data
    * @return predict result
    */
  override def predict(storage: DataBlock[LabeledData]): DataBlock[PredictResult] = {
    val resData = new MemoryDataBlock[PredictResult](storage.size())
    var pullFlag = false

    val batchData = new Array[LabeledData](batchSize)
    (0 until storage.size()).foreach { i =>
      if (i != 0 && i % batchSize == 0) {
        graph.feedData(batchData)
        if (!pullFlag) {
          graph.pullParams()
          pullFlag = true
        } else {
          graph.getTrainable.foreach {
            case layer: Embedding =>
              layer.pullParams()
            case layer: SparseInputLayer =>
              layer.pullParams()
            case _ =>
          }
        }
        graph.predict() match {
          case mat: BlasDoubleMatrix =>
            (0 until mat.getNumRows).foreach { i =>
              resData.put(GraphPredictResult(i, mat.get(i, 0), mat.get(i, 1), mat.get(i, 2)))
            }
          case mat: BlasFloatMatrix =>
            (0 until mat.getNumRows).foreach { i =>
              resData.put(GraphPredictResult(i, mat.get(i, 0), mat.get(i, 1), mat.get(i, 2)))
            }
        }
      }

      batchData(i % batchSize) = storage.loopingRead()
    }

    val left = storage.size() % batchSize
    if (left != 0) {
      val leftData = new Array[LabeledData](left)
      Array.copy(batchData, 0, leftData, 0, left)
      graph.feedData(leftData)
      graph.getTrainable.foreach {
        case layer: Embedding =>
          layer.pullParams()
        case _ =>
      }
      graph.predict() match {
        case mat: BlasDoubleMatrix =>
          (0 until mat.getNumRows).foreach { i =>
            resData.put(GraphPredictResult(i, mat.get(i, 0), mat.get(i, 1), mat.get(i, 2)))
          }
        case mat: BlasFloatMatrix =>
          (0 until mat.getNumRows).foreach { i =>
            resData.put(GraphPredictResult(i, mat.get(i, 0), mat.get(i, 1), mat.get(i, 2)))
          }
      }
    }
    resData
  }

  def init(taskflag: Int, idxsVector: Vector): Unit = {
    graph.init(taskflag, idxsVector)
  }

  def loadModel(client: AngelClient): Unit = {
    graph.loadModel(client)
  }

  def saveModel(client: AngelClient): Unit = {
    graph.saveModel(client)
  }
}


object GraphModel {
  def apply(className: String, conf: Configuration): GraphModel = {
    val cls = Class.forName(className)
    val cstr = cls.getConstructor(classOf[Configuration], classOf[TaskContext])
    cstr.newInstance(conf, null).asInstanceOf[GraphModel]
  }

  def apply(className: String, conf: Configuration, ctx: TaskContext = null): GraphModel = {
    val cls = Class.forName(className)
    val cstr = cls.getConstructor(classOf[Configuration], classOf[TaskContext])
    cstr.newInstance(conf, ctx).asInstanceOf[GraphModel]
  }
}
