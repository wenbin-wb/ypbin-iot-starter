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
package cn.ypbin.iot.core.spi;

import cn.ypbin.iot.core.context.MetricsRecorder;
import cn.ypbin.iot.core.i18n.IotMessageKeys;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.Quality;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把 {@link BytePayloadDecoderRegistry} 应用到「原始 {@code byte[]} 点位值」的统一入口。
 *
 * <p><b>为什么需要这个门面</b>：二进制交付路径分散在多个协议模块（TCP {@code binary}、
 * MQTT {@code binary}、OPC UA {@code ByteString}），如果每个模块各写一遍「查解码器 / 失败判 BAD /
 * 计数」的逻辑，三处语义必然漂移（其中一处忘了计数就是又一次「静默降级」）。本类把这段
 * <b>唯一的实现</b>放在契约层，协议模块只调一行。</p>
 *
 * <p><b>行为矩阵（冻结，测试见 {@code BytePayloadDecodingTest}）</b>：</p>
 * <table border="1">
 *   <caption>解码路由</caption>
 *   <tr><th>场景</th><th>返回</th><th>指标</th><th>理由</th></tr>
 *   <tr>
 *     <td>值不是 GOOD / 不是 {@code byte[]}</td>
 *     <td>原值</td><td>无</td>
 *     <td>本 SPI 只处理「交付给宿主的原始字节」，其余值形态（字符串/数值）由协议模块自己解码</td>
 *   </tr>
 *   <tr>
 *     <td>注册表为空（宿主未注册任何解码器）</td>
 *     <td>原值（{@code byte[]} 照旧交付）</td><td>无（debug 日志）</td>
 *     <td>宿主没有选择启用解码时，行为与「没有本 SPI」<b>逐字一致</b>；不制造无意义的错误计数</td>
 *   </tr>
 *   <tr>
 *     <td>注册表非空但没有解码器认领</td>
 *     <td>原值（{@code byte[]} 照旧交付）</td>
 *     <td>{@code iot.common.payload.decoder.not-found}</td>
 *     <td>宿主已选择启用解码却漏了该点位 ⇒ 多半是配置遗漏，必须可见（issue #30 的「显式计数」）</td>
 *   </tr>
 *   <tr>
 *     <td>解码器认领但解码失败</td>
 *     <td><b>BAD</b> + {@code qualityReason=<失败原因消息键>}</td>
 *     <td>{@code iot.common.payload.decode-failed.<reason>}</td>
 *     <td>宁可显式 BAD 也不把垃圾值当 GOOD 交付（G6）</td>
 *   </tr>
 *   <tr>
 *     <td>解码器 {@code supports()} 抛异常（实现缺陷）</td>
 *     <td><b>BAD</b> + {@code decoder-error}</td>
 *     <td>{@code iot.common.payload.decode-failed.decoder-error}</td>
 *     <td>同上；MQTT/OPC UA 的回调栈没有传输层兜底，逃逸会打断整条链路</td>
 *   </tr>
 *   <tr>
 *     <td>解码器 {@code decode()} 抛异常（实现缺陷）</td>
 *     <td><b>BAD</b> + {@code decoder-error}</td>
 *     <td>{@code iot.common.payload.decode-failed.decoder-error}</td>
 *     <td>不得让宿主实现的缺陷打断协议线程，也不得静默吞掉</td>
 *   </tr>
 *   <tr>
 *     <td>解码成功</td>
 *     <td><b>GOOD</b> + 规范值（保留原地址与源时间戳）</td><td>无</td>
 *     <td>解码只改变值形态，不改变时序与地址</td>
 *   </tr>
 * </table>
 *
 * <p><b>线程模型</b>：本类在协议线程（Netty EventLoop / MQTT 客户端线程 / Milo 执行器）上被调用，
 * 因此只做内存操作；解码器实现必须遵守同样的非阻塞契约（见 {@link BytePayloadDecoder}）。</p>
 *
 * @author wenbin
 * @since 2026-10-17
 */
public final class BytePayloadDecoding {

    private static final Logger log = LoggerFactory.getLogger(BytePayloadDecoding.class);

    private BytePayloadDecoding() {
    }

    /**
     * 按注册表把点位值里的原始字节解码成规范值；不需要/无法解码时原样返回。
     *
     * @param value       协议层产出的点位值（非空）
     * @param deviceId    设备标识（非空）
     * @param protocol    协议标识（非空）
     * @param connectionId 链路标识（可空，按空串处理）
     * @param registry    解码器注册表（可空，按空注册表处理）
     * @param metrics     指标门面（非空）
     * @return 解码后的点位值；不适用时返回入参本身
     */
    public static PointValue apply(PointValue value, String deviceId, ProtocolCode protocol,
            @Nullable String connectionId, @Nullable BytePayloadDecoderRegistry registry,
            MetricsRecorder metrics) {
        if (!value.isGood() || !(value.value() instanceof byte[] payload)) {
            return value;
        }
        BytePayloadDecoderRegistry effective = registry == null
                ? BytePayloadDecoderRegistry.empty() : registry;
        if (effective.isEmpty()) {
            // 宿主没有注册任何解码器：这与「没有本 SPI」逐字一致，不制造错误计数
            if (log.isDebugEnabled()) {
                log.debug("[ypbin-iot] binary payload for device {} point {} kept as byte[]; "
                        + "no BytePayloadDecoder registered.", deviceId, value.address().raw());
            }
            return value;
        }
        DecodeContext context = new DecodeContext(deviceId, protocol, value.address().raw(), connectionId);
        Optional<BytePayloadDecoder> decoder;
        try {
            decoder = effective.find(context);
        } catch (RuntimeException ex) {
            // supports() 抛异常属实现缺陷（SPI 明令禁止），但绝不能让它逃到协议线程：
            // MQTT/OPC UA 的 dispatch 直接把调用栈交给协议库回调，异常逃逸会打断整条链路。
            // 这里按「认领声明失败」处理，且显式暴露为 decoder-error 指标 + ERROR 日志（不静默）。
            metrics.recordError(DecodeFailure.DECODER_ERROR.messageKey());
            log.error("[ypbin-iot] BytePayloadDecoder#supports threw for device {} point {}; "
                    + "treating the payload as undecodable rather than letting the exception escape",
                    deviceId, value.address().raw(), ex);
            return PointValue.bad(value.address(), Quality.BAD,
                    DecodeFailure.DECODER_ERROR.messageKey(), value.timestamp());
        }
        if (decoder.isEmpty()) {
            metrics.recordError(IotMessageKeys.PAYLOAD_DECODER_NOT_FOUND);
            log.debug("[ypbin-iot] no BytePayloadDecoder claimed device {} point {}; kept as byte[].",
                    deviceId, value.address().raw());
            return value;
        }
        return invoke(decoder.get(), payload, context, value, metrics);
    }

    /** 调用解码器并把结果映射为点位值；任何异常都转成显式 BAD，不向协议线程逃逸。 */
    private static PointValue invoke(BytePayloadDecoder decoder, byte[] payload, DecodeContext context,
            PointValue original, MetricsRecorder metrics) {
        DecodeOutcome outcome;
        try {
            outcome = decoder.decode(payload, context);
        } catch (RuntimeException ex) {
            // 宿主解码器的缺陷不得打断协议线程；但必须可见（完整堆栈 + 指标 + BAD）
            metrics.recordError(DecodeFailure.DECODER_ERROR.messageKey());
            log.error("[ypbin-iot] byte payload decoder {} threw for device {} point {}",
                    decoder.id(), context.deviceId(), context.rawAddress(), ex);
            return PointValue.bad(original.address(), Quality.BAD,
                    DecodeFailure.DECODER_ERROR.messageKey(), original.timestamp());
        }
        if (outcome == null) {
            // 返回 null 违反 SPI 契约（禁止用 null 表达失败）：按实现缺陷处理，绝不静默
            metrics.recordError(DecodeFailure.DECODER_ERROR.messageKey());
            log.error("[ypbin-iot] byte payload decoder {} returned null outcome for device {} point {}",
                    decoder.id(), context.deviceId(), context.rawAddress());
            return PointValue.bad(original.address(), Quality.BAD,
                    DecodeFailure.DECODER_ERROR.messageKey(), original.timestamp());
        }
        if (outcome.failure() != null) {
            String messageKey = outcome.failure().messageKey();
            metrics.recordError(messageKey);
            log.debug("[ypbin-iot] byte payload decoder {} failed for device {} point {}: {}",
                    decoder.id(), context.deviceId(), context.rawAddress(), outcome.failure().getDetail());
            return PointValue.bad(original.address(), Quality.BAD, messageKey, original.timestamp());
        }
        Object decoded = outcome.value();
        // 紧凑构造器已保证失败必带原因、成功必带值；这里再做一次防御是因为 null 一旦流出，
        // 下游要么 NPE、要么把 null 当有效值落库（两种都比显式 BAD 糟）
        if (decoded == null) {
            metrics.recordError(DecodeFailure.DECODER_ERROR.messageKey());
            log.error("[ypbin-iot] byte payload decoder {} produced null value for device {} point {}",
                    decoder.id(), context.deviceId(), context.rawAddress());
            return PointValue.bad(original.address(), Quality.BAD,
                    DecodeFailure.DECODER_ERROR.messageKey(), original.timestamp());
        }
        return PointValue.good(original.address(), decoded, original.timestamp());
    }
}
