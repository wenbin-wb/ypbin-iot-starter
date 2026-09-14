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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(OpcUaBrowser.class);

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
        return Stages.normalize(browseLevel(root, 0, maxDepth, maxNodes, collected)
                .thenApply(ignored -> {
                    if (collected.size() >= maxNodes) {
                        context.log(LogLevel.WARN, OpcUaAdapter.MSG_BROWSE_TRUNCATED,
                                String.valueOf(maxNodes));
                    }
                    return List.copyOf(collected);
                }));
    }

    /**
     * 递归浏览一个层级。
     *
     * <p><b>为什么必须是递归组合而不是同步 while 循环</b>：子节点是在<b>异步回调里</b>入队的，
     * 同步循环会在任何回调执行前就把队列抽干 —— 结果是 {@code maxDepth} 完全失效、
     * 永远只浏览根的直接子节点（该缺陷已被实证）。</p>
     *
     * <p>同层子节点按<b>顺序</b>递归而非并发：真实服务器对并发 Browse 有限流，
     * 一次拉爆会让整个浏览失败或拖慢服务器。</p>
     */
    private CompletableFuture<Void> browseLevel(NodeId nodeId, int depth, int maxDepth, int maxNodes,
            List<BrowseNode> collected) {
        if (depth >= maxDepth || collected.size() >= maxNodes) {
            return CompletableFuture.completedFuture(null);
        }
        return client.getAddressSpace().browseAsync(nodeId)
                .orTimeout(Math.max(1L, context.settings().requestTimeout().toMillis()),
                        TimeUnit.MILLISECONDS)
                .handle((references, error) -> {
                    if (error != null) {
                        // 单个节点浏览失败不中断整次浏览：记指标后继续其余分支
                        context.metrics().recordError(OpcUaAdapter.MSG_BROWSE_FAILED);
                        log.debug("[ypbin-iot] opcua browse failed for node {}", nodeId, error);
                        return List.<ReferenceDescription>of();
                    }
                    return references == null ? List.<ReferenceDescription>of() : references;
                })
                .thenCompose(references -> {
                    List<NodeId> children = new ArrayList<>();
                    for (ReferenceDescription reference : references) {
                        if (collected.size() >= maxNodes) {
                            break;
                        }
                        NodeId child = reference.getNodeId() == null ? null
                                : reference.getNodeId().toNodeId(client.getNamespaceTable()).orElse(null);
                        if (child == null) {
                            // 远端命名空间未注册时无法解析为本地 NodeId：不能静默丢，要留痕
                            context.metrics().recordError(OpcUaAdapter.MSG_BROWSE_UNRESOLVED);
                            continue;
                        }
                        boolean browseable = isBrowseable(reference.getNodeClass());
                        collected.add(new BrowseNode(PointAddress.of(OpcUaNodeIdCodec.format(child)),
                                reference.getBrowseName() == null ? null
                                        : reference.getBrowseName().getName(),
                                reference.getNodeClass() == null ? "" : reference.getNodeClass().name(),
                                browseable,
                                attributes(reference)));
                        if (browseable && depth + 1 < maxDepth) {
                            children.add(child);
                        }
                    }
                    CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                    for (NodeId child : children) {
                        chain = chain.thenCompose(ignored ->
                                browseLevel(child, depth + 1, maxDepth, maxNodes, collected));
                    }
                    return chain;
                });
    }

    /**
     * 该节点类型是否可继续下钻。
     *
     * <p>不能简单用 {@code getNodeClass() != null}（该字段必填，恒为真）——
     * 那会让每个变量都被当成可浏览节点，一旦递归就会把变量的属性也拉进来。</p>
     */
    private static boolean isBrowseable(NodeClass nodeClass) {
        return nodeClass == NodeClass.Object || nodeClass == NodeClass.View;
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

}
