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

import org.apache.inlong.agent.conf.InstanceProfile;
import org.apache.inlong.agent.conf.TaskProfile;
import org.apache.inlong.agent.constant.TaskConstants;
import org.apache.inlong.agent.core.instance.ActionType;
import org.apache.inlong.agent.core.instance.InstanceAction;
import org.apache.inlong.agent.core.instance.InstanceManager;
import org.apache.inlong.agent.core.task.TaskAction;
import org.apache.inlong.agent.core.task.file.TaskManager;
import org.apache.inlong.agent.db.Db;
import org.apache.inlong.agent.plugin.file.Task;
import org.apache.inlong.agent.plugin.task.filecollect.FileScanner.BasicFileInfo;
import org.apache.inlong.agent.plugin.utils.file.FilePathUtil;
import org.apache.inlong.agent.plugin.utils.file.NewDateUtils;
import org.apache.inlong.agent.plugin.utils.file.PathDateExpression;
import org.apache.inlong.agent.state.State;
import org.apache.inlong.agent.utils.AgentUtils;
import org.apache.inlong.agent.utils.file.FileUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Watch directory, if new valid files are created, create jobs correspondingly.
 */
public class LogFileCollectTask extends Task {

    public static final String DEFAULT_FILE_INSTANCE = "org.apache.inlong.agent.plugin.instance.FileInstance";
    private static final Logger LOGGER = LoggerFactory.getLogger(LogFileCollectTask.class);
    private TaskProfile taskProfile;
    private Db basicDb;
    private TaskManager taskManager;
    private InstanceManager instanceManager;
    private final Map<String, WatchEntity> watchers = new ConcurrentHashMap<>();
    private final Set<String> watchFailedDirs = new HashSet<>();
    private final Map<String/* dataTime */, Map<String/* fileName */, InstanceProfile>> eventMap =
            new ConcurrentHashMap<>();
    public static final long DAY_TIMEOUT_INTERVAL = 2 * 24 * 3600 * 1000;
    public static final int CORE_THREAD_SLEEP_TIME = 1000;
    public static final int CORE_THREAD_MAX_GAP_TIME_MS = 60 * 1000;
    private boolean retry;
    private long startTime;
    private long endTime;
    private boolean initOK = false;
    private Set<String> originPatterns;
    private long lastScanTime = 0;
    public final long SCAN_INTERVAL = 1 * 60 * 1000;
    private volatile boolean runAtLeastOneTime = false;
    private volatile long coreThreadUpdateTime = 0;
    private volatile boolean running = false;

    @Override
    public void init(Object srcManager, TaskProfile taskProfile, Db basicDb) throws IOException {
        if (!isProfileValid(taskProfile)) {
            LOGGER.error("task profile invalid {}", taskProfile);
            return;
        }
        taskManager = (TaskManager) srcManager;
        commonInit(taskProfile, basicDb);
        if (retry) {
            retryInit();
        } else {
            watchInit();
        }
        initOK = true;
    }

    private void commonInit(TaskProfile taskProfile, Db basicDb) {
        this.taskProfile = taskProfile;
        this.basicDb = basicDb;
        retry = taskProfile.getBoolean(TaskConstants.TASK_RETRY, false);
        originPatterns = Stream.of(taskProfile.get(TaskConstants.FILE_DIR_FILTER_PATTERNS).split(","))
                .collect(Collectors.toSet());
        instanceManager = new InstanceManager(taskProfile.getTaskId(), taskProfile.getInt(TaskConstants.FILE_MAX_NUM),
                basicDb);
        try {
            instanceManager.start();
        } catch (Exception e) {
            LOGGER.error("start instance manager error {}", e.getMessage());
        }
    }

    private boolean isProfileValid(TaskProfile profile) {
        if (!profile.allRequiredKeyExist()) {
            LOGGER.error("task profile needs all required key");
            return false;
        }
        boolean ret =
                profile.hasKey(TaskConstants.FILE_DIR_FILTER_PATTERNS)
                        && profile.hasKey(TaskConstants.TASK_FILE_TIME_OFFSET)
                        && profile.hasKey(TaskConstants.FILE_MAX_NUM);
        if (!ret) {
            LOGGER.error("task profile needs file keys");
            return false;
        }
        if (profile.getBoolean(TaskConstants.TASK_RETRY, false)) {
            long startTime = profile.getLong(TaskConstants.TASK_START_TIME, 0);
            long endTime = profile.getLong(TaskConstants.TASK_END_TIME, 0);
            if (startTime == 0 || endTime == 0) {
                LOGGER.error("retry task time error start {} end {}", startTime, endTime);
                return false;
            }
        }
        return true;
    }

    private void retryInit() {
        startTime = taskProfile.getLong(TaskConstants.TASK_START_TIME, 0);
        endTime = taskProfile.getLong(TaskConstants.TASK_END_TIME, 0);
    }

    private void watchInit() {
        originPatterns.forEach((pathPattern) -> {
            addPathPattern(pathPattern);
        });
    }

    public void addPathPattern(String originPattern) {
        ArrayList<String> directories = FilePathUtil.getDirectoryLayers(originPattern);
        String basicStaticPath = directories.get(0);
        LOGGER.info("dataName {} watchPath {}", new Object[]{originPattern, basicStaticPath});
        /* Remember the failed watcher creations. */
        if (!new File(basicStaticPath).exists()) {
            LOGGER.warn(AgentErrMsg.DIRECTORY_NOT_FOUND_ERROR + basicStaticPath);
            watchFailedDirs.add(originPattern);
            return;
        }
        try {
            /*
             * When we construct the watch object, we should do some work with the data name, replace yyyy to 4 digits
             * regression, mm to 2 digits regression, also because of difference between java regular expression and
             * linux regular expression, we have to replace * to ., and replace . with \\. .
             */
            WatchService watchService = FileSystems.getDefault().newWatchService();
            WatchEntity entity = new WatchEntity(watchService, originPattern, taskProfile.getCycleUnit(),
                    taskProfile.getTimeOffset());
            entity.registerRecursively();
            watchers.put(originPattern, entity);
            watchFailedDirs.remove(originPattern);
        } catch (IOException e) {
            if (e.toString().contains("Too many open files") || e.toString().contains("打开的文件过多")) {
                LOGGER.error(AgentErrMsg.WATCH_DIR_ERROR + e.toString());
            } else {
                LOGGER.error(AgentErrMsg.WATCH_DIR_ERROR + e.toString(), e);
            }
        }
    }

    @Override
    public void destroy() {
        doChangeState(State.SUCCEEDED);
        if (instanceManager != null) {
            instanceManager.stop();
        }
        releaseWatchers(watchers);
    }

    private void releaseWatchers(Map<String, WatchEntity> watchers) {
        while (running) {
            if (AgentUtils.getCurrentTime() - coreThreadUpdateTime > CORE_THREAD_MAX_GAP_TIME_MS) {
                LOGGER.error("core thread not update, maybe it has broken");
                break;
            }
            AgentUtils.silenceSleepInMs(CORE_THREAD_SLEEP_TIME);
        }
        watchers.forEach((taskId, watcher) -> {
            try {
                watcher.getWatchService().close();
            } catch (IOException e) {
                LOGGER.error("close watch service failed taskId {}", e, taskId);
            }
        });
    }

    @Override
    public TaskProfile getProfile() {
        return taskProfile;
    }

    @Override
    public String getTaskId() {
        return taskProfile.getTaskId();
    }

    @Override
    public void addCallbacks() {

    }

    @Override
    public void run() {
        Thread.currentThread().setName("directory-task-core-" + getTaskId());
        running = true;
        while (!isFinished()) {
            coreThreadUpdateTime = AgentUtils.getCurrentTime();
            AgentUtils.silenceSleepInMs(CORE_THREAD_SLEEP_TIME);
            if (!initOK) {
                continue;
            }
            if (retry) {
                runForRetry();
            } else {
                runForNormal();
            }
        }
        running = false;
    }

    private void runForRetry() {
        if (!runAtLeastOneTime) {
            scanExistingFile();
            dealWithEvenMap();
            runAtLeastOneTime = true;
        }
        if (instanceManager.allInstanceFinished()) {
            LOGGER.info("retry task finished, send action to task manager, taskId {}", getTaskId());
            TaskAction action = new TaskAction(org.apache.inlong.agent.core.task.ActionType.FINISH, taskProfile);
            taskManager.submitAction(action);
            doChangeState(State.SUCCEEDED);
        }
    }

    private void runForNormal() {
        if (AgentUtils.getCurrentTime() - lastScanTime > SCAN_INTERVAL) {
            scanExistingFile();
            lastScanTime = AgentUtils.getCurrentTime();
        }
        runForWatching();
        dealWithEvenMap();
    }

    private void scanExistingFile() {
        originPatterns.forEach((originPattern) -> {
            List<BasicFileInfo> fileInfos = scanExistingFileByPattern(originPattern);
            LOGGER.info("scan {} get file count {}", originPattern, fileInfos.size());
            fileInfos.forEach((fileInfo) -> {
                addToEvenMap(fileInfo.fileName, fileInfo.dataTime);
            });
        });
    }

    private boolean isInEventMap(String fileName, String dataTime) {
        Map<String, InstanceProfile> fileToProfile = eventMap.get(dataTime);
        if (fileToProfile == null) {
            return false;
        }
        if (fileToProfile.get(fileName) == null) {
            return false;
        }
        return true;
    }

    private List<BasicFileInfo> scanExistingFileByPattern(String originPattern) {
        long startScanTime = startTime;
        long endScanTime = endTime;
        if (!retry) {
            long currentTime = System.currentTimeMillis();
            // only scan two cycle, like two hours or two days
            long offset = NewDateUtils.calcOffset("-2" + taskProfile.getCycleUnit());
            startScanTime = currentTime - offset;
            endScanTime = currentTime;
        }
        return FileScanner.scanTaskBetweenTimes(taskProfile, originPattern, startScanTime, endScanTime, retry);
    }

    private void runForWatching() {
        /* Deal with those failed watcher creation tasks. */
        Set<String> tmpWatchFailedDirs = new HashSet<>(watchFailedDirs);
        for (String originPattern : tmpWatchFailedDirs) {
            addPathPattern(originPattern);
        }
        /*
         * Visit the watchers to see if it gets any new file creation, if it exists and fits the file name pattern, add
         * it to the task list.
         */
        for (Map.Entry<String, WatchEntity> entry : watchers.entrySet()) {
            dealWithWatchEntity(entry.getKey());
        }
    }

    private void dealWithEvenMap() {
        removeTimeoutEven(eventMap, retry);
        for (Map.Entry<String, Map<String, InstanceProfile>> entry : eventMap.entrySet()) {
            Map<String, InstanceProfile> sameDataTimeEvents = entry.getValue();
            if (sameDataTimeEvents.isEmpty()) {
                return;
            }
            /*
             * Calculate whether the event needs to be processed at the current time based on its data time, business
             * cycle, and offset
             */
            String dataTime = entry.getKey();
            String shouldStartTime =
                    NewDateUtils.getShouldStartTime(dataTime, taskProfile.getCycleUnit(), taskProfile.getTimeOffset());
            String currentTime = getCurrentTime();
            if (currentTime.compareTo(shouldStartTime) >= 0) {
                LOGGER.info("submit now taskId {}, dataTime {}, currentTime {}, shouldStartTime {}",
                        new Object[]{getTaskId(), dataTime, currentTime, shouldStartTime});
                /* These codes will sort the FileCreationEvents by create time. */
                Set<InstanceProfile> sortedEvents = new TreeSet<>(sameDataTimeEvents.values());
                /* Check the file end with event creation time in asc order. */
                for (InstanceProfile sortEvent : sortedEvents) {
                    String fileName = sortEvent.getInstanceId();
                    InstanceProfile profile = sameDataTimeEvents.get(fileName);
                    InstanceAction action = new InstanceAction(ActionType.ADD, profile);
                    while (!instanceManager.submitAction(action)) {
                        LOGGER.error("instance manager action queue is full: taskId {}", instanceManager.getTaskId());
                        AgentUtils.silenceSleepInMs(CORE_THREAD_SLEEP_TIME);
                    }
                    sameDataTimeEvents.remove(fileName);
                }
            } else {
                LOGGER.info("submit later taskId {}, dataTime {}, currentTime {}, shouldStartTime {}",
                        new Object[]{getTaskId(), dataTime, currentTime, shouldStartTime});
            }
        }
    }

    private void removeTimeoutEven(Map<String, Map<String, InstanceProfile>> eventMap, boolean isRetry) {
        if (isRetry) {
            return;
        }
        for (Map.Entry<String, Map<String, InstanceProfile>> entry : eventMap.entrySet()) {
            /* If the data time of the event is within 2 days before (after) the current time, it is valid */
            String dataTime = entry.getKey();
            if (!NewDateUtils.isValidCreationTime(dataTime, DAY_TIMEOUT_INTERVAL)) {
                /* Remove it from memory map. */
                eventMap.remove(dataTime);
                LOGGER.warn("remove too old event from event map. dataTime {}", dataTime);
            }
        }
    }

    private String getCurrentTime() {
        SimpleDateFormat dateFormat = new SimpleDateFormat(NewDateUtils.DEFAULT_FORMAT);
        TimeZone timeZone = TimeZone.getTimeZone(NewDateUtils.DEFAULT_TIME_ZONE);
        dateFormat.setTimeZone(timeZone);
        return dateFormat.format(new Date(System.currentTimeMillis()));
    }

    public synchronized void dealWithWatchEntity(String originPattern) {
        WatchEntity entity = watchers.get(originPattern);
        if (entity == null) {
            LOGGER.error("Can't find the watch entity for originPattern: " + originPattern);
            return;
        }
        try {
            /* Get all creation events until all events are consumed. */
            for (int i = 0; i < entity.getTotalPathSize(); i++) {
                // maybe the watchService is closed ,but we catch this exception!
                final WatchKey key = entity.getWatchService().poll();
                if (key == null) {
                    return;
                }
                dealWithWatchKey(entity, key);
            }
        } catch (Exception e) {
            LOGGER.error("deal with creation event error: ", e);
        }
    }

    private void dealWithWatchKey(WatchEntity entity, WatchKey key) throws IOException {
        Path contextPath = entity.getPath(key);
        LOGGER.info("Find creation events in path: " + contextPath.toAbsolutePath());
        for (WatchEvent<?> watchEvent : key.pollEvents()) {
            Path child = resolvePathFromEvent(watchEvent, contextPath);
            if (child == null) {
                continue;
            }
            if (Files.isDirectory(child)) {
                LOGGER.info("The find creation event is triggered by a directory: " + child
                        .getFileName());
                entity.registerRecursively(child);
                continue;
            }
            handleFilePath(child, entity);
        }
        resetWatchKey(entity, key, contextPath);
    }

    private Path resolvePathFromEvent(WatchEvent<?> watchEvent, Path contextPath) {
        final Kind<?> kind = watchEvent.kind();
        /*
         * Can't simply continue when it detects that an event maybe ignored.
         */
        if (kind == StandardWatchEventKinds.OVERFLOW) {
            LOGGER.error("An event is unclear and lost");
            /*
             * TODO: should we do a full scan to avoid lost events?
             */
            return null;
        }
        final WatchEvent<Path> watchEventPath = (WatchEvent<Path>) watchEvent;
        final Path eventPath = watchEventPath.context();
        /*
         * Must resolve, otherwise we can't get the file attributes.
         */
        return contextPath.resolve(eventPath);
    }

    private void handleFilePath(Path filePath, WatchEntity entity) {
        String newFileName = filePath.toFile().getAbsolutePath();
        LOGGER.info("[New File] {} {}", newFileName, entity.getOriginPattern());
        Matcher matcher = entity.getPattern().matcher(newFileName);
        if (matcher.matches() || matcher.lookingAt()) {
            LOGGER.info("[Matched File] {} {}", newFileName, entity.getOriginPattern());
            String dataTime = getDataTimeFromFileName(newFileName, entity.getOriginPattern(),
                    entity.getDateExpression());
            if (!checkFileNameForTime(newFileName, entity)) {
                LOGGER.error(AgentErrMsg.FILE_ERROR + "File Timeout {} {}", newFileName, dataTime);
                return;
            }
            addToEvenMap(newFileName, dataTime);
        }
    }

    private void addToEvenMap(String fileName, String dataTime) {
        if (isInEventMap(fileName, dataTime)) {
            return;
        }
        Long fileUpdateTime = FileUtils.getFileLastModifyTime(fileName);
        if (!instanceManager.shouldAddAgain(fileName, fileUpdateTime)) {
            return;
        }
        Map<String, InstanceProfile> sameDataTimeEvents = eventMap.computeIfAbsent(dataTime,
                mapKey -> new ConcurrentHashMap<>());
        boolean containsInMemory = sameDataTimeEvents.containsKey(fileName);
        if (containsInMemory) {
            LOGGER.error("should not happen! may be {} has been deleted and add again", fileName);
            return;
        }
        InstanceProfile instanceProfile = taskProfile.createInstanceProfile(DEFAULT_FILE_INSTANCE,
                fileName, dataTime, fileUpdateTime);
        sameDataTimeEvents.put(fileName, instanceProfile);
    }

    private boolean checkFileNameForTime(String newFileName, WatchEntity entity) {
        /* Get the data time for this file. */
        PathDateExpression dateExpression = entity.getDateExpression();
        if (dateExpression.getLongestDatePattern().length() != 0) {
            String dataTime = getDataTimeFromFileName(newFileName, entity.getOriginPattern(), dateExpression);
            LOGGER.info("file {} ,fileTime {}", newFileName, dataTime);
            if (!NewDateUtils.isValidCreationTime(dataTime, entity.getCycleUnit(),
                    entity.getTimeOffset())) {
                return false;
            }
        }
        return true;
    }

    private String getDataTimeFromFileName(String fileName, String originPattern, PathDateExpression dateExpression) {
        /*
         * TODO: what if this file doesn't have any date pattern regex.
         *
         * For this case, we can simple think that the next file creation means the last task of this conf should finish
         * reading and start reading this new file.
         */
        // Extract data time from file name
        String fileTime = NewDateUtils.getDateTime(fileName, originPattern, dateExpression);

        /**
         * Replace any non-numeric characters in the file time
         * such as 2015-09-16_00 replace with 2015091600
         */
        return fileTime.replaceAll("\\D", "");
    }

    private void resetWatchKey(WatchEntity entity, WatchKey key, Path contextPath) {
        key.reset();
        /*
         * Register a new watch service on the path if the old watcher is invalid.
         */
        if (!key.isValid()) {
            LOGGER.warn(AgentErrMsg.WATCHER_INVALID + "Invalid Watcher {}",
                    contextPath.getFileName());
            try {
                WatchService oldService = entity.getWatchService();
                oldService.close();
                WatchService watchService = FileSystems.getDefault().newWatchService();
                entity.clearKeys();
                entity.clearPathToKeys();
                entity.setWatchService(watchService);
                entity.registerRecursively();
            } catch (IOException e) {
                LOGGER.error("Restart a new watcher runs into error: ", e);
            }
        }
    }
}
