/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.lookout.remote.report;

import com.alipay.lookout.common.log.LookoutLoggerFactory;
import com.alipay.lookout.core.config.LookoutConfig;
import com.alipay.lookout.remote.model.LookoutMeasurement;
import com.alipay.lookout.remote.report.support.ReportDecider;
import com.alipay.lookout.remote.report.support.http.HttpRequestProcessor;
import com.alipay.lookout.report.MetricObserver;
import com.google.common.base.Strings;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.StringEntity;
import org.slf4j.Logger;
import org.xerial.snappy.Snappy;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.alipay.lookout.core.config.LookoutConfig.*;

/**
 * Created by kevin.luy@alipay.com on 2017/2/7.
 */
public class HttpObserver implements MetricObserver<LookoutMeasurement> {
    private static final Logger        logger                     = LookoutLoggerFactory
                                                                      .getLogger(HttpObserver.class);
    static final String                APP_HEADER_NAME            = "app";
    public static final String         UTF_8                      = "utf-8";
    static final String                AGENT_URL_PATTERN          = "http://%s:%d/datas";
    public static final String         APPLICATION_OCTET_STREAM   = "application/octet-stream";
    public static final String         SNAPPY                     = "snappy";
    static final String                TEXT_MEDIATYPE             = "text/plain";

    private static final char          MSG_SPLITOR                = '\t';
    private final AddressService       addressService;

    AtomicInteger                      warningTimes               = new AtomicInteger(0);

    private final LookoutConfig        lookoutConfig;

    private final HttpRequestProcessor httpRequestProcessor;
    private final ReportDecider        reportDecider              = new ReportDecider();

    private final Map<String, String>  commonMetadata             = new HashMap<String, String>();

    private int                        innerAgentPort             = -1;

    //anti log repeatly, mark it
    private volatile boolean           enableReportAlreadyLogged  = false;
    private volatile boolean           disableReportAlreadyLogged = false;

    public HttpObserver(LookoutConfig lookoutConfig, AddressService addrService) {
        this.lookoutConfig = lookoutConfig;
        addressService = addrService;
        addressService.setAgentServerVip(lookoutConfig.getString(LOOKOUT_AGENT_HOST_ADDRESS));
        addressService.setAgentTestUrl(lookoutConfig.getString(LOOKOUT_AGENT_TEST_URL,
            System.getProperty(LOOKOUT_AGENT_TEST_URL)));
        httpRequestProcessor = new HttpRequestProcessor(reportDecider);

        //inner port
        innerAgentPort = lookoutConfig.getInt(LOOKOUT_AGENT_SERVER_PORT, -1);

        //add common metadatas
        if (lookoutConfig.containsKey(LookoutConfig.APP_NAME)) {
            commonMetadata.put(APP_HEADER_NAME, lookoutConfig.getString(LookoutConfig.APP_NAME));
        }
    }

    /**
     * 决定poll前都判断下
     *
     * @return enable
     */
    @Override
    public boolean isEnable() {
        if (!reportDecider.isPassed()) {
            if (reportDecider.stillSilent()) {
                logger.debug("observer is disable temporarily cause by agent silent order.");
                return false;
            }
            // ask agent ?
            Address agentAddress = addressService.getAgentServerHost();
            if (!isAgentAddressEmpty(agentAddress)) {
                try {
                    httpRequestProcessor.sendGetRequest(
                        new HttpGet(String.format(AGENT_URL_PATTERN, agentAddress.ip(),
                            agentAddress.port())), commonMetadata);
                } catch (Throwable e) {
                    logger.info(">>WARNING: check passed fail!agent:{}", agentAddress);
                }
                return false;//下次再重新询问是否passed
            }
        }

        boolean enable = addressService.isAgentServerExisted()
                         && lookoutConfig.getBoolean(LOOKOUT_AUTOPOLL_ENABLE, true);

        if (enable) {
            if (disableReportAlreadyLogged) {
                //disable already logged,allow log next time;
                disableReportAlreadyLogged = false;
            }
            //enable alread logged ? skip this condition.
            if (!enableReportAlreadyLogged) {
                enableReportAlreadyLogged = true;
                Address agentAddress = addressService.getAgentServerHost();
                logger.info(">>: enable report! agent:{}", agentAddress);
            }
        } else {
            if (enableReportAlreadyLogged) {
                enableReportAlreadyLogged = false;//allow log next time
            }
            if (!disableReportAlreadyLogged) {
                disableReportAlreadyLogged = true;
                logger.info(
                    ">>WARNING: disable report! agent existed:{},lookout.autopoll.enable:{}",
                    addressService.isAgentServerExisted(),
                    lookoutConfig.getBoolean(LOOKOUT_AUTOPOLL_ENABLE, true));
            }
        }

        return enable;
    }

    @Override
    public void update(List<LookoutMeasurement> measures, Map<String, String> metadata) {
        if (measures.isEmpty()) {
            return;
        }
        metadata.putAll(commonMetadata);
        logger.debug(">> metrics:\n{}\n", measures.toString());
        List<List<LookoutMeasurement>> batches = getBatches(measures,
            lookoutConfig.getInt(LOOKOUT_REPORT_BATCH_SIZE, DEFAULT_REPORT_BATCH_SIZE));
        for (List<LookoutMeasurement> batch : batches) {
            reportBatch(batch, metadata);
        }
    }

    private boolean isAgentAddressEmpty(Address agentAddress) {
        return agentAddress == null || Strings.isNullOrEmpty(agentAddress.ip());
    }

    /**
     * Get a list of all measurements and break them into batches.
     * @param ms measurement list
     * @param batchSize batch size
     * @return  measurement list
     */
    public List<List<LookoutMeasurement>> getBatches(List<LookoutMeasurement> ms, int batchSize) {
        List<List<LookoutMeasurement>> batches = new ArrayList();
        for (int i = 0; i < ms.size(); i += batchSize) {
            List<LookoutMeasurement> batch = ms.subList(i, Math.min(ms.size(), i + batchSize));
            batches.add(batch);
        }
        return batches;
    }

    private void reportBatch(List<LookoutMeasurement> measures, Map<String, String> metadata) {
        Address agentAddress = addressService.getAgentServerHost();
        if (isAgentAddressEmpty(agentAddress)) {
            //防止日志过多
            if (warningTimes.get() < 5) {
                logger
                    .warn(">>WARNING: lookout report fail! cause by :agent-host-address is required!");
                warningTimes.incrementAndGet();
            }
            return;//空地址，就不报告了.
        }
        //如果有汇报地址
        if (warningTimes.get() > 0) {
            warningTimes.getAndSet(0);
            logger.info("agent-host-address is found again!");
        }

        String text = buildReportText(measures);
        if (measures.size() < 200) {
            report2Agent(agentAddress, text, metadata);
        } else {
            reportSnappy2Agent(agentAddress, text, metadata);
        }
        //  Response response = httpClient.newCall(request).execute();
        //  String date = response.header("Date");
        //  recordClockSkew((date == null) ? 0L : date.toEpochMilli());
    }

    String buildReportText(List<LookoutMeasurement> measures) {
        Iterator<LookoutMeasurement> it = measures.iterator();
        StringBuilder sb = new StringBuilder();
        while (it.hasNext()) {
            if (sb.length() > 0) {
                sb.append(MSG_SPLITOR);
            }
            sb.append(it.next().toString());
        }
        return sb.toString();
    }

    void reportSnappy2Agent(Address agentAddress, String msg, Map<String, String> metadata) {
        try {
            HttpPost httpPost = new HttpPost(buildRealAgentServerURL(agentAddress));
            httpPost.setHeader(HttpHeaders.CONTENT_TYPE, APPLICATION_OCTET_STREAM);
            httpPost.setHeader(HttpHeaders.CONTENT_ENCODING, SNAPPY);
            byte[] compressed = Snappy.compress(msg, Charset.forName(UTF_8));
            httpPost.setEntity(new ByteArrayEntity(compressed));
            httpRequestProcessor.sendPostRequest(httpPost, metadata);
        } catch (Throwable e) {
            reportDecider.markUnpassed();
            if (e instanceof UnknownHostException || e instanceof ConnectException) {
                addressService.clearAddressCache();
                logger.info(">>WARNING: lookout agent address err?cause:{}", e.getMessage());
            }
            logger.info(">>WARNING: report to lookout agent fail!cause:{}", e.getMessage());
        }

    }

    void report2Agent(Address agentAddress, String msg, Map<String, String> metadata) {
        try {
            HttpPost httpPost = new HttpPost(buildRealAgentServerURL(agentAddress));
            httpPost.setHeader(HttpHeaders.CONTENT_TYPE, TEXT_MEDIATYPE);
            httpPost.setEntity(new StringEntity(msg));
            httpRequestProcessor.sendPostRequest(httpPost, metadata);
        } catch (Throwable e) {
            reportDecider.markUnpassed();
            if (e instanceof UnknownHostException || e instanceof ConnectException) {
                addressService.clearAddressCache();
                logger.info(">>WARNING: lookout agent:{} err?cause:{}", agentAddress.ip(),
                    e.getMessage());
            }
            logger.info(">>WARNING: report to lookout agent:{} fail!cause:{}", agentAddress.ip(),
                e.getMessage());
        }
    }

    String buildRealAgentServerURL(Address agentAddress) {
        return String.format(AGENT_URL_PATTERN, agentAddress.ip(),
            innerAgentPort > 0 ? innerAgentPort : agentAddress.port());
    }
}