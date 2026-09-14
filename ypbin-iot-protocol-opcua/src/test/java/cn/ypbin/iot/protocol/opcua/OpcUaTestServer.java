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

import java.io.IOException;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.EndpointConfig;
import org.eclipse.milo.opcua.sdk.server.ManagedNamespace;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.AttributeObserver;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.transport.server.OpcServerTransportFactory;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransport;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransportConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OPC UA 测试服务端（自建地址空间）。
 *
 * <p><b>顺序很重要</b>：上一轮先写适配器再想验证，结果四条正向路径一条都跑不起来（覆盖率 13%）。
 * 这次先把服务端 harness 建起来，适配器才有可验证的对端。</p>
 *
 * <p>Milo 1.x 的服务端有三处与直觉不符的地方（都靠实测确认，按名字猜会找不到类）：
 * ① 服务端传输实现不在 {@code milo-sdk-server} 里，而在独立的 {@code milo-transport} 制品；
 * ② {@code Namespace} 接口<b>没有生命周期</b>，节点在构造器里建、再用
 * {@code AddressSpaceManager.register(...)} 注册，而不是覆盖 {@code onStartup()}；
 * ③ 端点用 {@code setBindAddress/setBindPort/setHostname} 拼装，没有 {@code setEndpointUrl}。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
final class OpcUaTestServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpcUaTestServer.class);

    private static final long STARTUP_TIMEOUT_SECONDS = 20L;

    /** 标量值等级（OPC UA 规范：-1 表示标量）。 */
    private static final int SCALAR_VALUE_RANK = -1;

    /** 可读写访问等级（CurrentRead | CurrentWrite = 3）。 */
    private static final int ACCESS_READ_WRITE = 3;

    /** 只读访问等级（CurrentRead = 1）。 */
    private static final int ACCESS_READ_ONLY = 1;

    private final OpcUaServer server;

    private final TestNamespace namespace;

    private final int port;

    OpcUaTestServer() throws Exception {
        this.port = freePort();
        OpcUaServerConfig config = OpcUaServerConfig.builder()
                .setApplicationUri("urn:ypbin:iot:test-server")
                .setApplicationName(LocalizedText.english("ypbin iot test server"))
                .setEndpoints(Set.of(EndpointConfig.newBuilder()
                        .setBindAddress("127.0.0.1")
                        .setBindPort(port)
                        .setHostname("127.0.0.1")
                        .setSecurityPolicy(SecurityPolicy.None)
                        .setSecurityMode(MessageSecurityMode.None)
                        .build()))
                .build();
        OpcServerTransportFactory transportFactory = profile -> new OpcTcpServerTransport(
                OpcTcpServerTransportConfig.newBuilder().build());
        this.server = new OpcUaServer(config, transportFactory);
        this.server.startup().get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        this.namespace = new TestNamespace(server);
        // Namespace 无生命周期：注册到地址空间管理器即可
        this.server.getAddressSpaceManager().register(namespace);
        log.debug("[test] opcua test server listening on {}", port);
    }

    int port() {
        return port;
    }

    /**
     * 服务端端点地址。
     *
     * @return 形如 {@code opc.tcp://127.0.0.1:<port>}
     */
    String endpointUrl() {
        return "opc.tcp://127.0.0.1:" + port;
    }

    /**
     * 命名空间限定的节点地址。
     *
     * @param identifier 字符串标识
     * @return 形如 {@code ns=<index>;s=<identifier>} 的地址
     */
    String nodeAddress(String identifier) {
        return "ns=" + namespace.getNamespaceIndex().intValue() + ";s=" + identifier;
    }

    /**
     * 模拟设备侧主动改值（推送订阅场景用）。
     *
     * @param identifier 字符串标识
     * @param value      新值
     */
    void updateValue(String identifier, Object value) {
        namespace.updateValue(identifier, value);
    }

    @Override
    public void close() {
        try {
            server.getAddressSpaceManager().unregister(namespace);
            server.shutdown().get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ex) {
            log.warn("[test] failed to shut down opcua test server", ex);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 测试命名空间：可读写变量 + 只读变量。
     *
     * @author wenbin
     * @since 2026-09-14
     */
    static final class TestNamespace extends ManagedNamespace {

        private final Map<String, UaVariableNode> nodes = new LinkedHashMap<>();

        /** 已创建的数据项（订阅场景下服务器按它推送变更）。 */
        private final Map<NodeId, DataItem> dataItems = new ConcurrentHashMap<>();

        /** 节点属性观察者：把 Value 变更桥接到 DataItem。 */
        private final Map<NodeId, AttributeObserver> observers = new ConcurrentHashMap<>();

        TestNamespace(OpcUaServer server) {
            super(server, "urn:ypbin:iot:test");
            addVariable("Temperature", 23.5D, true);
            addVariable("Pressure", 1013L, true);
            addVariable("SerialNumber", "SN-0001", false);
        }

        void updateValue(String identifier, Object value) {
            UaVariableNode node = nodes.get(identifier);
            if (node != null) {
                node.setValue(new DataValue(new Variant(value)));
            }
        }

        private void addVariable(String identifier, Object initial, boolean writable) {
            UaVariableNode node = UaVariableNode.builder(getNodeContext())
                    .setNodeId(newNodeId(identifier))
                    .setBrowseName(newQualifiedName(identifier))
                    .setDisplayName(LocalizedText.english(identifier))
                    .setDataType(dataTypeOf(initial))
                    .setTypeDefinition(Identifiers.BaseDataVariableType)
                    .setValueRank(SCALAR_VALUE_RANK)
                    .setAccessLevel(Unsigned.ubyte(writable ? ACCESS_READ_WRITE : ACCESS_READ_ONLY))
                    .setUserAccessLevel(Unsigned.ubyte(writable ? ACCESS_READ_WRITE : ACCESS_READ_ONLY))
                    .setValue(new DataValue(new Variant(initial)))
                    .buildAndAdd();
            // 挂到 Objects 文件夹，否则浏览与按路径寻址都找不到它
            node.addReference(new Reference(node.getNodeId(), Identifiers.Organizes,
                    Identifiers.ObjectsFolder.expanded(), false));
            // buildAndAdd() 已经把节点加入 node manager；再 addNode 一次是重复注册
            nodes.put(identifier, node);
        }

        private static NodeId dataTypeOf(Object value) {
            if (value instanceof Double) {
                return Identifiers.Double;
            }
            if (value instanceof Long) {
                return Identifiers.Int64;
            }
            return Identifiers.String;
        }

        // Milo 1.x 把「数据项注册」交给命名空间自己维护：这四个方法是抽象方法，
        // 必须实现；用一张 map 跟踪已创建的数据项，是 Milo 的标准模式。
        @Override
        public void onDataItemsCreated(List<DataItem> items) {
            // 关键接线：把节点的 Value 变更转发给 DataItem，否则服务器永远不会上报变更
            // （只把 DataItem 存进 map 是"只写不读"，订阅推送整条链路静默失效）。
            // 这也是上一轮把 nativeSubscriptionMustReceivePush 标为未验证的真因：
            // 缺陷在 harness，不在产品代码。
            items.forEach(item -> {
                NodeId nodeId = item.getReadValueId().getNodeId();
                dataItems.put(nodeId, item);
                UaVariableNode node = nodes.get(nodeId.getIdentifier() instanceof String identifier
                        ? identifier : "");
                if (node == null) {
                    return;
                }
                AttributeObserver observer = (observed, attributeId, value) -> {
                    if (AttributeId.Value == attributeId && value instanceof DataValue dataValue) {
                        item.setValue(dataValue);
                    }
                };
                observers.put(nodeId, observer);
                node.addAttributeObserver(observer);
            });
        }

        @Override
        public void onDataItemsModified(List<DataItem> items) {
            onDataItemsCreated(items);
        }

        @Override
        public void onDataItemsDeleted(List<DataItem> items) {
            items.forEach(item -> {
                NodeId nodeId = item.getReadValueId().getNodeId();
                dataItems.remove(nodeId);
                AttributeObserver observer = observers.remove(nodeId);
                if (observer != null) {
                    UaVariableNode node = nodes.get(nodeId.getIdentifier() instanceof String identifier
                            ? identifier : "");
                    if (node != null) {
                        node.removeAttributeObserver(observer);
                    }
                }
            });
        }

        @Override
        public void onMonitoringModeChanged(List<MonitoredItem> items) {
            // 监控模式切换由服务器按 MonitoringMode.Reporting 处理，无需额外动作
        }
    }
}
