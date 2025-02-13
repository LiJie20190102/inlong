/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.agent.plugin.task.filecollect;

import org.apache.inlong.agent.conf.TaskProfile;
import org.apache.inlong.agent.plugin.utils.file.FilePathUtil;
import org.apache.inlong.agent.plugin.utils.file.FileTimeComparator;
import org.apache.inlong.agent.plugin.utils.file.Files;
import org.apache.inlong.agent.plugin.utils.file.NewDateUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.inlong.agent.constant.CommonConstants.DEFAULT_FILE_MAX_NUM;

/*
 * This class is mainly used for scanning log file that we want to read. We use this class at
 * inlong_agent recover process, the do and redo tasks and the current log file access when we deploy a
 * new data source.
 */
public class FileScanner {

    public static class BasicFileInfo {

        public String fileName;
        public String dataTime;

        public BasicFileInfo(String fileName, String dataTime) {
            this.fileName = fileName;
            this.dataTime = dataTime;
        }

    }

    private static final Logger logger = LoggerFactory.getLogger(FileScanner.class);

    public static List<BasicFileInfo> scanTaskBetweenTimes(TaskProfile conf, String originPattern, long failTime,
            long recoverTime, boolean isRetry) {
        String cycleUnit = conf.getCycleUnit();
        if (!isRetry) {
            failTime -= NewDateUtils.calcOffset(conf.getTimeOffset());
            recoverTime -= NewDateUtils.calcOffset(conf.getTimeOffset());
        }

        String startTime = NewDateUtils.millSecConvertToTimeStr(failTime, cycleUnit);
        String endTime = NewDateUtils.millSecConvertToTimeStr(recoverTime, cycleUnit);
        logger.info("task {} this scan time is between {} and {}.",
                new Object[]{conf.getTaskId(), startTime, endTime});

        return scanTaskBetweenTimes(conf.getCycleUnit(), originPattern, startTime, endTime);
    }

    /* Scan log files and create tasks between two times. */
    public static List<BasicFileInfo> scanTaskBetweenTimes(String cycleUnit, String originPattern, String startTime,
            String endTime) {
        List<Long> dateRegion = NewDateUtils.getDateRegion(startTime, endTime, cycleUnit);
        List<BasicFileInfo> infos = new ArrayList<BasicFileInfo>();
        for (Long time : dateRegion) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(time);
            String filename = NewDateUtils.replaceDateExpression(calendar, originPattern);
            ArrayList<String> allPaths = FilePathUtil.cutDirectory(filename);
            String firstDir = allPaths.get(0);
            String secondDir = allPaths.get(0) + File.separator + allPaths.get(1);
            ArrayList<String> fileList = getUpdatedOrNewFiles(firstDir, secondDir, filename, 3,
                    DEFAULT_FILE_MAX_NUM);
            for (String file : fileList) {
                // TODO the time is not YYYYMMDDHH
                String dataTime = NewDateUtils.millSecConvertToTimeStr(time, cycleUnit);
                BasicFileInfo info = new BasicFileInfo(file, dataTime);
                logger.info("scan new task fileName {} ,dataTime {}", file,
                        NewDateUtils.millSecConvertToTimeStr(time, cycleUnit));
                infos.add(info);
            }
        }
        return infos;
    }

    public static ArrayList<String> scanFile(int maxFileNum, String originPattern, long dataTime) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(dataTime);

        String filename = NewDateUtils.replaceDateExpression(calendar, originPattern);
        ArrayList<String> allPaths = FilePathUtil.cutDirectory(filename);
        String firstDir = allPaths.get(0);
        String secondDir = allPaths.get(0) + File.separator + allPaths.get(1);
        return getUpdatedOrNewFiles(firstDir, secondDir, filename, 3, maxFileNum);
    }

    private static ArrayList<String> getUpdatedOrNewFiles(String firstDir, String secondDir,
            String fileName, long depth, int maxFileNum) {
        ArrayList<String> ret = new ArrayList<String>();
        ArrayList<File> readyFiles = new ArrayList<File>();

        if (!new File(firstDir).isDirectory()) {
            return ret;
        }

        for (File pathname : Files.find(firstDir).yieldFilesAndDirectories()
                .recursive().withDepth((int) depth).withDirNameRegex(secondDir)
                .withFileNameRegex(fileName)) {
            if (readyFiles.size() >= maxFileNum) {
                break;
            }
            readyFiles.add(pathname);
        }
        // sort by last-modified time (older -> newer)
        Collections.sort(readyFiles, new FileTimeComparator());
        for (File f : readyFiles) {
            // System.out.println(f.getAbsolutePath());
            ret.add(f.getAbsolutePath());
        }
        return ret;
    }

    @SuppressWarnings("unused")
    private static ArrayList<String> getUpdatedOrNewFiles(String logFileName,
            int maxFileNum) {
        ArrayList<String> ret = new ArrayList<String>();
        ArrayList<String> directories = FilePathUtil
                .getDirectoryLayers(logFileName);
        String parentDir = directories.get(0) + File.separator
                + directories.get(1);

        Pattern pattern = Pattern.compile(directories.get(2),
                Pattern.CASE_INSENSITIVE);
        for (File file : new File(parentDir).listFiles()) {
            Matcher matcher = pattern.matcher(file.getName());
            if (matcher.matches() && ret.size() < maxFileNum) {
                ret.add(file.getAbsolutePath());
            }
        }
        return ret;
    }

    public static void main(String[] args) {

        ArrayList<String> fileList = FileScanner.getUpdatedOrNewFiles(
                "f:\\\\abc", "f:\\\\abc\\\\", "f:\\\\abc\\\\1.txt", 3, 100);
        // fileList = FileScanner.getUpdatedOrNewFiles("F:\\abc\\1.txt", 100);
        for (String fileName : fileList) {
            System.out.println(fileName);
        }
    }
}
