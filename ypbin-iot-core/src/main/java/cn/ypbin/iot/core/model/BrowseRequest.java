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

import java.util.Objects;

/**
 * 目录浏览请求。
 *
 * @param rootAddress 起始节点地址；浏览根时为空字符串
 * @param maxDepth    最大深度，1 表示只取直接子节点
 * @param maxNodes    单次返回的最大节点数（防止一次拉回十万节点）
 * @author wenbin
 * @since 2026-09-13
 */
public record BrowseRequest(String rootAddress, int maxDepth, int maxNodes) {

    /** 默认最大深度。 */
    public static final int DEFAULT_MAX_DEPTH = 1;

    /** 默认最大节点数。 */
    public static final int DEFAULT_MAX_NODES = 1000;

    /**
     * 紧凑构造器：归一化默认值并做防御性校验。
     */
    public BrowseRequest {
        Objects.requireNonNull(rootAddress, "rootAddress must not be null");
        maxDepth = maxDepth <= 0 ? DEFAULT_MAX_DEPTH : maxDepth;
        maxNodes = maxNodes <= 0 ? DEFAULT_MAX_NODES : maxNodes;
    }

    /**
     * 以默认深度与上限浏览指定节点。
     *
     * @param rootAddress 起始节点地址
     * @return 浏览请求
     */
    public static BrowseRequest of(String rootAddress) {
        return new BrowseRequest(rootAddress, DEFAULT_MAX_DEPTH, DEFAULT_MAX_NODES);
    }
}
