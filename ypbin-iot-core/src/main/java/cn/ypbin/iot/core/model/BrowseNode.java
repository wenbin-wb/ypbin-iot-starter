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

import java.util.Map;
import java.util.Objects;

/**
 * 目录浏览结果中的一个节点。
 *
 * @param address        节点地址
 * @param displayName    展示名
 * @param nodeClass      节点类别（协议原始语义，如 OPC UA 的 Object/Variable）
 * @param browseable     是否可继续向下浏览
 * @param attributes     附加属性
 * @author wenbin
 * @since 2026-09-13
 */
public record BrowseNode(
        PointAddress address,
        String displayName,
        String nodeClass,
        boolean browseable,
        Map<String, String> attributes) {

    /**
     * 紧凑构造器：校验必填项并归一化可空字段。
     */
    public BrowseNode {
        Objects.requireNonNull(address, "address must not be null");
        displayName = displayName == null ? address.raw() : displayName;
        nodeClass = nodeClass == null ? "" : nodeClass;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
