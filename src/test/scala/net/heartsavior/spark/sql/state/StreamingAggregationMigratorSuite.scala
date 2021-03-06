/*
 * Copyright 2019 Jungtaek Lim "<kabhwan@gmail.com>"
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

package net.heartsavior.spark.sql.state

import java.io.File

import net.heartsavior.spark.sql.state.migration.StreamingAggregationMigrator
import net.heartsavior.spark.sql.util.SchemaUtil
import org.apache.hadoop.fs.Path
import org.scalatest.{Assertions, BeforeAndAfterAll}

import org.apache.spark.sql.Row
import org.apache.spark.sql.execution.streaming.MemoryStream
import org.apache.spark.sql.execution.streaming.state.StateStore
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.StructType

class StreamingAggregationMigratorSuite
  extends StateStoreTest
    with BeforeAndAfterAll
    with Assertions {

  override def afterAll(): Unit = {
    super.afterAll()
    StateStore.stop()
  }

  test("migrate streaming aggregation state format version 1 to 2") {
    withTempCheckpoints { case (oldCpDir, newCpDir) =>
      val oldCpPath = new Path(oldCpDir.getAbsolutePath)
      val newCpPath = new Path(newCpDir.getAbsolutePath)

      // run streaming aggregation query to state format version 1
      withSQLConf(SQLConf.STREAMING_AGGREGATION_STATE_FORMAT_VERSION.key -> "1") {
        runCompositeKeyStreamingAggregationQuery(oldCpDir.getAbsolutePath)
      }

      val stateSchema = getSchemaForCompositeKeyStreamingAggregationQuery(1)

      val migrator = new StreamingAggregationMigrator(spark)
      migrator.convertVersion1To2(
        oldCpPath,
        newCpPath,
        SchemaUtil.getSchemaAsDataType(stateSchema, "key").asInstanceOf[StructType],
        SchemaUtil.getSchemaAsDataType(stateSchema, "value").asInstanceOf[StructType])

      val newStateInfo = new StateInformationInCheckpoint(spark).gatherInformation(newCpPath)
      assert(newStateInfo.lastCommittedBatchId.isDefined,
        "The checkpoint directory should contain committed batch!")

      // check whether it's running well with new checkpoint

      // read state with new expected state schema (state format version 2)
      val newStateSchema = getSchemaForCompositeKeyStreamingAggregationQuery(2)

      // we assume operator id = 0, store_name = default
      val stateReadDf = spark.read
        .format("state")
        .schema(newStateSchema)
        .option(StateStoreDataSourceProvider.PARAM_CHECKPOINT_LOCATION,
          new File(newCpDir, "state").getAbsolutePath)
        .option(StateStoreDataSourceProvider.PARAM_VERSION,
          newStateInfo.lastCommittedBatchId.get + 1)
        .option(StateStoreDataSourceProvider.PARAM_OPERATOR_ID, 0)
        .load()

      checkAnswer(
        stateReadDf
          .selectExpr("key.groupKey AS key_groupKey", "key.fruit AS key_fruit",
            "value.cnt AS value_cnt", "value.sum AS value_sum", "value.max AS value_max",
            "value.min AS value_min"),
        Seq(
          Row(0, "Apple", 2, 6, 6, 0),
          Row(1, "Banana", 3, 9, 7, 1),
          Row(0, "Strawberry", 3, 12, 8, 2),
          Row(1, "Apple", 3, 15, 9, 3),
          Row(0, "Banana", 2, 14, 10, 4),
          Row(1, "Strawberry", 1, 5, 5, 5)
        )
      )

      // rerun streaming query from migrated checkpoint
      verifyContinueRunCompositeKeyStreamingAggregationQuery(newCpPath.toString)
    }
  }


  private def verifyContinueRunCompositeKeyStreamingAggregationQuery(
      checkpointRoot: String): Unit = {
    import testImplicits._

    val inputData = MemoryStream[Int]
    val aggregated = getCompositeKeyStreamingAggregationQuery(inputData)

    // batch 0
    inputData.addData(0 to 5)
    // batch 1
    inputData.addData(6 to 10)
    // batch 2
    inputData.addData(3, 2, 1)

    testStream(aggregated, OutputMode.Update)(
      StartStream(checkpointLocation = checkpointRoot),
      // batch 3
      AddData(inputData, 3, 2, 1),
      CheckLastBatch(
        (1, "Banana", 4, 10, 7, 1), // 1, 7, 1, 1
        (0, "Strawberry", 4, 14, 8, 2), // 2, 8, 2, 2
        (1, "Apple", 4, 18, 9, 3) // 3, 9, 3, 3
      )
    )
  }
}
