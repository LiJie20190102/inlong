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

package org.apache.inlong.agent.conf;

import org.apache.inlong.agent.constant.TaskConstants;
import org.apache.inlong.agent.pojo.TaskProfileDto;
import org.apache.inlong.agent.utils.AgentUtils;
import org.apache.inlong.common.enums.InstanceStateEnum;
import org.apache.inlong.common.enums.TaskStateEnum;
import org.apache.inlong.common.pojo.agent.DataConfig;

import com.google.gson.Gson;

import static org.apache.inlong.agent.constant.TaskConstants.TASK_RETRY;
import static org.apache.inlong.agent.constant.TaskConstants.TASK_STATE;

/**
 * job profile which contains details describing properties of one job.
 */
public class TaskProfile extends AbstractConfiguration {

    private static final Gson GSON = new Gson();

    /**
     * Get a TaskProfile from a DataConfig
     */
    public static TaskProfile convertToTaskProfile(DataConfig dataConfig) {
        if (dataConfig == null) {
            return null;
        }
        return TaskProfileDto.convertToTaskProfile(dataConfig);
    }

    public String getTaskId() {
        return get(TaskConstants.TASK_ID);
    }

    public String getCycleUnit() {
        return get(TaskConstants.TASK_CYCLE_UNIT);
    }

    public String getTimeOffset() {
        return get(TaskConstants.TASK_FILE_TIME_OFFSET);
    }

    public TaskStateEnum getState() {
        return TaskStateEnum.getTaskState(getInt(TASK_STATE));
    }

    public void setState(TaskStateEnum state) {
        setInt(TASK_STATE, state.ordinal());
    }

    public boolean isRetry() {
        return getBoolean(TASK_RETRY, false);
    }

    public String getTaskClass() {
        return get(TaskConstants.TASK_CLASS);
    }

    public void setTaskClass(String className) {
        set(TaskConstants.TASK_CLASS, className);
    }

    /**
     * parse json string to configuration instance.
     *
     * @return job configuration
     */
    public static TaskProfile parseJsonStr(String jsonStr) {
        TaskProfile conf = new TaskProfile();
        conf.loadJsonStrResource(jsonStr);
        return conf;
    }

    /**
     * check whether required keys exists.
     *
     * @return return true if all required keys exists else false.
     */
    @Override
    public boolean allRequiredKeyExist() {
        return hasKey(TaskConstants.TASK_ID) && hasKey(TaskConstants.TASK_SOURCE)
                && hasKey(TaskConstants.TASK_SINK) && hasKey(TaskConstants.TASK_CHANNEL)
                && hasKey(TaskConstants.TASK_GROUP_ID) && hasKey(TaskConstants.TASK_STREAM_ID)
                && hasKey(TaskConstants.TASK_CYCLE_UNIT);
    }

    public String toJsonStr() {
        return GSON.toJson(getConfigStorage());
    }

    public InstanceProfile createInstanceProfile(String instanceClass, String fileName, String dataTime,
            long fileUpdateTime) {
        InstanceProfile instanceProfile = InstanceProfile.parseJsonStr(toJsonStr());
        instanceProfile.setInstanceClass(instanceClass);
        instanceProfile.setInstanceId(fileName);
        instanceProfile.setDataTime(dataTime);
        instanceProfile.setCreateTime(AgentUtils.getCurrentTime());
        instanceProfile.setModifyTime(AgentUtils.getCurrentTime());
        instanceProfile.setState(InstanceStateEnum.DEFAULT);
        instanceProfile.setFileUpdateTime(fileUpdateTime);
        return instanceProfile;
    }
}
