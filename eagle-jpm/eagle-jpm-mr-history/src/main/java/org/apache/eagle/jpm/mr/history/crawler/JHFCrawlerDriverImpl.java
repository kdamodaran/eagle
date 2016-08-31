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

package org.apache.eagle.jpm.mr.history.crawler;

import org.apache.eagle.jpm.mr.history.MRHistoryJobConfig;
import org.apache.eagle.jpm.mr.history.parser.EagleJobStatus;
import org.apache.eagle.jpm.mr.history.zkres.JobHistoryZKStateLCM;
import org.apache.eagle.jpm.mr.historyentity.JobCountEntity;
import org.apache.eagle.jpm.util.JobIdFilter;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.eagle.service.client.IEagleServiceClient;
import org.apache.eagle.service.client.impl.EagleServiceClientImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * single thread crawling per driver.
 * multiple drivers can achieve parallelism.
 *
 */
public class JHFCrawlerDriverImpl implements JHFCrawlerDriver {
    private static final Logger LOG = LoggerFactory.getLogger(JHFCrawlerDriverImpl.class);

    private static final int SLEEP_SECONDS_WHILE_QUEUE_IS_EMPTY = 10;
    private static final String FORMAT_JOB_PROCESS_DATE = "%4d%02d%02d";
    private static final Pattern PATTERN_JOB_PROCESS_DATE = Pattern.compile("([0-9]{4})([0-9]{2})([0-9]{2})");

    private static final int INITIALIZED = 0x0;
    private static final int TODAY = 0x1;
    private static final int BEFORETODAY = 0x10;
    private static final int PROCESSED_JOB_KEEP_DAYS = 5;

    private int flag = INITIALIZED; // 0 not set, 1 TODAY, 2 BEFORETODAY
    private Deque<Pair<Long, String>> processQueue = new LinkedList<>();
    private Set<String> processedJobFileNames = new HashSet<>();

    private final JobProcessDate processDate = new JobProcessDate();
    private boolean dryRun;
    private JHFInputStreamCallback reader;
    protected boolean zeroBasedMonth = true;

    private JobHistoryZKStateLCM zkStateLcm;
    private JobHistoryLCM jhfLCM;
    private JobIdFilter jobFilter;
    private int partitionId;
    private TimeZone timeZone;
    private MRHistoryJobConfig.EagleServiceConfig eagleServiceConfig;
    private MRHistoryJobConfig.JobExtractorConfig jobExtractorConfig;

    public JHFCrawlerDriverImpl(MRHistoryJobConfig.EagleServiceConfig eagleServiceConfig,
                                MRHistoryJobConfig.JobExtractorConfig jobExtractorConfig,
                                MRHistoryJobConfig.ControlConfig controlConfig, JHFInputStreamCallback reader,
                                JobHistoryZKStateLCM zkStateLCM,
                                JobHistoryLCM historyLCM, JobIdFilter jobFilter, int partitionId) throws Exception {
        this.eagleServiceConfig = eagleServiceConfig;
        this.jobExtractorConfig = jobExtractorConfig;
        this.zeroBasedMonth = controlConfig.zeroBasedMonth;
        this.dryRun = controlConfig.dryRun;
        if (this.dryRun)  {
            LOG.info("this is a dry run");
        }
        this.reader = reader;
        jhfLCM = historyLCM;//new JobHistoryDAOImpl(jobHistoryConfig);
        this.zkStateLcm = zkStateLCM;
        this.partitionId = partitionId;
        this.jobFilter = jobFilter;
        timeZone = TimeZone.getTimeZone(controlConfig.timeZone);
    }

    /**
     * <br>
     * 1. if queue is not empty <br>
     * 1.1 dequeue and process one job file <br>
     * 1.2 store processed job file and also cache it to processedJobFileNames
     * 2. if queue is empty <br>
     * 2.0 if flag is BEFORETODAY, then write currentProcessedDate to jobProcessedDate as this day's data are all processed <br>
     * 2.1 crawl that day's job file list <br>
     * 2.2 filter out those jobID which are in _processedJobIDs keyed by
     * currentProcessedDate <br>
     * 2.3 put available file list to processQueue and then go to step 1
     */
    @Override
    public long crawl() throws Exception {
        LOG.info("queue size is " + processQueue.size());
        while (processQueue.isEmpty()) {
            // read lastProcessedDate only when it's initialized
            if (flag == INITIALIZED) {
                readAndCacheLastProcessedDate();
            }
            if (flag == BEFORETODAY) {
                updateProcessDate();
                clearProcessedJobFileNames();
            }
            if (flag != TODAY) { // advance one day if initialized or BEFORE today
                advanceOneDay();
            }

            if (isToday()) {
                flag = TODAY;
            } else {
                flag = BEFORETODAY;
            }

            List<String> serialNumbers = jhfLCM.readSerialNumbers(this.processDate.year, getActualMonth(processDate.month), this.processDate.day);
            List<Pair<Long, String>> allJobHistoryFiles = new LinkedList<>();
            for (String serialNumber : serialNumbers) {
                List<Pair<Long, String>> jobHistoryFiles = jhfLCM.readFileNames(
                        this.processDate.year,
                        getActualMonth(processDate.month),
                        this.processDate.day,
                        Integer.parseInt(serialNumber));
                LOG.info("total number of job history files " + jobHistoryFiles.size());
                for (Pair<Long, String> jobHistoryFile : jobHistoryFiles) {
                    if (jobFilter.accept(jobHistoryFile.getRight()) && !fileProcessed(jobHistoryFile.getRight())) {
                        allJobHistoryFiles.add(jobHistoryFile);
                    }
                }
                jobHistoryFiles.clear();
                LOG.info("after filtering, number of job history files " + processQueue.size());
            }

            Collections.sort(allJobHistoryFiles,
                (o1, o2) -> {
                    if (o1.getLeft() > o2.getLeft()) {
                        return 1;
                    } else if (o1.getLeft() == o2.getLeft()) {
                        return 0;
                    } else {
                        return -1;
                    }
                }
            );
            for (Pair<Long, String> jobHistoryFile : allJobHistoryFiles) {
                processQueue.add(jobHistoryFile);
            }

            allJobHistoryFiles.clear();

            if (processQueue.isEmpty()) {
                Thread.sleep(SLEEP_SECONDS_WHILE_QUEUE_IS_EMPTY * 1000);
            } else {
                LOG.info("queue size after populating is now : " + processQueue.size());
            }
        }
        // start to process job history file
        Pair<Long, String> item = processQueue.pollFirst();
        String jobHistoryFile = item.getRight();
        if (jobHistoryFile == null) { // terminate this round of crawling when the queue is empty
            LOG.info("process queue is empty, ignore this round");
            return -1;
        }
        // get serialNumber from job history file name
        Pattern p = Pattern.compile("^job_[0-9]+_([0-9]+)[0-9]{3}[_-]{1}");
        Matcher m = p.matcher(jobHistoryFile);
        String serialNumber;
        if (m.find()) {
            serialNumber = m.group(1);
        } else {
            LOG.warn("illegal job history file name : " + jobHistoryFile);
            return -1;
        }
        if (!dryRun) {
            jhfLCM.readFileContent(
                    processDate.year,
                    getActualMonth(processDate.month),
                    processDate.day,
                    Integer.valueOf(serialNumber),
                    jobHistoryFile,
                reader);
        }
        zkStateLcm.addProcessedJob(String.format(FORMAT_JOB_PROCESS_DATE,
                this.processDate.year,
                this.processDate.month + 1,
                this.processDate.day),
                jobHistoryFile);
        processedJobFileNames.add(jobHistoryFile);

        flushJobCount();
        Long modifiedTime = item.getLeft();
        return modifiedTime;
    }

    private void updateProcessDate() throws Exception {
        String line = String.format(FORMAT_JOB_PROCESS_DATE, this.processDate.year,
                this.processDate.month + 1, this.processDate.day);
        zkStateLcm.updateProcessedDate(partitionId, line);
    }

    private int getActualMonth(int month) {
        return zeroBasedMonth ? processDate.month : processDate.month + 1;
    }

    private static class JobProcessDate {
        public int year;
        public int month; // 0 based month
        public int day;
    }

    private void clearProcessedJobFileNames() {
        processedJobFileNames.clear();
    }

    private void readAndCacheLastProcessedDate() throws Exception {
        String lastProcessedDate = zkStateLcm.readProcessedDate(partitionId);
        Matcher m = PATTERN_JOB_PROCESS_DATE.matcher(lastProcessedDate);
        if (m.find() && m.groupCount() == 3) {
            this.processDate.year = Integer.parseInt(m.group(1));
            this.processDate.month = Integer.parseInt(m.group(2)) - 1; // zero based month
            this.processDate.day = Integer.parseInt(m.group(3));
        } else {
            throw new IllegalStateException("job lastProcessedDate must have format YYYYMMDD " + lastProcessedDate);
        }

        GregorianCalendar cal = new GregorianCalendar(timeZone);
        cal.set(this.processDate.year, this.processDate.month, this.processDate.day, 0, 0, 0);
        cal.add(Calendar.DATE, 1);
        List<String> list = zkStateLcm.readProcessedJobs(String.format(FORMAT_JOB_PROCESS_DATE, cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)));
        if (list != null) {
            this.processedJobFileNames = new HashSet<>(list);
        }
    }

    private void flushJobCount() throws Exception {
        List<Pair<String, String>> jobs = zkStateLcm.getProcessedJobs(
            String.format(FORMAT_JOB_PROCESS_DATE, this.processDate.year, this.processDate.month + 1, this.processDate.day)
        );
        JobCountEntity entity = new JobCountEntity();
        entity.setTotal(jobs.size());
        entity.setFail(0);
        jobs.stream().filter(job -> !job.getRight().equals(EagleJobStatus.SUCCEEDED.toString())).forEach(
            job -> entity.setFail(1 + entity.getFail())
        );

        IEagleServiceClient client = new EagleServiceClientImpl(
            eagleServiceConfig.eagleServiceHost,
            eagleServiceConfig.eagleServicePort,
            eagleServiceConfig.username,
            eagleServiceConfig.password);


        GregorianCalendar cal = new GregorianCalendar(this.processDate.year, this.processDate.month, this.processDate.day, 0, 0, 0);
        cal.setTimeZone(timeZone);
        entity.setTimestamp(cal.getTimeInMillis());
        @SuppressWarnings("serial")
        Map<String, String> baseTags = new HashMap<String, String>() {
            {
                put("site", jobExtractorConfig.site);
            }
        };
        entity.setTags(baseTags);
        List<JobCountEntity> entities = new ArrayList<>();
        entities.add(entity);

        LOG.info("start flushing entities of total number " + entities.size());
        client.create(entities);
        LOG.info("finish flushing entities of total number " + entities.size());
        client.getJerseyClient().destroy();
        client.close();
    }

    private void advanceOneDay() throws Exception {
        //flushJobCount();
        GregorianCalendar cal = new GregorianCalendar(timeZone);
        cal.set(this.processDate.year, this.processDate.month, this.processDate.day, 0, 0, 0);
        cal.add(Calendar.DATE, 1);
        this.processDate.year = cal.get(Calendar.YEAR);
        this.processDate.month = cal.get(Calendar.MONTH);
        this.processDate.day = cal.get(Calendar.DAY_OF_MONTH);

        try {
            clearProcessedJob(cal);
        } catch (Exception e) {
            LOG.error("failed to clear processed job ", e);
        }

    }

    private void clearProcessedJob(Calendar cal) {
        // clear all already processed jobs some days before current processing date (PROCESSED_JOB_KEEP_DAYS)
        cal.add(Calendar.DATE, -1 - PROCESSED_JOB_KEEP_DAYS);
        String line = String.format(FORMAT_JOB_PROCESS_DATE, cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
        zkStateLcm.truncateProcessedJob(line);
    }

    private boolean isToday() {
        GregorianCalendar today = new GregorianCalendar(timeZone);

        if (today.get(Calendar.YEAR) == this.processDate.year
                && today.get(Calendar.MONTH) == this.processDate.month
                && today.get(Calendar.DAY_OF_MONTH) == this.processDate.day) {
            return true;
        }
        return false;
    }

    /**
     * check if this file was already processed.
     *
     * @param fileName
     * @return
     */
    private boolean fileProcessed(String fileName) {
        if (processedJobFileNames.contains(fileName)) {
            return true;
        }
        return false;
    }
}
