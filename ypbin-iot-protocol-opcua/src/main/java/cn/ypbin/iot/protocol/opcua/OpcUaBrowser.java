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
package cn.ypbin.iot.protocol.opcua;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.LogLevel;
import cn.ypbin.iot.core.model.BrowseNode;
import cn.ypbin.iot.core.model.BrowseRequest;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.protocol.BrowseExtension;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.util.Stages;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;

/**
 * OPC UA 地址空间浏览。
 *
 * <p><b>这是本仓 {@code BrowseExtension} 扩展点的第一个真实实现</b>：OPC UA 的地址空间是自描述的，
 * 「有哪些点位」可以直接问服务器，不需要人工维护点位表——这正是调试期最省事的能力。</p>
 *
 * <p><b>必须显式限深限额</b>：真实服务器的地址空间可能包含数万节点，
 * 无限递归浏览会一次性拉爆内存与服务器负载。因此 {@code maxDepth} 与 {@code maxNodes}
 * 在遍历前就确定上限，达到上限即停止（并记录警告），而不是「尽力而为」地继续。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
final class OpcUaBrowser implements BrowseExtension {

    private final OpcUaClient client;

    private final AdapterContext context;

    OpcUaBrowser(OpcUaClient client, AdapterContext context) {
        this.client = client;
        this.context = context;
    }

    /**
     * 是否支持该扩展类型。
     *
     * @param extensionType 扩展类型
     * @return 支持返回 {@code true}
     */
    static boolean supports(Class<?> extensionType) {
        return BrowseExtension.class.isAssignableFrom(extensionType);
    }

    @Override
    public ProtocolCode protocol() {
        return OpcUaAdapter.PROTOCOL_CODE;
    }

    @Override
    public CompletionStage<List<BrowseNode>> browse(BrowseRequest request) {
        NodeId root;
        try {
            root = OpcUaNodeIdCodec.parse(request.rootAddress());
        } catch (RuntimeException ex) {
            return Stages.failed(ex);
        }
        int maxDepth = request.maxDepth();
        int maxNodes = request.maxNodes();
        List<BrowseNode> collected = new ArrayList<>();
        Deque<Level> frontier = new ArrayDeque<>();
        frontier.add(new Level(root, 0));
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        while (!frontier.isEmpty()) {
            Level level = frontier.poll();
            chain = chain.thenCompose(ignored -> client.getAddressSpace()
                    .browseAsync(level.nodeId())
                    .orTimeout(Math.max(1L, context.settings().requestTimeout().toMillis()),
                            java.util.concurrent.TimeUnit.MILLISECONDS)
                    .handle((references, error) -> {
                        if (error != null) {
                            // 单个节点浏览失败不应中断整次浏览：记录后继续其余分支
                            context.metrics().recordError(OpcUaAdapter.MSG_BROWSE_FAILED);
                            return null;
                        }
                        if (references == null) {
                            return null;
                        }
                        for (ReferenceDescription reference : references) {
                            if (collected.size() >= maxNodes) {
                                return null;
                            }
                            // ExpandedNodeId 需要命名空间表才能解析为本地 NodeId
                            NodeId child = reference.getNodeId() == null ? null
                                    : reference.getNodeId().toNodeId(client.getNamespaceTable()).orElse(null);
                            if (child == null) {
                                continue;
                            }
                            boolean browseable = reference.getNodeClass() != null;
                            collected.add(new BrowseNode(PointAddress.of(OpcUaNodeIdCodec.format(child)),
                                    reference.getBrowseName() == null ? null
                                            : reference.getBrowseName().getName(),
                                    reference.getNodeClass() == null ? "" : reference.getNodeClass().name(),
                                    browseable,
                                    attributes(reference)));
                            if (browseable && level.depth() + 1 < maxDepth) {
                                frontier.add(new Level(child, level.depth() + 1));
                            }
                        }
                        return null;
                    }));
        }
        return Stages.normalize(chain.thenApply(ignored -> {
            if (collected.size() >= maxNodes) {
                context.log(LogLevel.WARN, OpcUaAdapter.MSG_BROWSE_TRUNCATED,
                        String.valueOf(maxNodes));
            }
            return List.copyOf(collected);
        }));
    }

    private static Map<String, String> attributes(ReferenceDescription reference) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("referenceType", reference.getReferenceTypeId() == null ? ""
                : reference.getReferenceTypeId().toParseableString());
        attributes.put("typeDefinition", reference.getTypeDefinition() == null ? ""
                : reference.getTypeDefinition().toParseableString());
        attributes.put("displayName", reference.getDisplayName() == null ? ""
                : reference.getDisplayName().getText());
        return Map.copyOf(attributes);
    }

    /**
     * 浏览层级（广度优先遍历用）。
     *
     * @param nodeId 节点
     * @param depth  深度
     * @author wenbin
     * @since 2026-09-14
     */
    private record Level(NodeId nodeId, int depth) {
    }
}
