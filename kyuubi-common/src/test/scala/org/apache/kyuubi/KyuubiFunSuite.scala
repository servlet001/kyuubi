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

package org.apache.kyuubi

// scalastyle:off
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FunSuite, Outcome}
import org.scalatest.concurrent.Eventually

trait KyuubiFunSuite extends FunSuite
  with BeforeAndAfterAll
  with BeforeAndAfterEach
  with Eventually
  with ThreadAudit
  with Logging {
  // scalastyle:on
  override def beforeAll(): Unit = {
    doThreadPostAudit()
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    doThreadPostAudit()
  }

  final override def withFixture(test: NoArgTest): Outcome = {
    val testName = test.text
    val suiteName = this.getClass.getName
    val shortSuiteName = suiteName.replaceAll("org\\.apache\\.kyuubi", "o\\.a\\.k")
    try {
      info(s"\n\n===== TEST OUTPUT FOR $shortSuiteName: '$testName' =====\n")
      test()
    } finally {
      info(s"\n\n===== FINISHED $shortSuiteName: '$testName' =====\n")
    }
  }
}
