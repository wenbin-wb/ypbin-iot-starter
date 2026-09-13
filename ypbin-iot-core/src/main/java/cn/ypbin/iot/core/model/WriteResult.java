/*
 * Copyright (c) 2024-present ypbin-iot-starter authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.ypbin.iot.core.model;

import java.time.Duration;
import java.util.List;

/**
 * 写结果：逐项状态，部分失败不异常完成。
 *
 * @param statuses 逐项状态
 * @param elapsed  耗时
 * @author wenbin
 * @since 2026-09-13
 */
public record WriteResult(List<PointWriteStatus> statuses, Duration elapsed) {

    /**
     * 紧凑构造器：保证集合非空且不可变。
     */
    public WriteResult {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        elapsed = elapsed == null ? Duration.ZERO : elapsed;
    }

    /**
     * 是否全部成功。
     *
     * @return 全部成功返回 {@code true}
     */
    public boolean allSuccess() {
        return statuses.stream().allMatch(PointWriteStatus::success);
    }

    /**
     * 失败项。
     *
     * @return 失败状态列表；全部成功时返回空列表
     */
    public List<PointWriteStatus> failures() {
        return statuses.stream().filter(status -> !status.success()).toList();
    }
}
