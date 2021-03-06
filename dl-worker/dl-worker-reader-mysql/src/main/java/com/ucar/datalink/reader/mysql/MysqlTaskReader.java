package com.ucar.datalink.reader.mysql;

import com.alibaba.otter.canal.instance.core.CanalInstance;
import com.alibaba.otter.canal.instance.core.CanalInstanceGenerator;
import com.alibaba.otter.canal.instance.manager.CanalInstanceWithManager;
import com.alibaba.otter.canal.instance.manager.model.Canal;
import com.alibaba.otter.canal.instance.manager.model.CanalParameter;
import com.alibaba.otter.canal.parse.CanalEventParser;
import com.alibaba.otter.canal.parse.inbound.mysql.MysqlEventParser;
import com.alibaba.otter.canal.protocol.ClientIdentity;
import com.alibaba.otter.canal.protocol.Message;
import com.alibaba.otter.canal.server.embedded.CanalServerWithEmbedded;
import com.alibaba.otter.canal.sink.AbstractCanalEventSink;
import com.alibaba.otter.canal.sink.CanalEventSink;
import com.alibaba.otter.canal.sink.entry.EntryEventSink;
import com.ucar.datalink.domain.plugin.reader.mysql.GroupSinkMode;
import com.ucar.datalink.domain.plugin.reader.mysql.MysqlReaderParameter;
import com.ucar.datalink.contract.log.rdbms.RdbEventRecord;
import com.ucar.datalink.reader.mysql.utils.Constants;
import com.ucar.datalink.reader.mysql.utils.StatisticKey;
import com.ucar.datalink.worker.api.task.RecordChunk;
import com.ucar.datalink.worker.api.task.TaskReader;
import com.ucar.datalink.worker.api.util.statistic.ReaderStatistic;
import org.I0Itec.zkclient.exception.ZkInterruptedException;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;


/**
 * A implemention for reading binlogs from mysql.
 * <p>
 * Created by lubiao on 2017/2/6.
 */
@SuppressWarnings("unchecked")
public class MysqlTaskReader extends TaskReader<MysqlReaderParameter, RdbEventRecord> {

    private static final Logger logger = LoggerFactory.getLogger(MysqlTaskReader.class);
    private static final int MAX_EMPTY_TIMES = 10;
    private static final String MESSAGE_KEY = "MESSAGES";

    private String filter;
    private String destination;
    private int batchSize = 10000;
    private long batchTimeout = -1L;
    private boolean filterTableError = false;
    private CanalServerWithEmbedded canalServer;
    private ClientIdentity clientIdentity;
    private CanalDownStreamHandler handler;
    private MessageParser messageParser;

    @Override
    public void start() {
        if (isStart()) {
            return;
        }

        this.filter = CanalFilterBuilder.makeFilterExpression(context);
        logger.info("The filter for canal is {}.", filter);

        this.destination = context.taskId();
        this.batchSize = parameter.getMessageBatchSize();
        this.batchTimeout = parameter.getBatchTimeout();
        this.messageParser = new MessageParser(context, parameter);
        this.canalServer = new CanalServerWithEmbedded();
        this.canalServer.setCanalInstanceGenerator(new CanalInstanceGenerator() {

            @Override
            public CanalInstance generate(String destination) {
                Canal canal = CanalConfigGenerator.buildCanalConfig(destination, parameter, context);
                final CanalTaskAlarmHandler alarmHandler = new CanalTaskAlarmHandler(context.taskId());
                long slaveId = 10000;// 默认基数
                if (canal.getCanalParameter().getSlaveId() != null) {
                    slaveId = canal.getCanalParameter().getSlaveId();
                }
                canal.getCanalParameter().setSlaveId(slaveId + Long.valueOf(context.taskId()));//动态生成slaveid，避免重复
                canal.getCanalParameter().setFilterTableError(filterTableError);

                CanalInstanceWithManager instance = new CanalInstanceWithManager(canal, filter) {

                    @Override
                    protected void initMetaManager() {
                        CanalTaskMetaManager canalTaskMetaManager = new CanalTaskMetaManager();
                        canalTaskMetaManager.setPositionManager(context.positionManager());
                        canalTaskMetaManager.setFilter(MysqlTaskReader.this.filter);
                        metaManager = canalTaskMetaManager;
                    }

                    @Override
                    protected void startEventParserInternal(CanalEventParser parser, boolean isGroup) {
                        if (parser instanceof MysqlEventParser) {
                            MysqlEventParser mysqlEventParser = (MysqlEventParser) parser;
                            mysqlEventParser.setSupportBinlogFormats("ROW,STATEMENT,MIXED");
                            mysqlEventParser.setSupportBinlogImages("FULL,MINIMAL,NOBLOB");
                        }

                        super.startEventParserInternal(parser, isGroup);
                    }

                    @Override
                    protected void initEventSink() {
                        logger.info("init eventSink begin...");

                        int groupSize = getGroupSize();
                        if (groupSize <= 1) {
                            eventSink = new EntryEventSink();
                        } else {
                            if (parameter.getGroupSinkMode() == GroupSinkMode.Coordinate) {
                                eventSink = new FixedGroupEventSink(groupSize);
                            } else {
                                eventSink = new EntryEventSink();
                            }
                        }

                        if (eventSink instanceof EntryEventSink) {
                            ((EntryEventSink) eventSink).setFilterTransactionEntry(false);
                            ((EntryEventSink) eventSink).setEventStore(getEventStore());
                        }

                        logger.info("init eventSink end! \n\t load CanalEventSink:{}", eventSink.getClass().getName());
                    }

                    private int getGroupSize() {
                        List<List<CanalParameter.DataSourcing>> groupDbAddresses = parameters.getGroupDbAddresses();
                        if (!org.springframework.util.CollectionUtils.isEmpty(groupDbAddresses)) {
                            return groupDbAddresses.get(0).size();
                        } else {
                            // 可能是基于tddl的启动
                            return 1;
                        }
                    }
                };
                instance.setAlarmHandler(alarmHandler);

                CanalEventSink eventSink = instance.getEventSink();
                if (eventSink instanceof AbstractCanalEventSink) {
                    //TODO DownStreamHandler
                }

                return instance;
            }
        });

        canalServer.start();
        canalServer.start(destination);
        this.clientIdentity = new ClientIdentity(destination, Short.valueOf(context.taskId()), filter);
        canalServer.subscribe(clientIdentity);// 发起一次订阅

        super.start();
    }

    @Override
    public void stop() {
        if (!isStart()) {
            return;
        }
        super.stop();
    }

    @Override
    public void close() {
        if (handler != null) {
            try {
                handler.stop();
            } catch (Exception e) {
                logger.warn("failed destory handler", e);
            }
            handler = null;
        }

        canalServer.stop(destination);
        canalServer.stop();
    }

    @Override
    public void commit(RecordChunk<RdbEventRecord> recordChunk) throws InterruptedException {
        canalServer.ack(clientIdentity, recordChunk.getMetaData(Constants.BATCH_ID));
    }

    @Override
    public void rollback(RecordChunk<RdbEventRecord> recordChunk, Throwable t) {
        canalServer.rollback(clientIdentity, recordChunk.getMetaData(Constants.BATCH_ID));
    }

    @Override
    public RecordChunk<RdbEventRecord> fetch() throws InterruptedException {
        int emptyTimes = 0;
        Message message = null;
        try {
            //statistic before
            ReaderStatistic readerStatistic = context.taskReaderSession().getData(ReaderStatistic.KEY);
            long startTime = System.currentTimeMillis();

            //do get
            if (batchTimeout < 0) {// 轮询处理
                while (isStart()) {
                    message = canalServer.getWithoutAck(clientIdentity, batchSize);
                    if (message == null || message.getId() == -1L) { // no events
                        applyWait(emptyTimes++);
                    } else {
                        break;
                    }
                }
                if (!isStart()) {
                    throw new InterruptedException();
                }
            } else { // 超时控制
                while (isStart()) {
                    message = canalServer.getWithoutAck(clientIdentity, batchSize, batchTimeout, TimeUnit.MILLISECONDS);
                    if (message == null || message.getId() == -1L) { // no events
                        continue;
                    } else {
                        break;
                    }
                }
                if (!isStart()) {
                    throw new InterruptedException();
                }
            }

            //statistic after
            readerStatistic.getExtendStatistic().put(StatisticKey.GET_MESSAGE_TIME_THROUGH, System.currentTimeMillis() - startTime);
            readerStatistic.getExtendStatistic().put(StatisticKey.GET_MESSAGE_ENTRYS_COUNT, message.getEntries().size());
        } catch (ZkInterruptedException e) {
            throw new InterruptedException();
        }

        // 获取第一个的entry时间，包括被过滤的数据
        // 获取该批次数据对应的binlog日志大小
        long firstEntryTime = 0;
        long payloadSize = 0;
        if (!org.springframework.util.CollectionUtils.isEmpty(message.getEntries())) {
            firstEntryTime = message.getEntries().get(0).getHeader().getExecuteTime();
            payloadSize = message.getEntries().stream().mapToLong(i -> i.getHeader().getEventLength()).summaryStatistics().getSum();
        }

        if (parameter.isDump()) {
            context.taskReaderSession().setData(MESSAGE_KEY, message);
        }

        RecordChunk<RdbEventRecord> result = new RecordChunk<>(
                messageParser.parse(message.getEntries(), context)
                        .stream()
                        .filter(r -> !parameter.getFilteredEventTypes().contains(r.getEventType()))
                        .collect(Collectors.toList()),
                firstEntryTime,
                payloadSize
        );
        result.putMetaData(Constants.BATCH_ID, message.getId());
        return result;
    }

    @Override
    protected void dump(RecordChunk<RdbEventRecord> recordChunk) {
        if (logger.isInfoEnabled()) {
            Message message = context.taskReaderSession().getData(MESSAGE_KEY);
            String startPosition = null;
            String endPosition = null;
            if (!CollectionUtils.isEmpty(message.getEntries())) {
                startPosition = RecordsDumper.buildPositionForDump(message.getEntries().get(0));
                endPosition = RecordsDumper.buildPositionForDump(message.getEntries().get(message.getEntries().size() - 1));
            }

            //dump for troubleshoot problems
            MessageDumper.dumpMessages(message, recordChunk.getMetaData(Constants.BATCH_ID), message.getEntries().size());
            RecordsDumper.dumpRecords(recordChunk, startPosition, endPosition, message.getEntries().size(), parameter.isDumpDetail());
        }
        context.taskReaderSession().removeData(MESSAGE_KEY);
    }

    /**
     * 处理无数据的情况，避免空循环挂死
     */
    private void applyWait(int emptyTimes) {
        int newEmptyTimes = emptyTimes > MAX_EMPTY_TIMES ? MAX_EMPTY_TIMES : emptyTimes;
        if (emptyTimes <= 3) { // 3次以内
            Thread.yield();
        } else { // 超过3次，最多只sleep 10ms
            LockSupport.parkNanos(1000 * 1000L * newEmptyTimes);
        }
    }
}
