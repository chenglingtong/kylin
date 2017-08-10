/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.rest.metrics;

import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.concurrent.ThreadSafe;

import org.apache.hadoop.metrics2.MetricsException;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.QueryContext;
import org.apache.kylin.metrics.MetricsManager;
import org.apache.kylin.metrics.lib.impl.RecordEvent;
import org.apache.kylin.metrics.query.CubeSegmentRecordEventWrapper;
import org.apache.kylin.metrics.query.QueryRecordEventWrapper;
import org.apache.kylin.metrics.query.RPCRecordEventWrapper;
import org.apache.kylin.rest.request.SQLRequest;
import org.apache.kylin.rest.response.SQLResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

/**
 * The entrance of metrics features.
 */
@ThreadSafe
public class QueryMetricsFacade {

    private static final Logger logger = LoggerFactory.getLogger(QueryMetricsFacade.class);
    private static final HashFunction hashFunc = Hashing.murmur3_128();

    private static boolean enabled = false;
    private static ConcurrentHashMap<String, QueryMetrics> metricsMap = new ConcurrentHashMap<String, QueryMetrics>();

    public static void init() {
        enabled = KylinConfig.getInstanceFromEnv().getQueryMetricsEnabled();
        if (!enabled)
            return;

        DefaultMetricsSystem.initialize("Kylin");
    }

    public static long getSqlHashCode(String sql) {
        return hashFunc.hashString(sql, Charset.forName("UTF-8")).asLong();
    }

    public static void updateMetrics(SQLRequest sqlRequest, SQLResponse sqlResponse) {
        if (!enabled)
            return;

        String projectName = sqlRequest.getProject();
        String cubeName = sqlResponse.getCube();

        update(getQueryMetrics("Server_Total"), sqlResponse);

        update(getQueryMetrics(projectName), sqlResponse);

        String cubeMetricName = projectName + ",sub=" + cubeName;
        update(getQueryMetrics(cubeMetricName), sqlResponse);

        /**
         * report query related metrics
         */
        final QueryContext.QueryStatisticsResult queryStatisticsResult = sqlResponse.getQueryStatistics();
        for (QueryContext.RPCStatistics entry : queryStatisticsResult.getRpcStatisticsList()) {
            RPCRecordEventWrapper rpcMetricsEventWrapper = new RPCRecordEventWrapper(
                    new RecordEvent(KylinConfig.getInstanceFromEnv().getKylinMetricsSubjectQueryRpcCall()));
            rpcMetricsEventWrapper.setWrapper(sqlRequest.getProject(), entry.getRealizationName(), entry.getRpcServer(),
                    entry.getException());
            rpcMetricsEventWrapper.setStats(entry.getCallTimeMs(), entry.getSkippedRows(), entry.getScannedRows(),
                    entry.getReturnedRows(), entry.getAggregatedRows());
            //For update rpc level related metrics
            MetricsManager.getInstance().update(rpcMetricsEventWrapper.getMetricsRecord());
        }
        long sqlHashCode = getSqlHashCode(sqlRequest.getSql());
        for (QueryContext.CubeSegmentStatisticsResult contextEntry : queryStatisticsResult
                .getCubeSegmentStatisticsResultList()) {
            QueryRecordEventWrapper queryMetricsEventWrapper = new QueryRecordEventWrapper(
                    new RecordEvent(KylinConfig.getInstanceFromEnv().getKylinMetricsSubjectQuery()));
            queryMetricsEventWrapper.setWrapper(sqlHashCode,
                    sqlResponse.isStorageCacheUsed() ? "CACHE" : contextEntry.getQueryType(), sqlRequest.getProject(),
                    contextEntry.getRealization(), contextEntry.getRealizationType(), sqlResponse.getThrowable());

            long totalStorageReturnCount = 0L;
            for (Map<String, QueryContext.CubeSegmentStatistics> cubeEntry : contextEntry.getCubeSegmentStatisticsMap()
                    .values()) {
                for (QueryContext.CubeSegmentStatistics segmentEntry : cubeEntry.values()) {
                    CubeSegmentRecordEventWrapper cubeSegmentMetricsEventWrapper = new CubeSegmentRecordEventWrapper(
                            new RecordEvent(KylinConfig.getInstanceFromEnv().getKylinMetricsSubjectQueryCube()));

                    cubeSegmentMetricsEventWrapper.setWrapper(sqlRequest.getProject(), segmentEntry.getCubeName(),
                            segmentEntry.getSegmentName(), segmentEntry.getSourceCuboidId(),
                            segmentEntry.getTargetCuboidId(), segmentEntry.getFilterMask());

                    cubeSegmentMetricsEventWrapper.setStats(segmentEntry.getCallCount(), segmentEntry.getCallTimeSum(),
                            segmentEntry.getCallTimeMax(), segmentEntry.getStorageSkippedRows(),
                            segmentEntry.getStorageScannedRows(), segmentEntry.getStorageReturnedRows(),
                            segmentEntry.getStorageAggregatedRows(), segmentEntry.isIfSuccess(),
                            1.0 / cubeEntry.size());

                    totalStorageReturnCount += segmentEntry.getStorageReturnedRows();
                    //For update cube segment level related query metrics
                    MetricsManager.getInstance().update(cubeSegmentMetricsEventWrapper.getMetricsRecord());
                }
            }
            queryMetricsEventWrapper.setStats(sqlResponse.getDuration(), sqlResponse.getResults().size(),
                    totalStorageReturnCount);
            //For update query level metrics
            MetricsManager.getInstance().update(queryMetricsEventWrapper.getMetricsRecord());
        }
    }

    private static void update(QueryMetrics queryMetrics, SQLResponse sqlResponse) {
        try {
            incrQueryCount(queryMetrics, sqlResponse);
            incrCacheHitCount(queryMetrics, sqlResponse);

            if (!sqlResponse.getIsException()) {
                queryMetrics.addQueryLatency(sqlResponse.getDuration());
                queryMetrics.addScanRowCount(sqlResponse.getTotalScanCount());
                queryMetrics.addResultRowCount(sqlResponse.getResults().size());
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
        }

    }

    private static void incrQueryCount(QueryMetrics queryMetrics, SQLResponse sqlResponse) {
        if (!sqlResponse.isHitExceptionCache() && !sqlResponse.getIsException()) {
            queryMetrics.incrQuerySuccessCount();
        } else {
            queryMetrics.incrQueryFailCount();
        }
        queryMetrics.incrQueryCount();
    }

    private static void incrCacheHitCount(QueryMetrics queryMetrics, SQLResponse sqlResponse) {
        if (sqlResponse.isStorageCacheUsed()) {
            queryMetrics.addCacheHitCount(1);
        }
    }

    private static QueryMetrics getQueryMetrics(String name) {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        int[] intervals = config.getQueryMetricsPercentilesIntervals();

        QueryMetrics queryMetrics = metricsMap.get(name);
        if (queryMetrics != null) {
            return queryMetrics;
        }

        synchronized (QueryMetricsFacade.class) {
            queryMetrics = metricsMap.get(name);
            if (queryMetrics != null) {
                return queryMetrics;
            }

            try {
                queryMetrics = new QueryMetrics(intervals).registerWith(name);
                metricsMap.put(name, queryMetrics);
                return queryMetrics;
            } catch (MetricsException e) {
                logger.warn(name + " register error: ", e);
            }
        }
        return queryMetrics;
    }
}