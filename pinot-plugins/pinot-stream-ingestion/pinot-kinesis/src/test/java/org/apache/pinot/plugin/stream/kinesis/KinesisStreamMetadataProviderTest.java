/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.plugin.stream.kinesis;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pinot.spi.stream.PartitionGroupConsumer;
import org.apache.pinot.spi.stream.PartitionGroupConsumptionStatus;
import org.apache.pinot.spi.stream.PartitionGroupMetadata;
import org.apache.pinot.spi.stream.StreamConfig;
import org.apache.pinot.spi.stream.StreamConfigProperties;
import org.apache.pinot.spi.stream.StreamConsumerFactory;
import org.apache.pinot.spi.stream.StreamPartitionMsgOffset;
import org.easymock.Capture;
import org.easymock.CaptureType;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.kinesis.model.SequenceNumberRange;
import software.amazon.awssdk.services.kinesis.model.Shard;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;

import static org.easymock.EasyMock.*;


public class KinesisStreamMetadataProviderTest {
  private static final String STREAM_NAME = "kinesis-test";
  private static final String AWS_REGION = "us-west-2";
  private static final String SHARD_ID_0 = "0";
  private static final String SHARD_ID_1 = "1";
  private static final String CLIENT_ID = "dummy";
  private static final int TIMEOUT = 1000;

  private KinesisConnectionHandler _kinesisConnectionHandler;
  private KinesisStreamMetadataProvider _kinesisStreamMetadataProvider;
  private StreamConsumerFactory _streamConsumerFactory;
  private PartitionGroupConsumer _partitionGroupConsumer;

  private StreamConfig getStreamConfig() {
    Map<String, String> props = new HashMap<>();
    props.put(KinesisConfig.REGION, AWS_REGION);
    props.put(KinesisConfig.MAX_RECORDS_TO_FETCH, "10");
    props.put(KinesisConfig.SHARD_ITERATOR_TYPE, ShardIteratorType.AT_SEQUENCE_NUMBER.toString());
    props.put(StreamConfigProperties.STREAM_TYPE, "kinesis");
    props.put("stream.kinesis.consumer.type", "lowLevel");
    props.put("stream.kinesis.topic.name", STREAM_NAME);
    props.put("stream.kinesis.decoder.class.name", "ABCD");
    props.put("stream.kinesis.consumer.factory.class.name",
        "org.apache.pinot.plugin.stream.kinesis.KinesisConsumerFactory");
    return new StreamConfig("", props);
  }

  @BeforeMethod
  public void setupTest() {
    _kinesisConnectionHandler = createMock(KinesisConnectionHandler.class);
    _streamConsumerFactory = createMock(StreamConsumerFactory.class);
    _partitionGroupConsumer = createNiceMock(PartitionGroupConsumer.class);
    _kinesisStreamMetadataProvider =
        new KinesisStreamMetadataProvider(CLIENT_ID, getStreamConfig(), _kinesisConnectionHandler,
            _streamConsumerFactory);
  }

  @Test
  public void getPartitionsGroupInfoListTest()
      throws Exception {
    Shard shard0 = Shard.builder().shardId(SHARD_ID_0)
        .sequenceNumberRange(SequenceNumberRange.builder().startingSequenceNumber("1").build()).build();
    Shard shard1 = Shard.builder().shardId(SHARD_ID_1)
        .sequenceNumberRange(SequenceNumberRange.builder().startingSequenceNumber("1").build()).build();

    expect(_kinesisConnectionHandler.getShards()).andReturn(ImmutableList.of(shard0, shard1)).anyTimes();
    replay(_kinesisConnectionHandler);

    List<PartitionGroupMetadata> result = _kinesisStreamMetadataProvider
        .computePartitionGroupMetadata(CLIENT_ID, getStreamConfig(), new ArrayList<>(), TIMEOUT);

    Assert.assertEquals(result.size(), 2);
    Assert.assertEquals(result.get(0).getPartitionGroupId(), 0);
    Assert.assertEquals(result.get(1).getPartitionGroupId(), 1);
  }

  @Test
  public void getPartitionsGroupInfoEndOfShardTest()
      throws Exception {
    List<PartitionGroupConsumptionStatus> currentPartitionGroupMeta = new ArrayList<>();

    KinesisPartitionGroupOffset kinesisPartitionGroupOffset = new KinesisPartitionGroupOffset("0", "1");

    currentPartitionGroupMeta.add(new PartitionGroupConsumptionStatus(0, 1, kinesisPartitionGroupOffset,
        kinesisPartitionGroupOffset, "CONSUMING"));

    Capture<StreamPartitionMsgOffset> checkpointArgs = newCapture(CaptureType.ALL);
    Capture<PartitionGroupConsumptionStatus> partitionGroupMetadataCapture = newCapture(CaptureType.ALL);
    Capture<Integer> intArguments = newCapture(CaptureType.ALL);
    Capture<String> stringCapture = newCapture(CaptureType.ALL);

    Shard shard0 = Shard.builder().shardId(SHARD_ID_0).sequenceNumberRange(
        SequenceNumberRange.builder().startingSequenceNumber("1").endingSequenceNumber("1").build()).build();
    Shard shard1 = Shard.builder().shardId(SHARD_ID_1).sequenceNumberRange(
        SequenceNumberRange.builder().startingSequenceNumber("1").build()).build();
    expect(_kinesisConnectionHandler.getShards()).andReturn(ImmutableList.of(shard0, shard1)).anyTimes();
    expect(_streamConsumerFactory
        .createPartitionGroupConsumer(capture(stringCapture), capture(partitionGroupMetadataCapture)))
        .andReturn(_partitionGroupConsumer).anyTimes();
    expect(_partitionGroupConsumer.fetchMessages(capture(checkpointArgs), captureInt(intArguments))).andReturn(
        new KinesisMessageBatch(new ArrayList<>(), kinesisPartitionGroupOffset, true)).anyTimes();

    replay(_kinesisConnectionHandler, _streamConsumerFactory, _partitionGroupConsumer);

    List<PartitionGroupMetadata> result = _kinesisStreamMetadataProvider
        .computePartitionGroupMetadata(CLIENT_ID, getStreamConfig(), currentPartitionGroupMeta, TIMEOUT);

    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.get(0).getPartitionGroupId(), 1);
  }

  @Test
  public void getPartitionsGroupInfoChildShardsest()
      throws Exception {
    List<PartitionGroupConsumptionStatus> currentPartitionGroupMeta = new ArrayList<>();

    Map<String, String> shardToSequenceMap = new HashMap<>();
    shardToSequenceMap.put("1", "1");
    KinesisPartitionGroupOffset kinesisPartitionGroupOffset = new KinesisPartitionGroupOffset("1", "1");

    currentPartitionGroupMeta.add(new PartitionGroupConsumptionStatus(0, 1, kinesisPartitionGroupOffset,
        kinesisPartitionGroupOffset, "CONSUMING"));

    Capture<StreamPartitionMsgOffset> checkpointArgs = newCapture(CaptureType.ALL);
    Capture<PartitionGroupConsumptionStatus> partitionGroupMetadataCapture = newCapture(CaptureType.ALL);
    Capture<Integer> intArguments = newCapture(CaptureType.ALL);
    Capture<String> stringCapture = newCapture(CaptureType.ALL);

    Shard shard0 = Shard.builder().shardId(SHARD_ID_0).parentShardId(SHARD_ID_1).sequenceNumberRange(
        SequenceNumberRange.builder().startingSequenceNumber("1").build()).build();
    Shard shard1 = Shard.builder().shardId(SHARD_ID_1).sequenceNumberRange(
        SequenceNumberRange.builder().startingSequenceNumber("1").endingSequenceNumber("1").build()).build();

    expect(_kinesisConnectionHandler.getShards()).andReturn(ImmutableList.of(shard0, shard1)).anyTimes();
    expect(_streamConsumerFactory
        .createPartitionGroupConsumer(capture(stringCapture), capture(partitionGroupMetadataCapture)))
        .andReturn(_partitionGroupConsumer).anyTimes();
    expect(_partitionGroupConsumer.fetchMessages(capture(checkpointArgs), captureInt(intArguments))).andReturn(
        new KinesisMessageBatch(new ArrayList<>(), kinesisPartitionGroupOffset, true)).anyTimes();

    replay(_kinesisConnectionHandler, _streamConsumerFactory, _partitionGroupConsumer);

    List<PartitionGroupMetadata> result = _kinesisStreamMetadataProvider
        .computePartitionGroupMetadata(CLIENT_ID, getStreamConfig(), currentPartitionGroupMeta, TIMEOUT);

    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.get(0).getPartitionGroupId(), 0);
  }
}
