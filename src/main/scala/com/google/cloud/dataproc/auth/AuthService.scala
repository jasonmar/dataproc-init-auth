/*
 *  Copyright 2020 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.google.cloud.dataproc.auth

import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.coding.{Gzip, NoCoding}
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.{ActorMaterializer, Materializer}
import com.google.api.services.compute.model.Instance
import com.google.api.services.dataproc.model.Cluster
import com.google.cloud.dataproc.auth.ApiQuery.ClusterFilter

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext

object AuthService {
  def main(args: Array[String]): Unit = {
    implicit val sys: ActorSystem = ActorSystem()
    implicit val mat: ActorMaterializer = ActorMaterializer()
    implicit val ctx: ExecutionContext = sys.dispatcher

    run(AuthServiceConfig.fromEnv)
  }

  def run(config: AuthServiceConfig)
         (implicit sys: ActorSystem, mat: ActorMaterializer, ctx: ExecutionContext): Unit = {
    import config._
    val handler = handle(dir, projectId, zone, maxAgeSeconds, config.audience)
    val server = Http().bindAndHandle(handler, interface, port)
    System.out.println(s"Listening on $interface:$port")
  }

  def hasIp(ip: String, instance: Instance): Boolean = {
    import scala.collection.JavaConverters._
    instance.getNetworkInterfaces.asScala.exists(_.getNetworkIP == ip)
  }

  def hasIp(ip: RemoteAddress, instance: Instance): Boolean =
    hasIp(ip.toOption.map(_.getHostAddress).getOrElse(""), instance)

  def handle(dir: String,
             projectId: String,
             zone: String,
             maxAgeSeconds: Long,
             audience: String)
            (implicit sys: ActorSystem, mat: Materializer, ctx: ExecutionContext): Route = {
    decodeRequestWith(Gzip,NoCoding){
      encodeResponseWith(Gzip,NoCoding){
        extractClientIP{ip =>
          entity(as[String]){tokenEnc =>
            import scala.collection.JavaConverters._
            val id = EnhancedIdToken(tokenEnc)
            if (id.verify(audience) && id.maxAge(maxAgeSeconds)){
              val instance = ApiQuery.getInstance(projectId, zone, id.instanceName, id.zone)
                .find(hasIp(ip,_))
              if (instance.isDefined) {
                val clusterName = instance.get.getMetadata.getItems.asScala.find(_.getKey ==
                  "goog-dataproc-cluster-name").map(_.getValue)
                if (clusterName.isDefined){
                  val clusterFilter = ClusterFilter(clusterName = clusterName.get)
                  val cluster = ApiQuery.listClusters(zone.dropRight(2), id.projectId,
                    clusterFilter, new ArrayBuffer[Cluster](1)).headOption
                  if (cluster.isDefined){
                    val instances: Set[String] = (
                      cluster.get.getConfig.getSecondaryWorkerConfig.getInstanceNames.asScala ++
                      cluster.get.getConfig.getMasterConfig.getInstanceNames.asScala ++
                      cluster.get.getConfig.getWorkerConfig.getInstanceNames.asScala
                    ).toSet
                    if (instances.contains(id.instanceName)) {
                      getFromDirectory(dir)
                    } else reject(AuthorizationFailedRejection)
                  } else reject(AuthorizationFailedRejection)
                } else reject(AuthorizationFailedRejection)
              } else reject(AuthorizationFailedRejection)
            } else reject(AuthorizationFailedRejection)
          }
        }
      }
    }
  }
}
