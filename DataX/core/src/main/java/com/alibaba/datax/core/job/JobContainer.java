package com.alibaba.datax.core.job;

import com.alibaba.datax.common.constant.PluginType;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.AbstractJobPlugin;
import com.alibaba.datax.common.plugin.JobPluginCollector;
import com.alibaba.datax.common.spi.Reader;
import com.alibaba.datax.common.spi.Writer;
import com.alibaba.datax.common.statistics.PerfTrace;
import com.alibaba.datax.common.statistics.VMInfo;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.common.util.StrUtil;
import com.alibaba.datax.core.AbstractContainer;
import com.alibaba.datax.core.Engine;
import com.alibaba.datax.core.container.util.HookInvoker;
import com.alibaba.datax.core.container.util.JobAssignUtil;
import com.alibaba.datax.core.job.scheduler.AbstractScheduler;
import com.alibaba.datax.core.job.scheduler.processinner.StandAloneScheduler;
import com.alibaba.datax.core.log.DataxLog;
import com.alibaba.datax.core.log.LogManager;
import com.alibaba.datax.core.statistics.communication.Communication;
import com.alibaba.datax.core.statistics.communication.CommunicationTool;
import com.alibaba.datax.core.statistics.container.communicator.AbstractContainerCommunicator;
import com.alibaba.datax.core.statistics.container.communicator.job.StandAloneJobContainerCommunicator;
import com.alibaba.datax.core.statistics.plugin.DefaultJobPluginCollector;
import com.alibaba.datax.core.util.ErrorRecordChecker;
import com.alibaba.datax.core.util.FrameworkErrorCode;
import com.alibaba.datax.core.util.container.ClassLoaderSwapper;
import com.alibaba.datax.core.util.container.CoreConstant;
import com.alibaba.datax.core.util.container.LoadUtil;
import com.alibaba.datax.dataxservice.face.domain.enums.ExecuteMode;
import com.alibaba.fastjson.JSON;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by jingxing on 14-8-24.
 * <p/>
 * job???????????????jobContainer?????????????????????????????????master????????????????????????????????????????????????????????????????????????
 * ??????????????????????????????????????????
 */
public class JobContainer extends AbstractContainer {
    private static final Logger LOG = LoggerFactory
            .getLogger(JobContainer.class);

    private static final SimpleDateFormat dateFormat = new SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss");

    private ClassLoaderSwapper classLoaderSwapper = ClassLoaderSwapper
            .newCurrentThreadClassLoaderSwapper();

    private long jobId;

    private String readerPluginName;

    private String writerPluginName;

    /**
     * reader???writer jobContainer?????????
     */
    private Reader.Job jobReader;

    private Writer.Job jobWriter;

    private Configuration userConf;

    private long startTimeStamp;

    private long endTimeStamp;

    private long startTransferTimeStamp;

    private long endTransferTimeStamp;

    private int needChannelNumber;

    private int totalStage = 1;

    private ErrorRecordChecker errorLimit;

    public JobContainer(Configuration configuration) {
        super(configuration);

        errorLimit = new ErrorRecordChecker(configuration);
    }

    /**
     * jobContainer??????????????????????????????start()???????????????init???prepare???split???scheduler???
     * post??????destroy???statistics
     */
    @Override
    public void start() {
        LOG.info("DataX jobContainer starts job.");

        boolean hasException = false;
        boolean isDryRun = false;
        try {
            this.startTimeStamp = System.currentTimeMillis();
            isDryRun = configuration.getBool(CoreConstant.DATAX_JOB_SETTING_DRYRUN, false);
            if(isDryRun) {
                LOG.info("jobContainer starts to do preCheck ...");
                this.preCheck();
            } else {
                userConf = configuration.clone();
                LOG.debug("jobContainer starts to do preHandle ...");
                this.preHandle();

                LOG.debug("jobContainer starts to do init ...");
                this.init();
                LOG.info("jobContainer starts to do prepare ...");
                this.prepare();
                LOG.info("jobContainer starts to do split ...");
                this.totalStage = this.split();
                LOG.info("jobContainer starts to do schedule ...");
                this.schedule();
                LOG.debug("jobContainer starts to do post ...");
                this.post();

                LOG.debug("jobContainer starts to do postHandle ...");
                this.postHandle();
                LOG.info("DataX jobId [{}] completed successfully.", this.jobId);

                this.invokeHooks();
            }
        } catch (Throwable e) {
            LOG.error("Exception when job run", e);

            hasException = true;

            if (e instanceof OutOfMemoryError) {
                this.destroy();
                System.gc();
            }


            if (super.getContainerCommunicator() == null) {
                // ?????? containerCollector ?????? scheduler() ?????????????????????????????? scheduler() ?????????????????????????????????????????? containerCollector ???????????????

                AbstractContainerCommunicator tempContainerCollector;
                // standalone
                tempContainerCollector = new StandAloneJobContainerCommunicator(configuration);

                super.setContainerCommunicator(tempContainerCollector);
            }

            Communication communication = super.getContainerCommunicator().collect();
            // ????????????????????????????????????????????????
            // communication.setState(State.FAILED);
            communication.setThrowable(e);
            communication.setTimestamp(this.endTimeStamp);

            Communication tempComm = new Communication();
            tempComm.setTimestamp(this.startTransferTimeStamp);

            Communication reportCommunication = CommunicationTool.getReportCommunication(communication, tempComm, this.totalStage);
            super.getContainerCommunicator().report(reportCommunication);

            throw DataXException.asDataXException(
                    FrameworkErrorCode.RUNTIME_ERROR, e);
        } finally {
            if(!isDryRun) {

                this.destroy();
                this.endTimeStamp = System.currentTimeMillis();
                if (!hasException) {
                    //????????????cpu??????????????????GC?????????
                    VMInfo vmInfo = VMInfo.getVmInfo();
                    if (vmInfo != null) {
                        vmInfo.getDelta(false);
                        LOG.info(vmInfo.totalString());
                    }

                    LOG.info(PerfTrace.getInstance().summarizeNoException());
                    this.logStatistics();
                }
            }
        }
    }

    private void preCheck() {
        this.preCheckInit();
        this.adjustChannelNumber();

        if (this.needChannelNumber <= 0) {
            this.needChannelNumber = 1;
        }
        this.preCheckReader();
        this.preCheckWriter();
        LOG.info("PreCheck??????");
    }

    private void preCheckInit() {
        this.jobId = this.configuration.getLong(
                CoreConstant.DATAX_CORE_CONTAINER_JOB_ID, -1);

        if (this.jobId < 0) {
            LOG.info("Set jobId = 0");
            this.jobId = 0;
            this.configuration.set(CoreConstant.DATAX_CORE_CONTAINER_JOB_ID,
                    this.jobId);
        }

        Thread.currentThread().setName("job-" + this.jobId);

        JobPluginCollector jobPluginCollector = new DefaultJobPluginCollector(
                this.getContainerCommunicator());
        this.jobReader = this.preCheckReaderInit(jobPluginCollector);
        this.jobWriter = this.preCheckWriterInit(jobPluginCollector);
    }

    private Reader.Job preCheckReaderInit(JobPluginCollector jobPluginCollector) {
        this.readerPluginName = this.configuration.getString(
                CoreConstant.DATAX_JOB_CONTENT_READER_NAME);
        classLoaderSwapper.setCurrentThreadClassLoader(LoadUtil.getJarLoader(
                PluginType.READER, this.readerPluginName));

        Reader.Job jobReader = (Reader.Job) LoadUtil.loadJobPlugin(
                PluginType.READER, this.readerPluginName);

        this.configuration.set(CoreConstant.DATAX_JOB_CONTENT_READER_PARAMETER + ".dryRun", true);

        // ??????reader???jobConfig
        jobReader.setPluginJobConf(this.configuration.getConfiguration(
                CoreConstant.DATAX_JOB_CONTENT_READER_PARAMETER));
        // ??????reader???readerConfig
        jobReader.setPeerPluginJobConf(this.configuration.getConfiguration(
                CoreConstant.DATAX_JOB_CONTENT_READER_PARAMETER));

        jobReader.setJobPluginCollector(jobPluginCollector);

        classLoaderSwapper.restoreCurrentThreadClassLoader();
        return jobReader;
    }


    private Writer.Job preCheckWriterInit(JobPluginCollector jobPluginCollector) {
        this.writerPluginName = this.configuration.getString(
                CoreConstant.DATAX_JOB_CONTENT_WRITER_NAME);
        classLoaderSwapper.setCurrentThreadClassLoader(LoadUtil.getJarLoader(
                PluginType.WRITER, this.writerPluginName));

        Writer.Job jobWriter = (Writer.Job) LoadUtil.loadJobPlugin(
                PluginType.WRITER, this.writerPluginName);

        this.configuration.set(CoreConstant.DATAX_JOB_CONTENT_WRITER_PARAMETER + ".dryRun", true);

        // ??????writer???jobConfig
        jobWriter.setPluginJobConf(this.configuration.getConfiguration(
                CoreConstant.DATAX_JOB_CONTENT_WRITER_PARAMETER));
        // ??????reader???readerConfig
        jobWriter.setPeerPluginJobConf(this.configuration.getConfiguration(
                CoreConstant.DATAX_JOB_CONTENT_READER_PARAMETER));

        jobWriter.setPeerPluginName(this.readerPluginName);
        jobWriter.setJobPluginCollector(jobPluginCollector);

        classLoaderSwapper.restoreCurrentThreadClassLoader();

        return jobWriter;
    }

    private void preCheckReader() {
        classLoaderSwapper.setCurrentThreadClassLoader(LoadUtil.getJarLoader(
                PluginType.READER, this.readerPluginName));
        LOG.info(String.format("DataX Reader.Job [%s] do preCheck work .",
                this.readerPluginName));
        this.jobReader.preCheck();
        classLoaderSwapper.restoreCurrentThreadClassLoader();
    }

    private void preCheckWriter() {
        classLoaderSwapper.setCurrentThreadClassLoader(LoadUtil.getJarLoader(
                PluginType.WRITER, this.writerPluginName));
        LOG.info(String.format("DataX Writer.Job [%s] do preCheck work .",
                this.writerPluginName));
        this.jobWriter.preCheck();
        classLoaderSwapper.restoreCurrentThreadClassLoader();
    }

    /**
     * reader???writer????????????
     */
    private void init() {
        this.jobId = this.configuration.getLong(
                CoreConstant.DATAX_CORE_CONTAINER_JOB_ID, -1);

        if (this.jobId < 0) {
            LOG.info("Set jobId = 0");
            this.jobId = 0;
            this.configuration.set(CoreConstant.DATAX_CORE_CONTAINER_JOB_ID,
                    this.jobId);
        }

        Thread.currentThread().setName("job-" + this.jobId);

        JobPluginCollector jobPluginCollector = new DefaultJobPluginCollector(
                this.getContainerCommunicator());
        //?????????Reader ??????Writer
        this.jobReader = this.initJobReader(jobPluginCollector);
        this.jobWriter = this.initJobWriter(jobPluginCollector);
    }

    private void prepare() {
        this.prepareJobReader();
        this.prepareJobWriter();
    }

    private void preHandle() {
        String handlerPluginTypeStr = this.configuration.getString(
                CoreConstant.DATAX_JOB_PREHANDLER_PLUGINTYPE);
        if(!StringUtils.isNotEmpty(handlerPluginTypeStr)){
            return;
        }
        PluginType handlerPluginType;
        try {
            handlerPluginType = PluginType.valueOf(handlerPluginTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw DataXException.asDataXException(
                    FrameworkErrorCode.CONFIG_ERROR,
                    String.format("Job preHandler's pluginType(%s) set error, reason(%s)", handlerPluginTypeStr.toUpperCase(), e.getMessage()));
        }

        String handlerPluginName = this.configuration.getString(
                CoreConstant.DATAX_JOB_PREHANDLER_PLUGINNAME);

        classLoaderSwapper.setCurrentThreadClassLoader(LoadUtil.getJarLoader(
                handlerPluginType, handlerPluginName));

        AbstractJobPlugin handler = LoadUtil.loadJobPlugin(
                handlerPluginType, handlerPluginName);

        JobPluginCollector jobPluginCollector = new DefaultJobPluginCollector(
                this.getContainerCommunicator());
        handler.setJobPluginCollector(jobPluginCollector);

        //todo configuration?????????????????????????????????
        handler.preHandler(configuration);
        classLoaderSwapper.restoreCurrentThreadClassLoader();

        LOG.info("After PreHandler: \n" + Engine.filterJobConfiguration(configuration) + "\n");
    }

    private void postHandle() {
        String handlerPluginTypeStr = this.configuration.getString(
                CoreConstant.DATAX_JOB_POSTHANDLER_PLUGINTYPE);

        if(!StringUtils.isNotEmpty(handlerPluginTypeStr)){
            return;
        }
        PluginType handlerPluginType;
        try {
            handlerPluginType = PluginType.valueOf(handlerPluginTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw DataXException.asDataXException(
                    FrameworkErrorCode.CONFIG_ERROR,
                    String.format("Job postHandler's pluginType(%s) set error, reason(%s)", handlerPluginTypeStr.toUpperCase(), e.getMessage()));
        }

        String handlerPluginName = this.configuration.getString(
                CoreConstant.DATAX_JOB_POSTHANDLER_PLUGINNAME);

        classLoaderSwapper.setCurrentThreadClassLoader(LoadUtil.getJarLoader(
                handlerPluginType, handlerPluginName));

        AbstractJobPlugin handler = LoadUtil.loadJobPlugin(
                handlerPluginType, handlerPluginName);

        JobPluginCollector jobPluginCollector = new DefaultJobPluginCollector(
                this.getContainerCommunicator());
        handler.setJobPluginCollector(jobPluginCollector);

        handler.postHandler(configuration);
        classLoaderSwapper.restoreCurrentThreadClassLoader();
    }


    /**
     * ??????reader???writer?????????????????????????????????????????????writer????????????????????????reader??????????????????
     * ??????????????????????????????????????????1???1???????????????????????????????????????reader???writer???????????????????????????
     * ???????????????????????????????????????????????????????????????????????????shuffler???
     */
    private int split() {
        this.adjustChannelNumber();

        if (this.needChannelNumber <= 0) {
            this.needChannelNumber = 1;
        }

        List<Configuration> readerTaskConfigs = this
                .doReaderSplit(this.needChannelNumber);
        int taskNumber = readerTaskConfigs.size();
        List<Configuration> writerTaskConfigs = this
                .doWriterSplit(taskNumber);

        List<Configuration> transformerList = this.configuration.getListConfiguration(CoreConstant.DATAX_JOB_CONTENT_TRANSFORMER);

        LOG.debug("transformer configuration: "+ JSON.toJSONString(transformerList));
        /**
         * ?????????reader???writer???parameter list????????????content???????????????list
         */
        List<Configuration> contentConfig = mergeReaderAndWriterTaskConfigs(
                readerTaskConfigs, writerTaskConfigs, transformerList);


        LOG.debug("contentConfig configuration: "+ JSON.toJSONString(contentConfig));

        this.configuration.set(CoreConstant.DATAX_JOB_CONTENT, contentConfig);

        return contentConfig.size();
    }

    private void adjustChannelNumber() {
        int needChannelNumberByByte = Integer.MAX_VALUE;
        int needChannelNumberByRecord = Integer.MAX_VALUE;

        boolean isByteLimit = (this.configuration.getInt(
                CoreConstant.DATAX_JOB_SETTING_SPEED_BYTE, 0) > 0);
        if (isByteLimit) {
            long globalLimitedByteSpeed = this.configuration.getInt(
                    CoreConstant.DATAX_JOB_SETTING_SPEED_BYTE, 10 * 1024 * 1024);

            // ???byte????????????????????????Channel?????????????????????????????????????????????
            Long channelLimitedByteSpeed = this.configuration
                    .getLong(CoreConstant.DATAX_CORE_TRANSPORT_CHANNEL_SPEED_BYTE);
            if (channelLimitedByteSpeed == null || channelLimitedByteSpeed <= 0) {
                throw DataXException.asDataXException(
                        FrameworkErrorCode.CONFIG_ERROR,
                        "?????????bps????????????????????????channel???bps???????????????????????????????????????");
            }

            needChannelNumberByByte =
                    (int) (globalLimitedByteSpeed / channelLimitedByteSpeed);
            needChannelNumberByByte =
                    needChannelNumberByByte > 0 ? needChannelNumberByByte : 1;
            LOG.info("Job set Max-Byte-Speed to " + globalLimitedByteSpeed + " bytes.");
        }

        boolean isRecordLimit = (this.configuration.getInt(
                CoreConstant.DATAX_JOB_SETTING_SPEED_RECORD, 0)) > 0;
        if (isRecordLimit) {
            long globalLimitedRecordSpeed = this.configuration.getInt(
                    CoreConstant.DATAX_JOB_SETTING_SPEED_RECORD, 100000);

            Long channelLimitedRecordSpeed = this.configuration.getLong(
                    CoreConstant.DATAX_CORE_TRANSPORT_CHANNEL_SPEED_RECORD);
            if (channelLimitedRecordSpeed == null || channelLimitedRecordSpeed <= 0) {
                throw DataXException.asDataXException(FrameworkErrorCode.CONFIG_ERROR,
                        "?????????tps????????????????????????channel???tps???????????????????????????????????????");
            }

            needChannelNumberByRecord =
                    (int) (globalLimitedRecordSpeed / channelLimitedRecordSpeed);
            needChannelNumberByRecord =
                    needChannelNumberByRecord > 0 ? needChannelNumberByRecord : 1;
            LOG.info("Job set Max-Record-Speed to " + globalLimitedRecordSpeed + " records.");
        }

        // ????????????
        this.needChannelNumber = needChannelNumberByByte < needChannelNumberByRecord ?
                needChannelNumberByByte : needChannelNumberByRecord;

        // ?????????byte???record????????????needChannelNumber?????????
        if (this.needChannelNumber < Integer.MAX_VALUE) {
            return;
        }

        boolean isChannelLimit = (this.configuration.getInt(
                CoreConstant.DATAX_JOB_SETTING_SPEED_CHANNEL, 0) > 0);
        if (isChannelLimit) {
            this.needChannelNumber = this.configuration.getInt(
                    CoreConstant.DATAX_JOB_SETTING_SPEED_CHANNEL);

            LOG.info("Job set Channel-Number to " + this.needChannelNumber
                    + " channels.");

            return;
        }

        throw DataXException.asDataXException(
                FrameworkErrorCode.CONFIG_ERROR,
                "Job????????????????????????");
    }

    /**
     * schedule????????????????????????????????????reader???writer split????????????????????????taskGroupContainer???,
     * ????????????????????????????????????????????????????????????????????????????????????
     */
    private void schedule() {
        /**
         * ???????????????speed?????????channel??????????????????B/s
         */
        int channelsPerTaskGroup = this.configuration.getInt(
                CoreConstant.DATAX_CORE_CONTAINER_TASKGROUP_CHANNEL, 5);
        int taskNumber = this.configuration.getList(
                CoreConstant.DATAX_JOB_CONTENT).size();

        this.needChannelNumber = Math.min(this.needChannelNumber, taskNumber);
        PerfTrace.getInstance().setChannelNumber(needChannelNumber);

        /**
         * ????????????????????????????????????taskGroup??????????????????tasks??????
         */

        List<Configuration> taskGroupConfigs = JobAssignUtil.assignFairly(this.configuration,
                this.needChannelNumber, channelsPerTaskGroup);

        LOG.info("Scheduler starts [{}] taskGroups.", taskGroupConfigs.size());

        ExecuteMode executeMode = null;
        AbstractScheduler scheduler;
        try {
        	executeMode = ExecuteMode.STANDALONE;
            scheduler = initStandaloneScheduler(this.configuration);

            //?????? executeMode
            for (Configuration taskGroupConfig : taskGroupConfigs) {
                taskGroupConfig.set(CoreConstant.DATAX_CORE_CONTAINER_JOB_MODE, executeMode.getValue());
            }

            if (executeMode == ExecuteMode.LOCAL || executeMode == ExecuteMode.DISTRIBUTE) {
                if (this.jobId <= 0) {
                    throw DataXException.asDataXException(FrameworkErrorCode.RUNTIME_ERROR,
                            "???[ local | distribute ]?????????????????????jobId??????????????? > 0 .");
                }
            }

            LOG.info("Running by {} Mode.", executeMode);

            this.startTransferTimeStamp = System.currentTimeMillis();

            scheduler.schedule(taskGroupConfigs);

            this.endTransferTimeStamp = System.currentTimeMillis();
        } catch (Exception e) {
            LOG.error("??????scheduler ??????[{}]??????.", executeMode);
            this.endTransferTimeStamp = System.currentTimeMillis();
            throw DataXException.asDataXException(
                    FrameworkErrorCode.RUNTIME_ERROR, e);
        }

        /**
         * ????????????????????????
         */
        this.checkLimit();
    }


    private AbstractScheduler initStandaloneScheduler(Configuration configuration) {
        AbstractContainerCommunicator containerCommunicator = new StandAloneJobContainerCommunicator(configuration);
        super.setContainerCommunicator(containerCommunicator);

        return new StandAloneScheduler(containerCommunicator);
    }

    private void post() {
        this.postJobWriter();
        this.postJobReader();
    }

    private void destroy() {
        if (this.jobWriter != null) {
            this.jobWriter.destroy();
            this.jobWriter = null;
        }
        if (this.jobReader != null) {
            this.jobReader.destroy();
            this.jobReader = null;
        }
    }

    private void logStatistics() {
        long totalCosts = (this.endTimeStamp - this.startTimeStamp) / 1000;
        long transferCosts = (this.endTransferTimeStamp - this.startTransferTimeStamp) / 1000;
        if (0L == transferCosts) {
            transferCosts = 1L;
        }

        if (super.getContainerCommunicator() == null) {
            return;
        }

        Communication communication = super.getContainerCommunicator().collect();
        communication.setTimestamp(this.endTimeStamp);

        Communication tempComm = new Communication();
        tempComm.setTimestamp(this.startTransferTimeStamp);

        Communication reportCommunication = CommunicationTool.getReportCommunication(communication, tempComm, this.totalStage);

        // ????????????
        long byteSpeedPerSecond = communication.getLongCounter(CommunicationTool.READ_SUCCEED_BYTES)
                / transferCosts;

        long recordSpeedPerSecond = communication.getLongCounter(CommunicationTool.READ_SUCCEED_RECORDS)
                / transferCosts;

        reportCommunication.setLongCounter(CommunicationTool.BYTE_SPEED, byteSpeedPerSecond);
        reportCommunication.setLongCounter(CommunicationTool.RECORD_SPEED, recordSpeedPerSecond);

        super.getContainerCommunicator().report(reportCommunication);
        long totalReadRecords = CommunicationTool.getTotalReadRecords(communication);
        long totalErrorRecords = CommunicationTool.getTotalErrorRecords(communication);
        Long transformer_succeed_records = communication.getLongCounter(CommunicationTool.TRANSFORMER_SUCCEED_RECORDS);
        Long transformer_filter_records = communication.getLongCounter(CommunicationTool.TRANSFORMER_FILTER_RECORDS);
        Long transformer_failed_records = communication.getLongCounter(CommunicationTool.TRANSFORMER_FAILED_RECORDS);
        Long read_succeed_bytes = communication.getLongCounter(CommunicationTool.READ_SUCCEED_BYTES);
        Long transformer_used_time = communication.getLongCounter(CommunicationTool.TRANSFORMER_USED_TIME);


        LOG.info(String.format(
                "\n" + "%-26s: %-18s\n" + "%-26s: %-18s\n" + "%-26s: %19s\n"
                        + "%-26s: %19s\n" + "%-26s: %19s\n" + "%-26s: %19s\n"
                        + "%-26s: %19s\n",
                "??????????????????",
                dateFormat.format(startTimeStamp),

                "??????????????????",
                dateFormat.format(endTimeStamp),

                "??????????????????",
                String.valueOf(totalCosts) + "s",
                "??????????????????",
                StrUtil.stringify(byteSpeedPerSecond)
                        + "/s",
                "??????????????????",
                String.valueOf(recordSpeedPerSecond)
                        + "rec/s", "??????????????????",
                String.valueOf(totalReadRecords),
                "??????????????????",
                String.valueOf(totalErrorRecords)
        ));

        Long jobId = configuration.getLong(
                CoreConstant.DATAX_CORE_CONTAINER_JOB_ID, 0); // ?????? jobId ??????????????????

        DataxLog dataxLogIfNullCreate = LogManager.getDataxLogIfNullCreate(jobId);
        dataxLogIfNullCreate.setId(jobId);
        dataxLogIfNullCreate.setStartTimeStamp(startTimeStamp);
        dataxLogIfNullCreate.setEndTimeStamp(endTimeStamp);
        dataxLogIfNullCreate.setTotalCosts(totalCosts);
        dataxLogIfNullCreate.setByteSpeedPerSecond(byteSpeedPerSecond);
        dataxLogIfNullCreate.setRecordSpeedPerSecond(recordSpeedPerSecond);
        dataxLogIfNullCreate.setTotalReadRecords(totalReadRecords);
        dataxLogIfNullCreate.setTotalErrorRecords(totalErrorRecords);


        if (transformer_succeed_records > 0
                || transformer_failed_records > 0
                || transformer_filter_records > 0) {
            LOG.info(String.format(
                    "\n" + "%-26s: %19s\n" + "%-26s: %19s\n" + "%-26s: %19s\n",
                    "Transformer??????????????????",
                    transformer_succeed_records,

                    "Transformer??????????????????",
                    transformer_failed_records,

                    "Transformer??????????????????",
                    transformer_filter_records
            ));

            dataxLogIfNullCreate.setTransformerSucceedRecords(transformer_succeed_records);
            dataxLogIfNullCreate.setTransformerFailedRecords(transformer_failed_records);
            dataxLogIfNullCreate.setTransformerFilterRecords(transformer_filter_records);
            dataxLogIfNullCreate.setReadSucceedBytes(read_succeed_bytes);
            dataxLogIfNullCreate.setTransferCosts(transformer_used_time);
        }
    }

    /**
     * reader job?????????????????????Reader.Job
     *
     * @return
     */
    private Reader.Job initJobReader(
            JobPluginCollector jobPluginCollector) {
        this.readerPluginName = this.configuration.getString(
                CoreConstant.DATAX_JOB_CONTENT_READER_NAME);
        classLoaderSwapper.setCurrentThreadClassLoader(LoadUtil.getJarLoader(
                PluginType.READER, this.readerPluginName));

        Reader.Job jobReader = (Reader.Job) LoadUtil.loadJobPlugin(
                PluginType.READER, this.readerPluginName);

        // ??????reader???jobConfig
        jobReader.setPluginJobConf(this.configuration.getConfiguration(
                CoreConstant.DATAX_JOB_CONTENT_READER_PARAMETER));

        // ??????reader???readerConfig
        jobReader.setPeerPluginJobConf(this.configuration.getConfiguration(
                CoreConstant.DATAX_JOB_CONTENT_WRITER_PARAMETER));

        jobReader.setJobPluginCollector(jobPluginCollector);
        jobReader.init();

        classLoaderSwapper.restoreCurrentThreadClassLoader();
        return jobReader;
    }

    /**
     * writer job?????????????????????Writer.Job
     *
     * @return
     */
    private Writer.Job initJobWriter(
            JobPluginCollector jobPluginCollector) {
        this.writerPluginName = this.configuration.getString(
                CoreConstant.DATAX_JOB_CONTENT_WRITER_NAME);
        classLoaderSwapper.setCurrentThreadClassLoader(LoadUtil.getJarLoader(
                PluginType.WRITER, this.writerPluginName));

        Writer.Job jobWriter = (Writer.Job) LoadUtil.loadJobPlugin(
                PluginType.WRITER, this.writerPluginName);

        // ??????writer???jobConfig
        jobWriter.setPluginJobConf(this.configuration.getConfiguration(
                CoreConstant.DATAX_JOB_CONTENT_WRITER_PARAMETER));

        // ??????reader???readerConfig
        jobWriter.setPeerPluginJobConf(this.configuration.getConfiguration(
                CoreConstant.DATAX_JOB_CONTENT_READER_PARAMETER));

        jobWriter.setPeerPluginName(this.readerPluginName);
        jobWriter.setJobPluginCollector(jobPluginCollector);
        jobWriter.init();
        classLoaderSwapper.restoreCurrentThreadClassLoader();

        return jobWriter;
    }

    private void prepareJobReader() {
        classLoaderSwapper.setCurrentThreadClassLoader(LoadUtil.getJarLoader(
                PluginType.READER, this.readerPluginName));
        LOG.info(String.format("DataX Reader.Job [%s] do prepare work .",
                this.readerPluginName));
        this.jobReader.prepare();
        classLoaderSwapper.restoreCurrentThreadClassLoader();
    }

    private void prepareJobWriter() {
        classLoaderSwapper.setCurrentThreadClassLoader(LoadUtil.getJarLoader(
                PluginType.WRITER, this.writerPluginName));
        LOG.info(String.format("DataX Writer.Job [%s] do prepare work .",
                this.writerPluginName));
        this.jobWriter.prepare();
        classLoaderSwapper.restoreCurrentThreadClassLoader();
    }

    // TODO: ???????????????????????????
    private List<Configuration> doReaderSplit(int adviceNumber) {
        classLoaderSwapper.setCurrentThreadClassLoader(LoadUtil.getJarLoader(
                PluginType.READER, this.readerPluginName));
        List<Configuration> readerSlicesConfigs =
                this.jobReader.split(adviceNumber);
        if (readerSlicesConfigs == null || readerSlicesConfigs.size() <= 0) {
            throw DataXException.asDataXException(
                    FrameworkErrorCode.PLUGIN_SPLIT_ERROR,
                    "reader?????????task????????????????????????0");
        }
        LOG.info("DataX Reader.Job [{}] splits to [{}] tasks.",
                this.readerPluginName, readerSlicesConfigs.size());
        classLoaderSwapper.restoreCurrentThreadClassLoader();
        return readerSlicesConfigs;
    }

    private List<Configuration> doWriterSplit(int readerTaskNumber) {
        classLoaderSwapper.setCurrentThreadClassLoader(LoadUtil.getJarLoader(
                PluginType.WRITER, this.writerPluginName));

        List<Configuration> writerSlicesConfigs = this.jobWriter
                .split(readerTaskNumber);
        if (writerSlicesConfigs == null || writerSlicesConfigs.size() <= 0) {
            throw DataXException.asDataXException(
                    FrameworkErrorCode.PLUGIN_SPLIT_ERROR,
                    "writer?????????task??????????????????0");
        }
        LOG.info("DataX Writer.Job [{}] splits to [{}] tasks.",
                this.writerPluginName, writerSlicesConfigs.size());
        classLoaderSwapper.restoreCurrentThreadClassLoader();

        return writerSlicesConfigs;
    }

    /**
     * ???????????????reader???writer??????????????????????????????????????? ?????????reader???writer???????????????????????????????????????task?????????
     *
     * @param readerTasksConfigs
     * @param writerTasksConfigs
     * @return
     */
    private List<Configuration> mergeReaderAndWriterTaskConfigs(
            List<Configuration> readerTasksConfigs,
            List<Configuration> writerTasksConfigs) {
        return mergeReaderAndWriterTaskConfigs(readerTasksConfigs, writerTasksConfigs, null);
    }

    private List<Configuration> mergeReaderAndWriterTaskConfigs(
            List<Configuration> readerTasksConfigs,
            List<Configuration> writerTasksConfigs,
            List<Configuration> transformerConfigs) {
        if (readerTasksConfigs.size() != writerTasksConfigs.size()) {
            throw DataXException.asDataXException(
                    FrameworkErrorCode.PLUGIN_SPLIT_ERROR,
                    String.format("reader?????????task??????[%d]?????????writer?????????task??????[%d].",
                            readerTasksConfigs.size(), writerTasksConfigs.size())
            );
        }

        List<Configuration> contentConfigs = new ArrayList<Configuration>();
        for (int i = 0; i < readerTasksConfigs.size(); i++) {
            Configuration taskConfig = Configuration.newDefault();
            taskConfig.set(CoreConstant.JOB_READER_NAME,
                    this.readerPluginName);
            taskConfig.set(CoreConstant.JOB_READER_PARAMETER,
                    readerTasksConfigs.get(i));
            taskConfig.set(CoreConstant.JOB_WRITER_NAME,
                    this.writerPluginName);
            taskConfig.set(CoreConstant.JOB_WRITER_PARAMETER,
                    writerTasksConfigs.get(i));

            if(transformerConfigs!=null && transformerConfigs.size()>0){
                taskConfig.set(CoreConstant.JOB_TRANSFORMER, transformerConfigs);
            }

            taskConfig.set(CoreConstant.TASK_ID, i);
            contentConfigs.add(taskConfig);
        }

        return contentConfigs;
    }

    /**
     * ???????????????????????????????????? 1. tasks???channel 2. channel???taskGroup
     * ?????????????????????????????????tasks?????????taskGroup??????????????????????????????channel????????????????????????channel
     * <p/>
     * example:
     * <p/>
     * ??????????????? ????????????1024??????????????????????????????????????????1000M/s?????????channel????????????3M/s???
     * ??????taskGroup????????????7???channel
     * <p/>
     * ????????? ???channel?????????1000M/s / 3M/s =
     * 333???????????????????????????????????????308?????????channel???3???tasks?????????25?????????channel???4???tasks???
     * ?????????taskGroup?????????333 / 7 =
     * 47...4??????????????????48???taskGroup???47??????????????????7???channel??????4?????????1???channel
     * <p/>
     * ??????????????????????????????4???channel???taskGroup????????????????????????
     * ???????????????3???tasks???4???channel?????????taskGroupId???0???
     * ???????????????????????????????????????task????????????????????????channel??????taskGroup???
     * <p/>
     * TODO delete it
     *
     * @param averTaskPerChannel
     * @param channelNumber
     * @param channelsPerTaskGroup
     * @return ??????taskGroup?????????????????????
     */
    @SuppressWarnings("serial")
    private List<Configuration> distributeTasksToTaskGroup(
            int averTaskPerChannel, int channelNumber,
            int channelsPerTaskGroup) {
        Validate.isTrue(averTaskPerChannel > 0 && channelNumber > 0
                        && channelsPerTaskGroup > 0,
                "??????channel?????????task???[averTaskPerChannel]???channel??????[channelNumber]?????????taskGroup?????????channel???[channelsPerTaskGroup]??????????????????");
        List<Configuration> taskConfigs = this.configuration
                .getListConfiguration(CoreConstant.DATAX_JOB_CONTENT);
        int taskGroupNumber = channelNumber / channelsPerTaskGroup;
        int leftChannelNumber = channelNumber % channelsPerTaskGroup;
        if (leftChannelNumber > 0) {
            taskGroupNumber += 1;
        }

        /**
         * ??????????????????taskGroup?????????????????????
         */
        if (taskGroupNumber == 1) {
            final Configuration taskGroupConfig = this.configuration.clone();
            /**
             * configure???clone??????clone???
             */
            taskGroupConfig.set(CoreConstant.DATAX_JOB_CONTENT, this.configuration
                    .getListConfiguration(CoreConstant.DATAX_JOB_CONTENT));
            taskGroupConfig.set(CoreConstant.DATAX_CORE_CONTAINER_TASKGROUP_CHANNEL,
                    channelNumber);
            taskGroupConfig.set(CoreConstant.DATAX_CORE_CONTAINER_TASKGROUP_ID, 0);
            return new ArrayList<Configuration>() {
                {
                    add(taskGroupConfig);
                }
            };
        }

        List<Configuration> taskGroupConfigs = new ArrayList<Configuration>();
        /**
         * ?????????taskGroup???content???????????????
         */
        for (int i = 0; i < taskGroupNumber; i++) {
            Configuration taskGroupConfig = this.configuration.clone();
            List<Configuration> taskGroupJobContent = taskGroupConfig
                    .getListConfiguration(CoreConstant.DATAX_JOB_CONTENT);
            taskGroupJobContent.clear();
            taskGroupConfig.set(CoreConstant.DATAX_JOB_CONTENT, taskGroupJobContent);

            taskGroupConfigs.add(taskGroupConfig);
        }

        int taskConfigIndex = 0;
        int channelIndex = 0;
        int taskGroupConfigIndex = 0;

        /**
         * ????????????taskGroup??????channel?????????????????????taskGroup
         */
        if (leftChannelNumber > 0) {
            Configuration taskGroupConfig = taskGroupConfigs.get(taskGroupConfigIndex);
            for (; channelIndex < leftChannelNumber; channelIndex++) {
                for (int i = 0; i < averTaskPerChannel; i++) {
                    List<Configuration> taskGroupJobContent = taskGroupConfig
                            .getListConfiguration(CoreConstant.DATAX_JOB_CONTENT);
                    taskGroupJobContent.add(taskConfigs.get(taskConfigIndex++));
                    taskGroupConfig.set(CoreConstant.DATAX_JOB_CONTENT,
                            taskGroupJobContent);
                }
            }

            taskGroupConfig.set(CoreConstant.DATAX_CORE_CONTAINER_TASKGROUP_CHANNEL,
                    leftChannelNumber);
            taskGroupConfig.set(CoreConstant.DATAX_CORE_CONTAINER_TASKGROUP_ID,
                    taskGroupConfigIndex++);
        }

        /**
         * ????????????????????????????????????channel??????taskGroupId??????
         */
        int equalDivisionStartIndex = taskGroupConfigIndex;
        for (; taskConfigIndex < taskConfigs.size()
                && equalDivisionStartIndex < taskGroupConfigs.size(); ) {
            for (taskGroupConfigIndex = equalDivisionStartIndex; taskGroupConfigIndex < taskGroupConfigs
                    .size() && taskConfigIndex < taskConfigs.size(); taskGroupConfigIndex++) {
                Configuration taskGroupConfig = taskGroupConfigs.get(taskGroupConfigIndex);
                List<Configuration> taskGroupJobContent = taskGroupConfig
                        .getListConfiguration(CoreConstant.DATAX_JOB_CONTENT);
                taskGroupJobContent.add(taskConfigs.get(taskConfigIndex++));
                taskGroupConfig.set(
                        CoreConstant.DATAX_JOB_CONTENT, taskGroupJobContent);
            }
        }

        for (taskGroupConfigIndex = equalDivisionStartIndex;
             taskGroupConfigIndex < taskGroupConfigs.size(); ) {
            Configuration taskGroupConfig = taskGroupConfigs.get(taskGroupConfigIndex);
            taskGroupConfig.set(CoreConstant.DATAX_CORE_CONTAINER_TASKGROUP_CHANNEL,
                    channelsPerTaskGroup);
            taskGroupConfig.set(CoreConstant.DATAX_CORE_CONTAINER_TASKGROUP_ID,
                    taskGroupConfigIndex++);
        }

        return taskGroupConfigs;
    }

    private void postJobReader() {
        classLoaderSwapper.setCurrentThreadClassLoader(LoadUtil.getJarLoader(
                PluginType.READER, this.readerPluginName));
        LOG.info("DataX Reader.Job [{}] do post work.",
                this.readerPluginName);
        this.jobReader.post();
        classLoaderSwapper.restoreCurrentThreadClassLoader();
    }

    private void postJobWriter() {
        classLoaderSwapper.setCurrentThreadClassLoader(LoadUtil.getJarLoader(
                PluginType.WRITER, this.writerPluginName));
        LOG.info("DataX Writer.Job [{}] do post work.",
                this.writerPluginName);
        this.jobWriter.post();
        classLoaderSwapper.restoreCurrentThreadClassLoader();
    }

    /**
     * ???????????????????????????????????????????????????????????????1????????????????????????????????????1?????????????????????
     *
     * @param
     */
    private void checkLimit() {
        Communication communication = super.getContainerCommunicator().collect();
        errorLimit.checkRecordLimit(communication);
        errorLimit.checkPercentageLimit(communication);
    }

    /**
     * ????????????hook
     */
    private void invokeHooks() {
        Communication comm = super.getContainerCommunicator().collect();
        HookInvoker invoker = new HookInvoker(CoreConstant.DATAX_HOME + "/hook", configuration, comm.getCounter());
        invoker.invokeAll();
    }
}
