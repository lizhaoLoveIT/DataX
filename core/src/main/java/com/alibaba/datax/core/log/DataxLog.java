package com.alibaba.datax.core.log;

/**
 * DESCRIPTION:
 * Author: ammar
 * Date:   2022/7/22
 * Time:   上午9:50
 */
public class DataxLog {
    // 任务id
    private Long id;
    // 任务启动时刻
    private Long startTimeStamp;
    // 任务结束时刻
    private Long endTimeStamp;
    // 任务总时耗 s
    private Long totalCosts;
    // 任务平均流量
    private Long byteSpeedPerSecond;
    // 记录写入速度
    private Long recordSpeedPerSecond;
    // 读出记录总数
    private Long totalReadRecords;
    // 读写失败总数
    private Long totalErrorRecords;
    // 成功记录总数 Transformer成功记录总数
    private Long transformerSucceedRecords = 0l;
    // 失败记录总数 Transformer失败记录总数
    private Long transformerFailedRecords = 0l;
    // 过滤记录总数 Transformer过滤记录总数
    private Long transformerFilterRecords = 0l;
    // 字节数
    private Long readSucceedBytes = 0l;
    // 转换总耗时
    private Long transferCosts = 0l;
    // 转换开始时间 废弃
    private Long endTransferTimeStamp = 0l;
    // 转换结束时间 废弃
    private Long startTransferTimeStamp = 0l;

    public DataxLog(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getStartTimeStamp() {
        return startTimeStamp;
    }

    public void setStartTimeStamp(Long startTimeStamp) {
        this.startTimeStamp = startTimeStamp;
    }

    public Long getEndTimeStamp() {
        return endTimeStamp;
    }

    public void setEndTimeStamp(Long endTimeStamp) {
        this.endTimeStamp = endTimeStamp;
    }

    public Long getTotalCosts() {
        return totalCosts;
    }

    public void setTotalCosts(Long totalCosts) {
        this.totalCosts = totalCosts;
    }

    public Long getByteSpeedPerSecond() {
        return byteSpeedPerSecond;
    }

    public void setByteSpeedPerSecond(Long byteSpeedPerSecond) {
        this.byteSpeedPerSecond = byteSpeedPerSecond;
    }

    public Long getRecordSpeedPerSecond() {
        return recordSpeedPerSecond;
    }

    public void setRecordSpeedPerSecond(Long recordSpeedPerSecond) {
        this.recordSpeedPerSecond = recordSpeedPerSecond;
    }

    public Long getTotalReadRecords() {
        return totalReadRecords;
    }

    public void setTotalReadRecords(Long totalReadRecords) {
        this.totalReadRecords = totalReadRecords;
    }

    public Long getTotalErrorRecords() {
        return totalErrorRecords;
    }

    public void setTotalErrorRecords(Long totalErrorRecords) {
        this.totalErrorRecords = totalErrorRecords;
    }

    public Long getTransformerSucceedRecords() {
        return transformerSucceedRecords;
    }

    public void setTransformerSucceedRecords(Long transformerSucceedRecords) {
        this.transformerSucceedRecords = transformerSucceedRecords;
    }

    public Long getTransformerFailedRecords() {
        return transformerFailedRecords;
    }

    public void setTransformerFailedRecords(Long transformerFailedRecords) {
        this.transformerFailedRecords = transformerFailedRecords;
    }

    public Long getTransformerFilterRecords() {
        return transformerFilterRecords;
    }

    public void setTransformerFilterRecords(Long transformerFilterRecords) {
        this.transformerFilterRecords = transformerFilterRecords;
    }

    public Long getReadSucceedBytes() {
        return readSucceedBytes;
    }

    public void setReadSucceedBytes(Long readSucceedBytes) {
        this.readSucceedBytes = readSucceedBytes;
    }

    public Long getEndTransferTimeStamp() {
        return endTransferTimeStamp;
    }

    public void setEndTransferTimeStamp(Long endTransferTimeStamp) {
        this.endTransferTimeStamp = endTransferTimeStamp;
    }

    public Long getStartTransferTimeStamp() {
        return startTransferTimeStamp;
    }

    public void setStartTransferTimeStamp(Long startTransferTimeStamp) {
        this.startTransferTimeStamp = startTransferTimeStamp;
    }

    public Long getTransferCosts() {
        return transferCosts;
    }

    public void setTransferCosts(Long transferCosts) {
        this.transferCosts = transferCosts;
    }
}

