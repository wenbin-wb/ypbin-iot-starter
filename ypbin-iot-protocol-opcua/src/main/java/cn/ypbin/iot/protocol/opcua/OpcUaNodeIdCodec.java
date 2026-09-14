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

import cn.ypbin.iot.core.exception.AddressParseException;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import java.util.Optional;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

/**
 * OPC UA 地址（NodeId）解析。
 *
 * <p><b>只接受标准 NodeId 写法，不做猜测</b>：</p>
 *
 * <table border="1">
 *   <caption>支持的地址写法</caption>
 *   <tr><th>写法</th><th>含义</th></tr>
 *   <tr><td>{@code ns=2;s=Device.Temperature}</td><td>命名空间 2 的字符串标识</td></tr>
 *   <tr><td>{@code ns=2;i=1234}</td><td>命名空间 2 的数值标识</td></tr>
 *   <tr><td>{@code ns=2;g=...}</td><td>命名空间 2 的 GUID 标识</td></tr>
 *   <tr><td>{@code ns=2;b=...}</td><td>命名空间 2 的字节串标识</td></tr>
 *   <tr><td>{@code i=2258}</td><td>命名空间 0 的数值标识</td></tr>
 *   <tr><td>{@code s=MyVar}</td><td>命名空间 0 的字符串标识</td></tr>
 * </table>
 *
 * <p><b>为什么不接受裸标识符</b>（如 {@code Temperature}）：命名空间索引是 NodeId 的组成部分，
 * 缺失时无法推断用户指的是 ns=0（标准类型）还是 ns=2（厂商模型）。猜错的表现是
 * 「地址解析成功但读到 BadNodeIdUnknown」，比直接报错难排查得多——与 Modbus 侧同一口径。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
final class OpcUaNodeIdCodec {

    private static final ProtocolCode PROTOCOL = OpcUaAdapter.PROTOCOL_CODE;

    private OpcUaNodeIdCodec() {
    }

    /**
     * 解析地址。
     *
     * @param raw 原始地址
     * @return NodeId
     * @throws AddressParseException 写法不受支持时
     */
    static NodeId parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AddressParseException(PROTOCOL, raw, "NodeId must not be blank");
        }
        String text = raw.trim();
        if (text.endsWith("=")) {
            // 形如 ns=2;s= 的空标识符会被 parseSafe 接受，但得到的是无意义 NodeId，
            // 表现为「解析成功、读回 BadNodeIdUnknown」——比直接报错难排查
            throw new AddressParseException(PROTOCOL, raw, "NodeId identifier must not be empty");
        }
        Optional<NodeId> parsed = NodeId.parseSafe(text);
        if (parsed.isEmpty()) {
            throw new AddressParseException(PROTOCOL, raw,
                    "unsupported NodeId form; use 'ns=<index>;<type>=<identifier>' "
                            + "(e.g. ns=2;s=Device.Temperature) or '<type>=<identifier>' for namespace 0");
        }
        return parsed.get();
    }

    /**
     * 格式化地址（规范写法）。
     *
     * @param nodeId NodeId
     * @return 规范写法
     */
    static String format(NodeId nodeId) {
        return nodeId.toParseableString();
    }
}
