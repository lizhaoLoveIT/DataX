package com.alibaba.datax.core.log;

import com.alibaba.datax.core.Engine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DESCRIPTION:
 * Author: ammar
 * Date:   2022/7/22
 * Time:   上午10:05
 */
public class LogManager {
    private static final Logger LOG = LoggerFactory.getLogger(LogManager.class);
    private static final Map<String, DataxLog> map = new ConcurrentHashMap<>();
    public static DataxLog getDataxLogIfNullCreate(Long id) {
        if (id == null) return null;
        DataxLog dataxLog = map.get(id.toString());
        if (dataxLog == null) {
            dataxLog = new DataxLog(id);
            map.put(id.toString(), dataxLog);
        }
        return dataxLog;
    }
    public static void createDataxLog(Long id) {
        map.put(id.toString(), new DataxLog(id));
    }
    public static DataxLog getDataxLogAndRemove(Long id) {
        DataxLog dataxLog = map.get(id.toString());
        if (dataxLog == null) return null;
        map.remove(id);
        return dataxLog;
    }
}
